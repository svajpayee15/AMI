package com.example.ami.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The back-stack rules behind the bottom bar.
 *
 * These are worth pinning down because getting them wrong is invisible until someone
 * has pressed back eleven times to leave the app - the failure mode of activity-based
 * tab bars, and the reason [AmiNavBar.decide] exists apart from the view code at all.
 */
class AmiNavBarTest {

    @Test
    fun `tapping the tab you are already on does nothing`() {
        AmiTab.entries.forEach { tab ->
            assertEquals(
                "tapping $tab from $tab should stay put",
                NavAction.Stay,
                AmiNavBar.decide(current = tab, currentIsTabRoot = true, tapped = tab)
            )
        }
    }

    @Test
    fun `home is never closed, so there is always something to go back to`() {
        listOf(AmiTab.HEALTH, AmiTab.REPORT, AmiTab.SETTINGS).forEach { target ->
            val action = AmiNavBar.decide(AmiTab.HOME, currentIsTabRoot = true, tapped = target)

            assertEquals(NavAction.Open(target, finishCurrent = false), action)
        }
    }

    @Test
    fun `switching between two non-home tabs closes the one being left`() {
        val action = AmiNavBar.decide(AmiTab.HEALTH, currentIsTabRoot = true, tapped = AmiTab.REPORT)

        assertEquals(NavAction.Open(AmiTab.REPORT, finishCurrent = true), action)
    }

    @Test
    fun `going home from another tab closes it rather than stacking home twice`() {
        val action = AmiNavBar.decide(AmiTab.SETTINGS, currentIsTabRoot = true, tapped = AmiTab.HOME)

        assertEquals(NavAction.Open(AmiTab.HOME, finishCurrent = true), action)
    }

    /**
     * The wellbeing story sits under Health. Tapping Health from there has to navigate,
     * or the tab the user is looking at would be the one tab that does not respond.
     */
    @Test
    fun `tapping the owning tab from a screen underneath it goes up to that tab`() {
        val action = AmiNavBar.decide(AmiTab.HEALTH, currentIsTabRoot = false, tapped = AmiTab.HEALTH)

        assertEquals(NavAction.Open(AmiTab.HEALTH, finishCurrent = true), action)
    }

    @Test
    fun `a sub-screen of home still leaves home standing`() {
        val action = AmiNavBar.decide(AmiTab.HOME, currentIsTabRoot = false, tapped = AmiTab.HOME)

        assertEquals(NavAction.Open(AmiTab.HOME, finishCurrent = false), action)
    }

    /**
     * Whatever the route, the stack never grows past Home plus where you are: every
     * hop either leaves Home alone or closes a non-Home screen on the way out.
     */
    @Test
    fun `no sequence of tab taps can stack more than two screens`() {
        var depth = 1 // Home, launched.
        var current = AmiTab.HOME

        val everyPairInTurn = AmiTab.entries.flatMap { from -> AmiTab.entries.map { from to it } }
        repeat(20) {
            everyPairInTurn.forEach { (_, tapped) ->
                when (val action = AmiNavBar.decide(current, currentIsTabRoot = true, tapped)) {
                    NavAction.Stay -> Unit
                    is NavAction.Open -> {
                        if (action.finishCurrent) depth--
                        // Home is only ever navigated to from a screen that closes
                        // itself, so CLEAR_TOP always finds it already on the stack
                        // and reuses it instead of adding another.
                        if (action.tab != AmiTab.HOME) depth++
                        current = action.tab
                    }
                }
                assertTrue("stack grew to $depth screens", depth in 1..2)
            }
        }
    }

    @Test
    fun `every menu item maps back to exactly one tab`() {
        AmiTab.entries.forEach { tab ->
            assertEquals(tab, AmiTab.forItemId(tab.itemId))
        }
        assertEquals(
            "two tabs share a menu item id",
            AmiTab.entries.size,
            AmiTab.entries.map { it.itemId }.toSet().size
        )
    }

    @Test
    fun `an unknown menu item is rejected rather than guessed at`() {
        assertNull(AmiTab.forItemId(-1))
    }

    @Test
    fun `every tab points at a distinct screen`() {
        assertEquals(
            AmiTab.entries.size,
            AmiTab.entries.map { it.activity }.toSet().size
        )
    }
}
