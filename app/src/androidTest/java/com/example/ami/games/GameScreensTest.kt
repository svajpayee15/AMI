package com.example.ami.games

import android.app.Activity
import android.view.View
import android.widget.GridLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ami.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves each relaxation screen opens and runs.
 *
 * The rules of all five games are covered by fast local tests. What those cannot catch is
 * the Android half: a custom view missing the two-argument constructor the layout inflater
 * needs, a theme attribute this app's Material 2 theme doesn't provide, or an animation
 * loop that keeps running after the screen is gone. That last one is a battery drain
 * nobody would ever trace back to a bubble game, so it gets an explicit test.
 */
@RunWith(AndroidJUnit4::class)
class GameScreensTest {

    private fun <A : Activity> launching(clazz: Class<A>, block: (A) -> Unit) {
        ActivityScenario.launch(clazz).use { scenario -> scenario.onActivity(block) }
    }

    @Test
    fun hubListsEveryGame() {
        launching(GamesActivity::class.java) { activity ->
            val list = activity.findViewById<android.widget.LinearLayout>(R.id.gamesList)
            // One card per game, each of them tappable. Five since Music therapy joined
            // the set from the design.
            assertEquals(5, list.childCount)
            for (i in 0 until list.childCount) {
                assertTrue("card $i is not clickable", list.getChildAt(i).isClickable)
            }
        }
    }

    @Test
    fun breathingScreenStartsOnItsOwn() {
        // There is no start button by design: someone who opened this because they feel
        // anxious should not have to make a decision first. The exercise must therefore be
        // running by the time the screen is up.
        ActivityScenario.launch(BreathingActivity::class.java).use { scenario ->
            Thread.sleep(700)
            scenario.onActivity { activity ->
                val view = activity.findViewById<BreathingView>(R.id.breathingView)
                assertTrue("the scene is not animating", view.isRunning)
                assertTrue("the exercise never started", view.started)
                assertTrue("the breath clock is not advancing", view.elapsedMs > 0L)
            }
        }
    }

    @Test
    fun bubbleScreenFillsWithBubblesAndAnimates() {
        ActivityScenario.launch(BubbleActivity::class.java).use { scenario ->
            Thread.sleep(600)
            scenario.onActivity { activity ->
                val view = activity.findViewById<BubbleView>(R.id.bubbleView)
                assertTrue("the loop is not running", view.isRunning)
                assertTrue("the view was never measured", view.width > 0)
                assertEquals(0, view.popped)
            }
        }
    }

    @Test
    fun theBubbleLoopStopsWhenTheScreenGoesAway() {
        // An animation left ticking after onPause is a silent battery drain.
        val scenario = ActivityScenario.launch(BubbleActivity::class.java)
        Thread.sleep(400)

        var view: BubbleView? = null
        scenario.onActivity { view = it.findViewById(R.id.bubbleView) }
        assertTrue(view!!.isRunning)

        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        assertFalse("still animating after pause", view!!.isRunning)

        scenario.close()
    }

    @Test
    fun memoryScreenDealsAFullBoard() {
        launching(MemoryActivity::class.java) { activity ->
            val grid = activity.findViewById<GridLayout>(R.id.memoryGrid)
            assertEquals(MemoryBoard.DEFAULT_SYMBOLS.size * 2, grid.childCount)
            // Every card starts face down, so none of them shows a symbol yet.
            for (i in 0 until grid.childCount) {
                val symbol = grid.getChildAt(i).findViewById<android.widget.TextView>(R.id.memoryCardSymbol)
                assertEquals("", symbol.text.toString())
            }
        }
    }

    @Test
    fun tappingAMemoryCardTurnsItOver() {
        launching(MemoryActivity::class.java) { activity ->
            val grid = activity.findViewById<GridLayout>(R.id.memoryGrid)
            grid.getChildAt(0).performClick()

            val symbol = grid.getChildAt(0).findViewById<android.widget.TextView>(R.id.memoryCardSymbol)
            assertTrue("card did not reveal a symbol", symbol.text.isNotEmpty())
        }
    }

    @Test
    fun everyEndingScreenOffersAWayToGoAgain() {
        // The bug this guards: after the restyle, finishing a game left the person on a
        // dead screen with only the close X - no way to play again short of backing out
        // and re-entering. All three that can end now share one finish panel.
        listOf(
            BreathingActivity::class.java,
            BubbleActivity::class.java,
            MemoryActivity::class.java
        ).forEach { screen ->
            launching(screen) { activity ->
                assertTrue(
                    "${screen.simpleName} has no finish panel",
                    activity.findViewById<View>(R.id.gameFinishScrim) != null
                )
                assertTrue(
                    "${screen.simpleName} has no Again button",
                    activity.findViewById<View>(R.id.btnGameAgain) != null
                )
                // Hidden until the activity is actually over.
                assertEquals(
                    "${screen.simpleName} shows its finish panel on arrival",
                    View.GONE,
                    activity.findViewById<View>(R.id.gameFinishScrim).visibility
                )
            }
        }
    }

    @Test
    fun clearingEveryBubbleEndsTheSession() {
        // Popping used to refill forever, so the exercise had no end. Popping every bubble
        // must now empty the field and raise the finish panel.
        ActivityScenario.launch(BubbleActivity::class.java).use { scenario ->
            Thread.sleep(700)
            scenario.onActivity { activity ->
                val view = activity.findViewById<BubbleView>(R.id.bubbleView)
                // Far more taps than the session holds; each lands on a bubble's centre.
                repeat(ThoughtSession.SESSION_SIZE * 3) {
                    val target = view.bubbles.firstOrNull() ?: return@repeat
                    view.popAt(target.x, target.y)
                }
                assertTrue("bubbles remain after clearing the session", view.bubbles.isEmpty())
                assertEquals(
                    View.VISIBLE,
                    activity.findViewById<View>(R.id.gameFinishScrim).visibility
                )
            }
        }
    }

    @Test
    fun musicScreenInflatesWithItsMoodChips() {
        launching(MusicActivity::class.java) { activity ->
            val chips = activity.findViewById<android.widget.LinearLayout>(R.id.musicChipRows)
            // Two rows of two; four across clips on a narrow screen at this text size.
            assertEquals(2, chips.childCount)
            // With no audio loaded the card says so rather than faking a track.
            assertEquals(
                View.VISIBLE,
                activity.findViewById<View>(R.id.musicEmptyNote).visibility
            )
        }
    }

    @Test
    fun pondScreenInflatesAndAnimates() {
        ActivityScenario.launch(PondActivity::class.java).use { scenario ->
            Thread.sleep(600)
            scenario.onActivity { activity ->
                val view = activity.findViewById<View>(R.id.pondView) as PondView
                assertTrue(view.isRunning)
                assertTrue(view.width > 0)
            }
        }
    }
}
