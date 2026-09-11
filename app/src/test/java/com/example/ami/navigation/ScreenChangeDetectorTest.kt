package com.example.ami.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenChangeDetectorTest {

    private val settingsScreen =
        "Settings | Wi-Fi [tap] | Bluetooth [tap] | Sound [tap] | Display [tap] | " +
            "Battery [tap] | 87% remaining | Settings list [scrollable]"

    @Test
    fun `the first screen after a reset is always significant`() {
        val detector = ScreenChangeDetector()
        assertTrue(detector.accept(settingsScreen, "com.android.settings"))
    }

    @Test
    fun `an identical screen is not a change`() {
        val detector = ScreenChangeDetector()
        detector.accept(settingsScreen, "com.android.settings")
        assertFalse(detector.accept(settingsScreen, "com.android.settings"))
    }

    /**
     * The reason this class exists. A battery percentage, a clock or an unread badge
     * ticking over used to count as a new screen and fire a fresh planner call - and
     * re-narrate a step the user was still following.
     */
    @Test
    fun `a ticking number is not a change`() {
        val detector = ScreenChangeDetector()
        detector.accept(settingsScreen, "com.android.settings")
        val oneMinuteLater = settingsScreen.replace("87% remaining", "86% remaining")
        assertFalse(detector.accept(oneMinuteLater, "com.android.settings"))
    }

    @Test
    fun `a new button is a change`() {
        val detector = ScreenChangeDetector()
        detector.accept(settingsScreen, "com.android.settings")
        val withDialog = "$settingsScreen | Turn on Wi-Fi? | Turn on [tap] | Cancel [tap]"
        assertTrue(detector.accept(withDialog, "com.android.settings"))
    }

    @Test
    fun `a toggle flipping is a change even when nothing else moves`() {
        val detector = ScreenChangeDetector()
        detector.accept("Wi-Fi [toggle:off] | Bluetooth [toggle:on]", "com.android.settings")
        assertTrue(detector.accept("Wi-Fi [toggle:on] | Bluetooth [toggle:on]", "com.android.settings"))
    }

    @Test
    fun `leaving the app is a change even if the text happens to match`() {
        val detector = ScreenChangeDetector()
        detector.accept(settingsScreen, "com.android.settings")
        assertTrue(detector.accept(settingsScreen, "com.whatsapp"))
    }

    @Test
    fun `replacing the whole screen body is a change`() {
        val detector = ScreenChangeDetector()
        detector.accept("Inbox [tap] | Sent [tap] | Drafts [tap]", "com.mail")
        assertTrue(detector.accept("Compose [tap] | To | Subject | Send [tap]", "com.mail"))
    }

    @Test
    fun `reset makes the next identical screen significant again`() {
        val detector = ScreenChangeDetector()
        detector.accept(settingsScreen, "com.android.settings")
        detector.reset()
        assertTrue(detector.accept(settingsScreen, "com.android.settings"))
    }

    @Test
    fun `labels without annotations still count toward the overall comparison`() {
        val detector = ScreenChangeDetector()
        detector.accept("Your photos | Yesterday | Last week | Open [tap]", "com.photos")
        assertTrue(detector.accept("An error occurred | Try again [tap]", "com.photos"))
    }

    @Test
    fun `two empty screens are identical`() {
        assertTrue(ScreenChangeDetector.similarity(emptySet(), emptySet()) == 1f)
    }
}
