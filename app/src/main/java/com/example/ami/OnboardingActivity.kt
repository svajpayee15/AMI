package com.example.ami

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.ami.data.AmiPreferences
import kotlinx.coroutines.launch

class OnboardingActivity : AppCompatActivity() {

    private lateinit var preferences: AmiPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = AmiPreferences(this)

        lifecycleScope.launch {
            if (preferences.isOnboardingCompleteOnce()) {
                goToMain()
                return@launch
            }
            setContentView(R.layout.activity_onboarding)
            findViewById<Button>(R.id.btnOnboardingContinue).setOnClickListener {
                lifecycleScope.launch {
                    preferences.setOnboardingComplete(true)
                    goToMain()
                }
            }
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
