package com.example.ami.call

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.ami.R
import com.example.ami.reminders.AmiWellnessService
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/**
 * The incoming-call screen for a medicine check-in.
 *
 * Purely the UI: answering and declining are forwarded to [AmiWellnessService], which
 * owns the conversation and the escalation ladder, so navigating away from this screen
 * mid-call doesn't abandon either.
 */
class AmiCallActivity : AppCompatActivity() {

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        setContentView(R.layout.activity_call)

        val medicineName = intent.getStringExtra(EXTRA_MEDICINE_NAME).orEmpty()
        findViewById<TextView>(R.id.callReason).text =
            getString(R.string.call_reason, medicineName)

        findViewById<MaterialButton>(R.id.btnAnswer).setOnClickListener { onAnswer() }
        findViewById<MaterialButton>(R.id.btnDecline).setOnClickListener { onDecline() }
        findViewById<MaterialButton>(R.id.btnHangUp).setOnClickListener { onDecline() }

        startRinging()
        observeCall()
    }

    /**
     * A check-in is useless if it only appears once the phone is already unlocked, so
     * the screen is turned on and shown over the keyguard.
     */
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
                ?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    private fun startRinging() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                play()
            }
        } catch (e: Exception) {
            // A silent call screen is still usable; never let ringing crash the check-in.
        }

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        try {
            val pattern = longArrayOf(0, 800, 1000)
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } catch (e: Exception) {
            // Vibration is optional.
        }
    }

    private fun stopRinging() {
        try {
            ringtone?.stop()
        } catch (e: Exception) {
            // ignore
        }
        ringtone = null
        vibrator?.cancel()
        vibrator = null
    }

    private fun onAnswer() {
        stopRinging()
        AmiWellnessService.answer(this)
        showInCallUi()
    }

    private fun onDecline() {
        stopRinging()
        AmiWellnessService.decline(this)
        finish()
    }

    private fun showInCallUi() {
        findViewById<TextView>(R.id.callStatus).setText(R.string.call_connected)
        findViewById<View>(R.id.ringingControls).visibility = View.GONE
        findViewById<View>(R.id.btnHangUp).visibility = View.VISIBLE
        findViewById<View>(R.id.callCaption).visibility = View.VISIBLE
    }

    private fun observeCall() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AmiWellnessService.callCaption.collect { caption ->
                        findViewById<TextView>(R.id.callCaption).text = caption
                    }
                }
                launch {
                    AmiWellnessService.callActive.collect { active ->
                        if (!active) finish()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRinging()
    }

    companion object {
        private const val EXTRA_DOSE_ID = "extra_dose_id"
        private const val EXTRA_MEDICINE_NAME = "extra_medicine_name"

        fun intent(context: Context, doseId: Long, medicineName: String): Intent =
            Intent(context, AmiCallActivity::class.java).apply {
                putExtra(EXTRA_DOSE_ID, doseId)
                putExtra(EXTRA_MEDICINE_NAME, medicineName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
    }
}
