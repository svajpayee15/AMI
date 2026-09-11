package com.example.ami.caretaker

import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.example.ami.R

/**
 * Proves the caretaker screen actually opens.
 *
 * Everything under it - the extractor, the digest, the agent's rule gate - is covered by
 * fast local tests. What those cannot catch is a layout that fails to inflate, a view id
 * that does not exist, or a theme attribute the app's Material 2 theme doesn't provide.
 * That is what this is for, and it is why it asserts on the rendered views rather than on
 * anything the agent decided.
 *
 * Runs against an empty database on a fresh install, so the empty state is the expected
 * outcome; a populated report is the local tests' job.
 */
@RunWith(AndroidJUnit4::class)
class CaretakerActivityTest {

    @Test
    fun opensAndSettlesOnARenderedState() {
        ActivityScenario.launch(CaretakerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // Inflation succeeded and every id the activity reaches for resolves.
                assertTrue(activity.findViewById<View>(R.id.caretakerNoticedCard) != null)
                assertTrue(activity.findViewById<View>(R.id.caretakerFoodCard) != null)
                assertTrue(activity.findViewById<View>(R.id.caretakerExerciseCard) != null)
                assertTrue(activity.findViewById<View>(R.id.btnCaretakerSpeak) != null)
                assertTrue(activity.findViewById<View>(R.id.btnCaretakerRefresh) != null)
            }

            // The load is a coroutine; give it a moment to replace the placeholder.
            Thread.sleep(1_500)

            scenario.onActivity { activity ->
                val noted = activity.findViewById<TextView>(R.id.caretakerNoted).text.toString()
                val thinking = activity.getString(R.string.caretaker_thinking)
                val empty = activity.findViewById<View>(R.id.caretakerEmptyCard)

                // Either it finished and said something, or it showed the empty card.
                // What it must not do is sit on "Looking back over your week…" forever.
                assertTrue(
                    "screen never left its loading state",
                    empty.visibility == View.VISIBLE || noted != thinking
                )
            }
        }
    }

    @Test
    fun refreshDoesNotCrash() {
        ActivityScenario.launch(CaretakerActivity::class.java).use { scenario ->
            scenario.onActivity { it.findViewById<View>(R.id.btnCaretakerRefresh).performClick() }
            Thread.sleep(1_000)
            scenario.onActivity { activity ->
                assertEquals(
                    "activity died during refresh",
                    false,
                    activity.isFinishing || activity.isDestroyed
                )
            }
        }
    }
}
