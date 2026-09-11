package com.example.ami.games

import android.app.Activity
import android.view.View
import android.widget.TextView
import com.example.ami.R
import com.google.android.material.button.MaterialButton

/**
 * Wires the shared finish panel.
 *
 * One helper rather than three copies, so all three activities that can end do it the same
 * way: the same card, the same two choices, in the same place. That consistency is the
 * point - someone who has learned what "Again" does on one screen should not have to
 * relearn it on the next.
 */
object GameFinish {

    /**
     * @param onAgain restart the activity's own state. The panel hides itself first, so
     *   callers only have to deal a new board or reset a session.
     */
    fun bind(activity: Activity, onAgain: () -> Unit) {
        activity.findViewById<MaterialButton>(R.id.btnGameAgain).setOnClickListener {
            hide(activity)
            onAgain()
        }
        activity.findViewById<MaterialButton>(R.id.btnGameDone).setOnClickListener {
            activity.finish()
        }
    }

    fun show(activity: Activity, titleRes: Int, bodyRes: Int) {
        activity.findViewById<TextView>(R.id.gameFinishTitle).setText(titleRes)
        activity.findViewById<TextView>(R.id.gameFinishBody).setText(bodyRes)
        activity.findViewById<View>(R.id.gameFinishScrim).visibility = View.VISIBLE
    }

    fun hide(activity: Activity) {
        activity.findViewById<View>(R.id.gameFinishScrim).visibility = View.GONE
    }

    fun isShowing(activity: Activity): Boolean =
        activity.findViewById<View>(R.id.gameFinishScrim).visibility == View.VISIBLE
}
