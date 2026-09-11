package com.example.ami.health

/**
 * Everything AMI knows about the person's numbers right now.
 *
 * Every field except [steps] is nullable on purpose: a phone measures step count on its
 * own, but weight, blood pressure and blood glucose only exist if a paired device or the
 * user put them there. The UI and the diet advice both have to cope with "we don't know".
 */
data class VitalsSnapshot(
    val steps: Long = 0,
    val weightKg: Double? = null,
    val heightCm: Double? = null,
    val systolic: Int? = null,
    val diastolic: Int? = null,
    val glucoseMgDl: Double? = null
) {
    /** Null unless both weight and a plausible height are known. */
    val bmi: Double?
        get() {
            val kg = weightKg ?: return null
            val cm = heightCm ?: return null
            if (cm < 50) return null
            val metres = cm / 100.0
            return kg / (metres * metres)
        }

    val hasAnyVital: Boolean
        get() = weightKg != null || systolic != null || glucoseMgDl != null

    companion object {
        /** Explicit locale: this is shown to the user, so it follows their decimal separator. */
        fun formatBmi(value: Double): String =
            String.format(java.util.Locale.getDefault(), "%.1f", value)
    }
}
