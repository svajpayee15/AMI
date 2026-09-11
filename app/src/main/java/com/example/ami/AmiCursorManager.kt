package com.example.ami

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator

// These views are added straight to the WindowManager as overlays, so there is no parent
// to inflate against - the LayoutParams below are the real ones. A null root is correct
// here, not the usual mistake InflateParams is looking for.
@SuppressLint("InflateParams")
class AmiCursorManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var cursorView: View? = null
    private var triggerView: View? = null

    private val cursorParams = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        gravity = Gravity.TOP or Gravity.START
    }

    private val triggerParams = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
    }

    fun showTrigger(onTriggerClicked: () -> Unit, onStopClicked: () -> Unit) {
        if (triggerView == null) {
            triggerView = LayoutInflater.from(context).inflate(R.layout.layout_ami_trigger, null)
            triggerView?.findViewById<View>(R.id.ami_trigger_icon)?.setOnClickListener {
                onTriggerClicked()
            }
            triggerView?.findViewById<View>(R.id.ami_stop_icon)?.setOnClickListener {
                onStopClicked()
            }
            windowManager.addView(triggerView, triggerParams)
            Log.d("AMI_CURSOR", "Trigger bubble shown")
        }
    }

    fun setStopButtonVisibility(visible: Boolean) {
        triggerView?.findViewById<View>(R.id.ami_stop_icon)?.visibility = if (visible) View.VISIBLE else View.GONE
        try {
            windowManager.updateViewLayout(triggerView, triggerParams)
        } catch (e: Exception) {}
    }

    fun showCursor(text: String? = null) {
        if (cursorView == null) {
            try {
                cursorView = LayoutInflater.from(context).inflate(R.layout.layout_ami_cursor, null)
                windowManager.addView(cursorView, cursorParams)
                Log.d("AMI_CURSOR", "Cursor view added")
            } catch (e: Exception) {
                Log.e("AMI_CURSOR", "Failed to add cursor view", e)
            }
        }
        updateCursorText(text)
    }

    fun updateCursorText(text: String?) {
        cursorView?.let { view ->
            val textView = view.findViewById<android.widget.TextView>(R.id.ami_speech_bubble)
            if (text != null) {
                textView.text = text
                textView.visibility = View.VISIBLE
            } else {
                textView.visibility = View.GONE
            }
            try {
                windowManager.updateViewLayout(view, cursorParams)
            } catch (e: Exception) {}
        }
    }

    fun moveCursorTo(x: Int, y: Int) {
        cursorView?.let { view ->
            // Use measured dimensions for initial positioning
            view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val w = view.measuredWidth
            val h = view.measuredHeight

            cursorParams.x = x - (w / 2)
            cursorParams.y = y - (h / 2)
            
            try {
                windowManager.updateViewLayout(view, cursorParams)
            } catch (e: Exception) {
                Log.e("AMI_CURSOR", "Error updating cursor position", e)
            }
        }
    }

    fun animateSwipe(direction: String) {
        cursorView?.let { view ->
            val startX = cursorParams.x
            val startY = cursorParams.y
            val distance = 300 // pixels to swipe

            val (endX, endY) = when (direction.uppercase()) {
                "UP" -> startX to (startY - distance)
                "DOWN" -> startX to (startY + distance)
                "LEFT" -> (startX - distance) to startY
                "RIGHT" -> (startX + distance) to startY
                else -> startX to startY
            }

            val animator = ValueAnimator.ofFloat(0f, 1f)
            animator.duration = 800
            animator.interpolator = AccelerateDecelerateInterpolator()
            animator.addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                cursorParams.x = (startX + (endX - startX) * fraction).toInt()
                cursorParams.y = (startY + (endY - startY) * fraction).toInt()
                try {
                    windowManager.updateViewLayout(view, cursorParams)
                } catch (e: Exception) {}
            }
            animator.start()
        }
    }

    fun hideCursor() {
        cursorView?.let {
            windowManager.removeView(it)
            cursorView = null
        }
    }
}
