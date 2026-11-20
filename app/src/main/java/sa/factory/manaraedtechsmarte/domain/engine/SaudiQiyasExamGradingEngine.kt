package sa.factory.manaraedtechsmarte.domain.engine

import kotlin.math.*

/**
 * Saudi National Center for Assessment (Qiyas / قياس) Standardized Exam Grading Engine.
 * Supports GAT (القدرات العامة - كمي ولفظي) and Tahsili (التحصيلي - رياضيات، فيزياء، كيمياء، أحياء),
 * normalized T-score standard equating (Mean 65, SD 10), and smart time telemetry anomaly detection.
 *
 * Strictly zero emojis.
 */
class SaudiQiyasExamGradingEngine {

    companion object {
        const val QIYAS_SCALE_MEAN = 65.0
        const val QIYAS_SCALE_SD = 10.0
        const val MIN_QUESTION_READING_TIME_SEC = 6.0
        const val RAPID_GUESSING_PRESSURE_THRESHOLD = 0.25
    }

    enum class ExamType {
        GAT_GENERAL_APTITUDE,      // اختبار القدرات العامة
        TAHSILI_SUBJECT_ACHIEVEMENT // اختبار التحصيلي الدراسي
    }

    enum class QuestionSection {
        QUANTITATIVE_MATH, // كمي
        VERBAL_ARABIC,     // لفظي
        PHYSICS,           // فيزياء
        CHEMISTRY,         // كيمياء
        BIOLOGY            // أحياء
    }

    data class StudentAnswerRecord(
        val questionId: String,
        val section: QuestionSection,
        val selectedOption: String, // "A", "B", "C", "D"
        val correctOption: String,
        val timeSpentSeconds: Double,
        val meanStylusPressure: Double
    )

    data class SectionScore(
        val section: QuestionSection,
        val totalQuestions: Int,
        val correctAnswers: Int,
        val rawPercentage: Double,
        val rapidGuessesCount: Int
    )

    data class NormalizedExamReport(
        val studentNationalId: String,
        val examType: ExamType,
        val totalQuestions: Int,
        val correctCount: Int,
        val rawAccuracyPct: Double,
        val sectionScores: List<SectionScore>,
        val normalizedQiyasScore: Double, // 0 to 100 on Qiyas standard scale
        val estimatedPercentile: Double,   // 0% to 99.9%
        val academicTierAr: String,
        val anomalyWarnings: List<String>
    )

    /**
     * Grades a completed student exam submission and computes normalized Qiyas equating.
     */
    fun gradeExamSubmission(
        studentNationalId: String,
        examType: ExamType,
        answers: List<StudentAnswerRecord>
    ): NormalizedExamReport {
        if (answers.isEmpty()) {
            return NormalizedExamReport(
                studentNationalId = studentNationalId,
                examType = examType,
                totalQuestions = 0,
                correctCount = 0,
                rawAccuracyPct = 0.0,
                sectionScores = emptyList(),
                normalizedQiyasScore = 0.0,
                estimatedPercentile = 0.0,
                academicTierAr = "غير مكتمل",
                anomalyWarnings = listOf("[ALERT] No answer records provided")
            )
        }

        val totalQuestions = answers.size
        var totalCorrect = 0
        val warnings = mutableListOf<String>()

        val grouped = answers.groupBy { it.section }
        val sectionList = mutableListOf<SectionScore>()

        for ((sec, records) in grouped) {
            val secTotal = records.size
            var secCorrect = 0
            var rapidGuesses = 0

            for (ans in records) {
                if (ans.selectedOption.equals(ans.correctOption, ignoreCase = true)) {
                    secCorrect++
                    totalCorrect++
                }

                // Rapid Guessing Detection: Time < 6 seconds with faint stylus contact
                if (ans.timeSpentSeconds < MIN_QUESTION_READING_TIME_SEC && ans.meanStylusPressure < RAPID_GUESSING_PRESSURE_THRESHOLD) {
                    rapidGuesses++
                }
            }

            if (rapidGuesses > secTotal / 3) {
                warnings.add("[ALERT] Anomaly: Section $sec has $rapidGuesses suspected rapid guesses out of $secTotal questions")
            }

            val rawPct = (secCorrect.toDouble() / secTotal.toDouble()) * 100.0
            sectionList.add(
                SectionScore(
                    section = sec,
                    totalQuestions = secTotal,
                    correctAnswers = secCorrect,
                    rawPercentage = round(rawPct * 10.0) / 10.0,
                    rapidGuessesCount = rapidGuesses
                )
            )
        }

        val rawAccuracy = (totalCorrect.toDouble() / totalQuestions.toDouble()) * 100.0

        // Standard Qiyas Equating:
        // Assume exam population raw mean is 50%, with SD of 15%
        val populationMean = 50.0
        val populationSd = 15.0
        val zScore = (rawAccuracy - populationMean) / populationSd
        val tScore = QIYAS_SCALE_MEAN + (zScore * QIYAS_SCALE_SD)
        val clampedScore = tScore.coerceIn(20.0, 100.0)
        val finalScore = round(clampedScore * 10.0) / 10.0

        // Percentile estimation using error function approximation
        val percentile = calculatePercentileFromZ(zScore)

        val tier = when {
            finalScore >= 95.0 -> "فئة النخبة الأولى (أعلى 1%)"
            finalScore >= 85.0 -> "فئة الموهوبين والمتميزين"
            finalScore >= 75.0 -> "فئة متقدمة فوق المتوسط"
            finalScore >= 65.0 -> "فئة المتوسط الوطني"
            else -> "فئة بحاجة إلى برامج تعزيز تأسيسية"
        }

        return NormalizedExamReport(
            studentNationalId = studentNationalId,
            examType = examType,
            totalQuestions = totalQuestions,
            correctCount = totalCorrect,
            rawAccuracyPct = round(rawAccuracy * 10.0) / 10.0,
            sectionScores = sectionList,
            normalizedQiyasScore = finalScore,
            estimatedPercentile = round(percentile * 10.0) / 10.0,
            academicTierAr = tier,
            anomalyWarnings = warnings
        )
    }

    /**
     * Cumulative normal distribution approximation for percentile calculation.
     */
    fun calculatePercentileFromZ(z: Double): Double {
        val sign = if (z < 0) -1.0 else 1.0
        val x = abs(z) / sqrt(2.0)
        // Abramowitz and Stegun approximation for erf(x)
        val t = 1.0 / (1.0 + 0.3275911 * x)
        val a1 = 0.254829592
        val a2 = -0.284496736
        val a3 = 1.421413741
        val a4 = -1.453152027
        val a5 = 1.061405429
        val erf = 1.0 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * exp(-x * x)
        val cdf = 0.5 * (1.0 + sign * erf)
        return (cdf * 100.0).coerceIn(0.1, 99.9)
    }

    /**
     * Validates Saudi Student 10-digit National ID / Iqama.
     */
    fun validateStudentId(id: String): Boolean {
        if (!Regex("^[12]\\d{9}$").matches(id)) {
            return false
        }
        var sum = 0
        for (i in 0 until 10) {
            val digit = id[i] - '0'
            if (i % 2 == 0) {
                val doubled = digit * 2
                sum += if (doubled > 9) doubled - 9 else doubled
            } else {
                sum += digit
            }
        }
        return sum % 10 == 0
    }
}
