package com.example.ami.navigation

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.ami.HistoryActivity
import com.example.ami.MainActivity
import com.example.ami.R
import com.example.ami.health.VitalsActivity
import com.example.ami.profile.ProfileSettingsActivity
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * The app's top-level destinations.
 *
 * Everything else - care settings, edit profile, the info pages, the wellbeing story -
 * is reached *from* one of these and leaves by going back, so the person is never more
 * than two steps from anywhere.
 */
enum class AmiTab(@IdRes val itemId: Int, val activity: Class<out Activity>) {
    HOME(R.id.nav_home, MainActivity::class.java),
    HEALTH(R.id.nav_health, VitalsActivity::class.java),
    REPORT(R.id.nav_report, HistoryActivity::class.java),
    SETTINGS(R.id.nav_settings, ProfileSettingsActivity::class.java);

    companion object {
        fun forItemId(@IdRes itemId: Int): AmiTab? = entries.firstOrNull { it.itemId == itemId }
    }
}

/** What tapping a tab should do, decided separately from doing it so it can be tested. */
sealed interface NavAction {

    /** The tap landed on the screen already showing. Nothing to do. */
    data object Stay : NavAction

    /**
     * Go to [tab]. [finishCurrent] closes the screen being left behind, which is what
     * keeps switching tabs from stacking up screens - see [AmiNavBar.decide].
     */
    data class Open(val tab: AmiTab, val finishCurrent: Boolean) : NavAction
}

/**
 * Attaches the bottom navigation bar to a top-level screen.
 *
 * These are Activities rather than Fragments, so "switching tabs" has to be made to
 * behave like one. The rule is the one Material asks for: the back stack is at most
 * Home plus the screen you are on, and back from any tab returns to Home rather than
 * retracing the tabs you visited.
 */
object AmiNavBar {

    /**
     * The stack rule, in one place.
     *
     * Home is the root and is never closed, so it is still underneath to come back to.
     * Every other tab closes itself on the way out, which is what stops Health ->
     * Report -> Settings from turning into three screens deep of back presses.
     *
     * A tab can also be tapped from a screen that belongs to it without *being* it -
     * the wellbeing story under Health - and that has to navigate rather than sit
     * still, hence [currentIsTabRoot].
     */
    fun decide(current: AmiTab, currentIsTabRoot: Boolean, tapped: AmiTab): NavAction =
        if (tapped == current && currentIsTabRoot) {
            NavAction.Stay
        } else {
            NavAction.Open(tapped, finishCurrent = current != AmiTab.HOME)
        }

    /**
     * Replaces [AppCompatActivity.setContentView]: inflates [contentLayout] into the
     * navigation shell and wires the bar up for [tab].
     *
     * Pass `isTabRoot = false` from a screen that sits *under* a tab, so that tapping
     * that tab walks back up to its root screen instead of doing nothing.
     */
    fun setContentView(
        activity: AppCompatActivity,
        @LayoutRes contentLayout: Int,
        tab: AmiTab,
        isTabRoot: Boolean = true
    ) {
        activity.setContentView(R.layout.layout_ami_nav_shell)
        activity.layoutInflater.inflate(
            contentLayout,
            activity.findViewById<FrameLayout>(R.id.amiNavContent),
            true
        )

        val bar = activity.findViewById<BottomNavigationView>(R.id.amiBottomNav)

        // Checked directly on the menu item rather than through selectedItemId, which
        // would fire the listener and navigate to the screen we are already on.
        bar.menu.findItem(tab.itemId).isChecked = true

        bar.setOnItemSelectedListener { item ->
            val tapped = AmiTab.forItemId(item.itemId) ?: return@setOnItemSelectedListener false
            when (val action = decide(tab, isTabRoot, tapped)) {
                NavAction.Stay -> true
                is NavAction.Open -> {
                    open(activity, action)
                    // Deliberately false: this screen keeps its own tab highlighted, so
                    // if the user comes back to it the bar still says where they are.
                    false
                }
            }
        }

        applyWindowInsets(activity)
    }

    /** Opens [tab] from anywhere - the home screen cards use this too. */
    fun open(activity: Activity, tab: AmiTab) {
        open(activity, NavAction.Open(tab, finishCurrent = false))
    }

    private fun open(activity: Activity, action: NavAction.Open) {
        activity.startActivity(
            Intent(activity, action.tab.activity)
                // CLEAR_TOP with SINGLE_TOP hands the intent to the existing screen if
                // it is already on the stack instead of building a second copy of it.
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        if (action.finishCurrent) activity.finish()
        suppressTransition(activity)
    }

    /** Tabs should swap, not slide in like a screen being opened on top of another. */
    @Suppress("DEPRECATION")
    private fun suppressTransition(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            activity.overridePendingTransition(0, 0)
        }
    }

    /**
     * From Android 15 the system bars are always drawn over the app, so without this
     * the tabs sit underneath the gesture pill and the screen titles under the clock.
     */
    private fun applyWindowInsets(activity: Activity) {
        val root = activity.findViewById<ViewGroup>(R.id.amiNavRoot)
        val content = activity.findViewById<ViewGroup>(R.id.amiNavContent)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            root.updatePadding(left = insets.left, right = insets.right)
            content.updatePadding(top = insets.top)
            // The bottom inset is deliberately not handled here: BottomNavigationView
            // pads itself for the gesture bar, and adding it again here pushed the bar
            // up by a second inset's worth of untappable surface.
            windowInsets
        }
    }
}
