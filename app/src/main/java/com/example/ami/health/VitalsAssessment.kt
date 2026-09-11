package com.example.ami.health

/**
 * Deterministic triage of a [VitalsSnapshot].
 *
 * This is plain arithmetic on purpose. It runs *before* any model is consulted so that a
 * dangerous reading is caught by code that cannot hallucinate, and so that an URGENT
 * result can hard-stop the diet suggestions rather than relying on the model to decline.
 *
 * The thresholds are widely published general-adult guidance, not a diagnosis, and the
 * wording throughout stays descriptive ("high enough that someone should look at it")
 * rather than diagnostic. Glucose in particular depends on whether the reading was taken
 * fasting or after a meal, which AMI does not know - so only the extremes are treated as
 * actionable and everything in between is flagged for a human rather than interpreted.
 */
object VitalsAssessment {

    enum class Severity { NORMAL, CAUTION, URGENT }

    data class Finding(
        val metric: String,
        val severity: Severity,
        /** Plain language, safe to speak aloud to the user. */
        val summary: String
    )

    data class Assessment(val findings: List<Finding>) {
        val severity: Severity
            get() = findings.maxOfOrNull { it.severity } ?: Severity.NORMAL

        val isUrgent: Boolean get() = severity == Severity.URGENT

        val urgentFindings: List<Finding> get() = findings.filter { it.severity == Severity.URGENT }

        /** Compact description handed to the diet model, e.g. "blood pressure: CAUTION (…)". */
        fun describe(): String =
            if (findings.isEmpty()) "no readings available"
            else findings.joinToString("; ") { "${it.metric}: ${it.severity} (${it.summary})" }
    }

    fun assess(snapshot: VitalsSnapshot): Assessment {
        val findings = mutableListOf<Finding>()
        assessBloodPressure(snapshot.systolic, snapshot.diastolic)?.let(findings::add)
        assessGlucose(snapshot.glucoseMgDl)?.let(findings::add)
        assessBmi(snapshot.bmi)?.let(findings::add)
        return Assessment(findings)
    }

    private fun assessBloodPressure(systolic: Int?, diastolic: Int?): Finding? {
        if (systolic == null || diastolic == null) return null
        val metric = "blood pressure"
        val reading = "$systolic over $diastolic"
        return when {
            systolic >= 180 || diastolic >= 120 -> Finding(
                metric, Severity.URGENT,
                "$reading is very high and needs medical attention straight away"
            )
            systolic < 90 || diastolic < 60 -> Finding(
                metric, Severity.CAUTION,
                "$reading is on the low side"
            )
            systolic >= 140 || diastolic >= 90 -> Finding(
                metric, Severity.CAUTION,
                "$reading is higher than the usual target"
            )
            systolic >= 130 || diastolic >= 80 -> Finding(
                metric, Severity.CAUTION,
                "$reading is slightly raised"
            )
            else -> Finding(metric, Severity.NORMAL, "$reading looks fine")
        }
    }

    private fun assessGlucose(mgDl: Double?): Finding? {
        if (mgDl == null) return null
        val metric = "blood sugar"
        val reading = "${mgDl.toInt()} mg/dL"
        return when {
            mgDl < 54 -> Finding(
                metric, Severity.URGENT,
                "$reading is very low and needs attention straight away"
            )
            mgDl >= 300 -> Finding(
                metric, Severity.URGENT,
                "$reading is very high and needs attention straight away"
            )
            mgDl < 70 -> Finding(metric, Severity.CAUTION, "$reading is low")
            mgDl >= 126 -> Finding(metric, Severity.CAUTION, "$reading is high")
            mgDl >= 100 -> Finding(metric, Severity.CAUTION, "$reading is slightly raised")
            else -> Finding(metric, Severity.NORMAL, "$reading looks fine")
        }
    }

    /**
     * BMI is never urgent - it is a long-run measure, not an acute reading, and treating
     * it as an emergency would be both wrong and alarming.
     */
    private fun assessBmi(bmi: Double?): Finding? {
        if (bmi == null) return null
        val metric = "weight"
        val reading = "a BMI of ${VitalsSnapshot.formatBmi(bmi)}"
        return when {
            bmi < 18.5 -> Finding(metric, Severity.CAUTION, "$reading is on the low side")
            bmi >= 30 -> Finding(metric, Severity.CAUTION, "$reading is above the healthy range")
            bmi >= 25 -> Finding(metric, Severity.CAUTION, "$reading is a little above the healthy range")
            else -> Finding(metric, Severity.NORMAL, "$reading is in the healthy range")
        }
    }
}
