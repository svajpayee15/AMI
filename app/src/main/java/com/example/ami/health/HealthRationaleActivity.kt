package com.example.ami.health

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.ami.R

/**
 * Shown by Health Connect when the user asks why AMI wants their health data.
 *
 * Health Connect requires this to exist and to be reachable from its own permission UI;
 * the text must match the privacy policy published on the Play listing.
 */
class HealthRationaleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_health_rationale)
    }
}
