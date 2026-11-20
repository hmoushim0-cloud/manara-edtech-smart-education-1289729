package sa.factory.manaraedtechsmarte

import sa.factory.manaraedtechsmarte.data.repository.ManaraEdtechRepositoryImpl
import sa.factory.manaraedtechsmarte.domain.engine.SaudiQiyasExamGradingEngine
import sa.factory.manaraedtechsmarte.domain.engine.SaudiSmartStylusDigitizerEngine
import sa.factory.manaraedtechsmarte.hardware.HardwareSensorDriver
import sa.factory.manaraedtechsmarte.model.ManaraEdtechItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ManaraEdtechRegressionTest {

    // 1. Checkout & Tax Calculation
    @Test
    fun testCheckoutCalculationAndTax() = runBlocking {
        val sensorDriver = HardwareSensorDriver()
        val repository = ManaraEdtechRepositoryImpl(sensorDriver)

        val item1 = ManaraEdtechItem("c1", "حزمة مراجعة القدرات الشاملة", "الوصف", 150.0, "معتمد", "عام")
        val item2 = ManaraEdtechItem("c2", "تدريب القلم الذكي للتحصيلي", "الوصف", 200.0, "معتمد", "عام")

        repository.addToCart(item1)
        repository.addToCart(item2)

        val order = repository.checkout("Mada EMV")

        assertEquals(350.0, order.subtotalSar, 0.01)
        assertEquals(52.5, order.vatSar, 0.01)
        assertEquals(402.5, order.totalSar, 0.01)
        assertEquals(0, repository.activeCart.value.size)
    }

    // 2. Saudi Student National ID / Iqama Validation (10 digits starting with 1 or 2)
    @Test
    fun testSaudiStudentIdValidation() {
        val engine = SaudiQiyasExamGradingEngine()

        // Valid Citizen ID
        assertTrue(engine.validateStudentId("1010101010"))
        // Valid Resident Iqama
        assertTrue(engine.validateStudentId("2000000008"))

        // Invalid: Starts with 7
        assertFalse(engine.validateStudentId("7010101010"))
        // Invalid length
        assertFalse(engine.validateStudentId("101010101"))
        // Invalid Luhn checksum
        assertFalse(engine.validateStudentId("1010101011"))
    }

    // 3. 4096-Level Stylus Pressure Curve Calibration
    @Test
    fun testStylusPressureCalibration() {
        val engine = SaudiSmartStylusDigitizerEngine()

        // Linear: 2048 / 4095 ~ 0.5
        val linear = engine.calibratePressure(2048, SaudiSmartStylusDigitizerEngine.PressureCurve.LINEAR)
        assertEquals(0.5, linear, 0.01)

        // Min pressure = 0.0, Max pressure = 1.0
        assertEquals(0.0, engine.calibratePressure(0, SaudiSmartStylusDigitizerEngine.PressureCurve.LINEAR), 0.001)
        assertEquals(1.0, engine.calibratePressure(4095, SaudiSmartStylusDigitizerEngine.PressureCurve.LINEAR), 0.001)

        // Calligraphic smooth curve (sigmoid)
        val smoothMid = engine.calibratePressure(2048, SaudiSmartStylusDigitizerEngine.PressureCurve.CALLIGRAPHIC_SMOOTH)
        assertEquals(0.5, smoothMid, 0.05)

        // Firm exam note curve requires firmer touch
        val firmMid = engine.calibratePressure(2048, SaudiSmartStylusDigitizerEngine.PressureCurve.FIRM_EXAM_NOTE)
        assertTrue(firmMid < 0.35)
    }

    // 4. Palm Rejection Filter (Threshold 12.0 mm)
    @Test
    fun testStylusPalmRejectionFilter() {
        val engine = SaudiSmartStylusDigitizerEngine()

        // Active stylus tip contact radius is ~ 1.5mm
        val tipPoint = SaudiSmartStylusDigitizerEngine.RawStylusPoint(
            rawX = 32768,
            rawY = 32768,
            rawPressure = 1500,
            tiltAzimuthDeg = 45,
            tiltAltitudeDeg = 60,
            contactRadiusMm = 1.8,
            toolType = SaudiSmartStylusDigitizerEngine.TOOL_PEN_TIP,
            timestampEpochMs = 1700000000000L
        )

        val processedTip = engine.processStylusPoint(tipPoint, canvasWidthPx = 1080.0, canvasHeightPx = 2400.0)
        assertFalse(processedTip.isPalmContactRejected)
        assertTrue(processedTip.normalizedPressure > 0.0)
        assertEquals("PEN_TIP", processedTip.toolName)

        // Wrist/Palm contact radius is large (e.g. 25.0 mm)
        val palmPoint = tipPoint.copy(contactRadiusMm = 24.5)
        val processedPalm = engine.processStylusPoint(palmPoint, canvasWidthPx = 1080.0, canvasHeightPx = 2400.0)
        assertTrue(processedPalm.isPalmContactRejected)
        assertEquals(0.0, processedPalm.normalizedPressure, 0.001)
    }

    // 5. Stylus Jitter Exponential Moving Average Smoothing
    @Test
    fun testStylusJitterSmoothing() {
        val engine = SaudiSmartStylusDigitizerEngine()

        val prev = SaudiSmartStylusDigitizerEngine.ProcessedStylusPoint(
            canvasX = 500.0,
            canvasY = 1000.0,
            normalizedPressure = 0.5,
            isPalmContactRejected = false,
            isHovering = false,
            toolName = "PEN_TIP"
        )

        // Sudden jitter spike
        val raw = SaudiSmartStylusDigitizerEngine.RawStylusPoint(
            rawX = 34000, // translates to ~560 px
            rawY = 28000,
            rawPressure = 2000,
            tiltAzimuthDeg = 0,
            tiltAltitudeDeg = 90,
            contactRadiusMm = 1.5,
            toolType = SaudiSmartStylusDigitizerEngine.TOOL_PEN_TIP,
            timestampEpochMs = 1700000000100L
        )

        val smoothed = engine.processStylusPoint(
            raw = raw,
            canvasWidthPx = 1080.0,
            canvasHeightPx = 2400.0,
            smoothingAlpha = 0.5,
            prevPoint = prev
        )

        // Position should be smoothed halfway between 500 and target
        assertTrue(smoothed.canvasX > 500.0)
        assertTrue(smoothed.canvasX < 560.0)
    }

    // 6. Packet Serialization & CRC-16 CCITT
    @Test
    fun testStylusPacketSerializationAndCrc() {
        val engine = SaudiSmartStylusDigitizerEngine()
        val raw = SaudiSmartStylusDigitizerEngine.RawStylusPoint(
            rawX = 12345,
            rawY = 54321,
            rawPressure = 3500,
            tiltAzimuthDeg = 180,
            tiltAltitudeDeg = 45,
            contactRadiusMm = 2.0,
            toolType = SaudiSmartStylusDigitizerEngine.TOOL_PEN_TIP,
            timestampEpochMs = 1700000000000L
        )

        val packet = engine.serializeTelemetryFrame(raw)
        assertEquals(20, packet.size)
        assertEquals(0x50.toByte(), packet[0]) // 'P'
        assertEquals(0x45.toByte(), packet[1]) // 'E'
        assertEquals(0x01.toByte(), packet[2]) // Protocol Version 1

        val crcCalculated = engine.computeCrc16Ccitt(packet.copyOfRange(0, 16))
        assertTrue(crcCalculated > 0)
    }

    // 7. BLE Exam Desk Proximity Ranging
    @Test
    fun testBleExamDeskProximityFormula() {
        val engine = SaudiSmartStylusDigitizerEngine()

        // Desk beacon close (RSSI = -65 dBm, TxPower = -59 dBm) -> ~2.0m
        val nearBeacon = engine.evaluateExamBeaconProximity(
            studentId = "STU-9912",
            deskBeaconId = "DESK-A12",
            rssiDbm = -65,
            txPower1mDbm = -59
        )
        assertTrue(nearBeacon.isWithinAssignedDesk)
        assertTrue(nearBeacon.estimatedDistanceMeters in 1.5..2.5)

        // Beacon too far (RSSI = -85 dBm) -> > 10m
        val farBeacon = engine.evaluateExamBeaconProximity(
            studentId = "STU-9912",
            deskBeaconId = "DESK-A12",
            rssiDbm = -85,
            txPower1mDbm = -59
        )
        assertFalse(farBeacon.isWithinAssignedDesk)
        assertTrue(farBeacon.estimatedDistanceMeters > 5.0)
    }

    // 8. Qiyas GAT Standardized Exam Grading & T-Score Equating
    @Test
    fun testQiyasGatExamGrading() {
        val engine = SaudiQiyasExamGradingEngine()

        val answers = listOf(
            SaudiQiyasExamGradingEngine.StudentAnswerRecord("q1", SaudiQiyasExamGradingEngine.QuestionSection.QUANTITATIVE_MATH, "A", "A", 25.0, 0.75),
            SaudiQiyasExamGradingEngine.StudentAnswerRecord("q2", SaudiQiyasExamGradingEngine.QuestionSection.QUANTITATIVE_MATH, "B", "B", 30.0, 0.80),
            SaudiQiyasExamGradingEngine.StudentAnswerRecord("q3", SaudiQiyasExamGradingEngine.QuestionSection.VERBAL_ARABIC, "C", "C", 18.0, 0.65),
            SaudiQiyasExamGradingEngine.StudentAnswerRecord("q4", SaudiQiyasExamGradingEngine.QuestionSection.VERBAL_ARABIC, "D", "A", 15.0, 0.60)
        )

        val report = engine.gradeExamSubmission(
            studentNationalId = "1010101010",
            examType = SaudiQiyasExamGradingEngine.ExamType.GAT_GENERAL_APTITUDE,
            answers = answers
        )

        assertEquals(4, report.totalQuestions)
        assertEquals(3, report.correctCount)
        assertEquals(75.0, report.rawAccuracyPct, 0.01)
        // 75% accuracy vs 50% mean -> z = +1.667 -> T = 65 + 16.67 = 81.7
        assertTrue(report.normalizedQiyasScore in 80.0..85.0)
        assertTrue(report.estimatedPercentile > 90.0)
    }

    // 9. Rapid Guessing Anomaly Detection
    @Test
    fun testRapidGuessingAnomalyDetection() {
        val engine = SaudiQiyasExamGradingEngine()

        // 3 questions answered in < 4s with very faint pressure (< 0.20)
        val answers = listOf(
            SaudiQiyasExamGradingEngine.StudentAnswerRecord("q1", SaudiQiyasExamGradingEngine.QuestionSection.QUANTITATIVE_MATH, "A", "B", 3.0, 0.10),
            SaudiQiyasExamGradingEngine.StudentAnswerRecord("q2", SaudiQiyasExamGradingEngine.QuestionSection.QUANTITATIVE_MATH, "C", "D", 2.5, 0.12),
            SaudiQiyasExamGradingEngine.StudentAnswerRecord("q3", SaudiQiyasExamGradingEngine.QuestionSection.QUANTITATIVE_MATH, "B", "A", 4.0, 0.15)
        )

        val report = engine.gradeExamSubmission(
            studentNationalId = "1010101010",
            examType = SaudiQiyasExamGradingEngine.ExamType.GAT_GENERAL_APTITUDE,
            answers = answers
        )

        val mathSec = report.sectionScores.first { it.section == SaudiQiyasExamGradingEngine.QuestionSection.QUANTITATIVE_MATH }
        assertEquals(3, mathSec.rapidGuessesCount)
        assertTrue(report.anomalyWarnings.isNotEmpty())
    }

    // 10. Normal Distribution Percentile Function
    @Test
    fun testNormalDistributionPercentileCalculation() {
        val engine = SaudiQiyasExamGradingEngine()

        // z = 0 (Mean) -> 50th percentile
        assertEquals(50.0, engine.calculatePercentileFromZ(0.0), 1.0)

        // z = +1.0 (1 SD above mean) -> ~84.1%
        assertEquals(84.1, engine.calculatePercentileFromZ(1.0), 1.0)

        // z = +2.0 (2 SD above mean) -> ~97.7%
        assertEquals(97.7, engine.calculatePercentileFromZ(2.0), 1.0)

        // z = -1.0 (1 SD below mean) -> ~15.9%
        assertEquals(15.9, engine.calculatePercentileFromZ(-1.0), 1.0)
    }
}
