package com.example.ami.navigation

import java.util.Locale

/**
 * Decides whether the screen changed enough to be worth another planner call.
 *
 * The previous rule was "the flattened screen text differs from last time", which is
 * true of any screen containing a clock, an unread badge, a progress percentage or a
 * live chat - so a single goal could fire an LLM call every 1.5 seconds while the user
 * stood still. Every one of those costs money and, more importantly, re-narrates a step
 * the user is still in the middle of following.
 *
 * What actually matters to a planner is which things can be *acted on*: the set of
 * tappable labels and the state of any toggles. Body text drifting is noise; a new
 * button, a vanished button or a switch flipping is a new situation.
 *
 * Pure and stateful only in [last], with no Android dependency, so the thresholds can
 * be tested against real screen dumps.
 */
class ScreenChangeDetector(
    private val actionableThreshold: Float = ACTIONABLE_THRESHOLD,
    private val overallThreshold: Float = OVERALL_THRESHOLD
) {

    private var last: Snapshot? = null

    /**
     * Records [screenText] and returns whether it is a meaningful change from the
     * previous screen. The first call after [reset] is always significant.
     */
    fun accept(screenText: String, packageName: String? = null): Boolean {
        val next = Snapshot.parse(screenText, packageName)
        val previous = last
        last = next
        return previous == null || isSignificant(previous, next)
    }

    /** Call when a new goal starts, so the next screen is always re-planned. */
    fun reset() {
        last = null
    }

    private fun isSignificant(previous: Snapshot, next: Snapshot): Boolean = when {
        previous.packageName != next.packageName -> true
        previous.toggles != next.toggles -> true
        similarity(previous.actionable, next.actionable) < actionableThreshold -> true
        similarity(previous.all, next.all) < overallThreshold -> true
        else -> false
    }

    /**
     * One parsed screen: the labels, which of them can be tapped, and every toggle's
     * state. Built from the same annotated `label [tap] | label [toggle:on]` dump the
     * accessibility service sends to the planner.
     */
    data class Snapshot(
        val packageName: String?,
        val all: Set<String>,
        val actionable: Set<String>,
        val toggles: Map<String, Boolean>
    ) {
        companion object {
            fun parse(screenText: String, packageName: String?): Snapshot {
                val all = mutableSetOf<String>()
                val actionable = mutableSetOf<String>()
                val toggles = mutableMapOf<String, Boolean>()

                screenText.split("|").forEach { rawEntry ->
                    val entry = rawEntry.trim()
                    if (entry.isEmpty()) return@forEach

                    val annotationStart = entry.lastIndexOf('[')
                    val hasAnnotation = annotationStart >= 0 && entry.endsWith(']')
                    val label = if (hasAnnotation) entry.substring(0, annotationStart) else entry
                    val annotations = if (hasAnnotation) {
                        entry.substring(annotationStart + 1, entry.length - 1)
                    } else {
                        ""
                    }

                    val key = normalise(label)
                    if (key.isEmpty()) return@forEach
                    all.add(key)

                    if (annotations.contains("tap")) actionable.add(key)
                    when {
                        annotations.contains("toggle:on") -> {
                            toggles[key] = true
                            actionable.add(key)
                        }
                        annotations.contains("toggle:off") -> {
                            toggles[key] = false
                            actionable.add(key)
                        }
                    }
                }
                return Snapshot(packageName, all, actionable, toggles)
            }

            /**
             * Digits collapse to "#" so a clock, a timer, an unread count and a
             * percentage all read as the same label from one second to the next -
             * which is the single largest source of spurious screen changes.
             */
            private fun normalise(label: String): String = label
                .lowercase(Locale.US)
                .replace(Regex("""\d+"""), "#")
                .replace(Regex("""[^a-z#]+"""), " ")
                .trim()
        }
    }

    companion object {
        /** Two empty screens are identical, not maximally different. */
        fun similarity(a: Set<String>, b: Set<String>): Float {
            if (a.isEmpty() && b.isEmpty()) return 1f
            val intersection = a.count { it in b }
            val union = a.size + b.size - intersection
            return if (union == 0) 1f else intersection.toFloat() / union
        }

        /** One button appearing or disappearing among six is already a new situation. */
        private const val ACTIONABLE_THRESHOLD = 0.85f

        /** Body text can drift by half before it means the screen actually moved. */
        private const val OVERALL_THRESHOLD = 0.5f
    }
}
