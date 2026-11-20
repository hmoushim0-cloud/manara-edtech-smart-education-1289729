package sa.factory.manaraedtechsmarte.domain.engine

import java.nio.ByteBuffer
import kotlin.math.*

/**
 * Saudi Smart Stylus Digitizer Engine.
 * Handles 4096-level active stylus telemetry, palm rejection filtering,
 * Arabic calligraphy pressure curves, and BLE beacon exam desk proximity.
 *
 * Strictly zero emojis.
 */
class SaudiSmartStylusDigitizerEngine {

    companion object {
        const val MAX_PRESSURE_LEVEL = 4095
        const val MAGIC_BYTE_STYLUS: Byte = 0x50 // 'P'
        const val MAGIC_BYTE_DIGITIZER: Byte = 0x45 // 'E'
        const val PROTOCOL_VERSION: Byte = 0x01

        const val TOOL_PEN_TIP: Byte = 0x01
        const val TOOL_ERASER: Byte = 0x02
        const val TOOL_HOVER: Byte = 0x03

        const val MAX_VALID_DESK_DISTANCE_METERS = 2.5
        const val MAX_ALLOWED_PALM_CONTACT_RADIUS_MM = 12.0
    }

    enum class PressureCurve {
        LINEAR,
        CALLIGRAPHIC_SMOOTH,
        FIRM_EXAM_NOTE
    }

    data class RawStylusPoint(
        val rawX: Int,
        val rawY: Int,
        val rawPressure: Int,
        val tiltAzimuthDeg: Int,
        val tiltAltitudeDeg: Int,
        val contactRadiusMm: Double,
        val toolType: Byte,
        val timestampEpochMs: Long
    )

    data class ProcessedStylusPoint(
        val canvasX: Double,
        val canvasY: Double,
        val normalizedPressure: Double, // 0.0 to 1.0
        val isPalmContactRejected: Boolean,
        val isHovering: Boolean,
        val toolName: String
    )

    data class BleExamBeaconTelemetry(
        val studentId: String,
        val deskBeaconId: String,
        val rssiDbm: Int,
        val estimatedDistanceMeters: Double,
        val isWithinAssignedDesk: Boolean
    )

    /**
     * Calibrates raw 0-4095 pressure according to the chosen curve.
     */
    fun calibratePressure(rawPressure: Int, curve: PressureCurve): Double {
        val clamped = rawPressure.coerceIn(0, MAX_PRESSURE_LEVEL)
        val normalized = clamped.toDouble() / MAX_PRESSURE_LEVEL.toDouble()

        return when (curve) {
            PressureCurve.LINEAR -> normalized
            PressureCurve.CALLIGRAPHIC_SMOOTH -> {
                // S-curve sigmoid for fluid Arabic handwriting
                1.0 / (1.0 + exp(-8.0 * (normalized - 0.5)))
            }
            PressureCurve.FIRM_EXAM_NOTE -> {
                // Quadratic curve requiring firmer stylus touch to trigger high line width
                normalized.pow(1.8)
            }
        }
    }

    /**
     * Filters and translates raw digitizer input to canvas coordinates.
     */
    fun processStylusPoint(
        raw: RawStylusPoint,
        canvasWidthPx: Double,
        canvasHeightPx: Double,
        smoothingAlpha: Double = 0.75,
        prevPoint: ProcessedStylusPoint? = null,
        curve: PressureCurve = PressureCurve.CALLIGRAPHIC_SMOOTH
    ): ProcessedStylusPoint {
        // 1. Palm Rejection: If contact radius exceeds stylus tip threshold (>12mm), reject as wrist/palm touch
        val isPalm = raw.contactRadiusMm > MAX_ALLOWED_PALM_CONTACT_RADIUS_MM

        // 2. Coordinate Mapping (0..65535 to Canvas Resolution)
        val targetX = (raw.rawX.toDouble() / 65535.0) * canvasWidthPx
        val targetY = (raw.rawY.toDouble() / 65535.0) * canvasHeightPx

        // 3. Exponential Moving Average Jitter Smoothing
        val smoothedX = if (prevPoint != null && !prevPoint.isPalmContactRejected) {
            smoothingAlpha * targetX + (1.0 - smoothingAlpha) * prevPoint.canvasX
        } else {
            targetX
        }

        val smoothedY = if (prevPoint != null && !prevPoint.isPalmContactRejected) {
            smoothingAlpha * targetY + (1.0 - smoothingAlpha) * prevPoint.canvasY
        } else {
            targetY
        }

        val isHovering = raw.rawPressure == 0 || raw.toolType == TOOL_HOVER
        val normalizedPressure = if (isHovering || isPalm) 0.0 else calibratePressure(raw.rawPressure, curve)

        val toolName = when (raw.toolType) {
            TOOL_PEN_TIP -> "PEN_TIP"
            TOOL_ERASER -> "ERASER"
            TOOL_HOVER -> "HOVER"
            else -> "UNKNOWN"
        }

        return ProcessedStylusPoint(
            canvasX = smoothedX,
            canvasY = smoothedY,
            normalizedPressure = normalizedPressure,
            isPalmContactRejected = isPalm,
            isHovering = isHovering,
            toolName = toolName
        )
    }

    /**
     * Computes CRC-16 CCITT (Polynomial 0x1021) for binary packet integrity verification.
     */
    fun computeCrc16Ccitt(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            for (i in 0 until 8) {
                val bit = ((b.toInt() shr (7 - i)) and 1) == 1
                val c15 = ((crc shr 15) and 1) == 1
                crc = crc shl 1
                if (c15 xor bit) {
                    crc = crc xor 0x1021
                }
            }
        }
        return crc and 0xFFFF
    }

    /**
     * Serializes telemetry frame into binary packet with CRC-16 verification.
     */
    fun serializeTelemetryFrame(raw: RawStylusPoint): ByteArray {
        val buffer = ByteBuffer.allocate(20)
        buffer.put(MAGIC_BYTE_STYLUS)
        buffer.put(MAGIC_BYTE_DIGITIZER)
        buffer.put(PROTOCOL_VERSION)
        buffer.put(raw.toolType)
        buffer.putShort(raw.rawX.toShort())
        buffer.putShort(raw.rawY.toShort())
        buffer.putShort(raw.rawPressure.toShort())
        buffer.put(raw.tiltAzimuthDeg.toByte())
        buffer.put(raw.tiltAltitudeDeg.toByte())
        buffer.putShort((raw.contactRadiusMm * 100.0).toInt().toShort())

        val partial = buffer.array().copyOfRange(0, 16)
        val crc = computeCrc16Ccitt(partial)
        buffer.position(16)
        buffer.putShort(crc.toShort())

        return buffer.array()
    }

    /**
     * Estimates desk proximity distance from BLE RSSI using Path Loss Formula.
     */
    fun evaluateExamBeaconProximity(
        studentId: String,
        deskBeaconId: String,
        rssiDbm: Int,
        txPower1mDbm: Int = -59,
        pathLossExponent: Double = 2.0
    ): BleExamBeaconTelemetry {
        val ratio = (txPower1mDbm - rssiDbm) / (10.0 * pathLossExponent)
        val distanceMeters = 10.0.pow(ratio)
        val isWithinAssignedDesk = distanceMeters <= MAX_VALID_DESK_DISTANCE_METERS

        return BleExamBeaconTelemetry(
            studentId = studentId,
            deskBeaconId = deskBeaconId,
            rssiDbm = rssiDbm,
            estimatedDistanceMeters = (round(distanceMeters * 100.0) / 100.0),
            isWithinAssignedDesk = isWithinAssignedDesk
        )
    }
}
