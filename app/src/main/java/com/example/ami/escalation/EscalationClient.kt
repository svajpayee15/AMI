package com.example.ami.escalation

import android.util.Log
import com.example.ami.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.Instant

/**
 * Calls AMI's own backend (see /server) to email caregivers and place reminder calls.
 * Real SMTP and Twilio credentials live server-side only - the app holds a shared
 * secret and the backend's base URL. Every failure is swallowed and logged: an
 * unreachable backend must never crash the wellness-check flow that calls this.
 */
class EscalationClient {

    /** What became of a reminder call, once the backend's webhook has heard back. */
    enum class CallOutcome {
        /** The call was picked up. */
        ANSWERED,

        /** It rang out, was busy, or failed. */
        NOT_ANSWERED,

        /** Never placed, or no verdict arrived in time. Treated as not answered. */
        UNKNOWN
    }

    private val api: EscalationApi? by lazy {
        val baseUrl = BuildConfig.AMI_BACKEND_BASE_URL
        if (baseUrl.isBlank()) {
            null
        } else {
            try {
                Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(EscalationApi::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to build escalation client for '$baseUrl'", e)
                null
            }
        }
    }

    suspend fun escalate(
        caregiverEmails: List<String>,
        medicineName: String,
        message: String,
        urgent: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        if (caregiverEmails.isEmpty()) {
            Log.w(TAG, "No caregivers configured, skipping escalation for $medicineName")
            return@withContext false
        }
        val client = api ?: run {
            Log.w(TAG, "Backend URL not configured, skipping escalation for $medicineName")
            return@withContext false
        }
        try {
            client.escalate(
                secret = BuildConfig.AMI_ESCALATION_SHARED_SECRET,
                request = EscalationRequest(
                    caregiverEmails = caregiverEmails,
                    medicineName = medicineName,
                    message = message,
                    timestampIso = Instant.now().toString(),
                    urgent = urgent
                )
            ).isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "Escalation call failed for $medicineName", e)
            false
        }
    }

    suspend fun sendSummary(
        caregiverEmails: List<String>,
        subject: String,
        body: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (caregiverEmails.isEmpty()) return@withContext false
        val client = api ?: return@withContext false
        try {
            client.summary(
                secret = BuildConfig.AMI_ESCALATION_SHARED_SECRET,
                request = SummaryRequest(caregiverEmails, subject, body)
            ).isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "Weekly summary send failed", e)
            false
        }
    }

    /**
     * Asks the backend to place a real phone call.
     *
     * Returns Twilio's call SID if the call was accepted for dialling, or null. A SID
     * means queued, not answered - pass it to [awaitCallOutcome] to find that out.
     */
    suspend fun requestPhoneCall(
        phoneNumber: String,
        medicineName: String,
        message: String
    ): String? = withContext(Dispatchers.IO) {
        if (phoneNumber.isBlank()) {
            Log.w(TAG, "No phone number configured, skipping call for $medicineName")
            return@withContext null
        }
        val client = api ?: run {
            Log.w(TAG, "Backend URL not configured, skipping call for $medicineName")
            return@withContext null
        }
        try {
            val response = client.call(
                secret = BuildConfig.AMI_ESCALATION_SHARED_SECRET,
                request = CallRequest(phoneNumber, medicineName, message)
            )
            if (response.code() == 503) {
                Log.i(TAG, "Backend has no Twilio configuration; calling disabled")
                return@withContext null
            }
            response.body()?.sid?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "Call request failed for $medicineName", e)
            null
        }
    }

    /**
     * Polls the backend until Twilio's status webhook says the call finished.
     *
     * Without this the app only ever knew a call had been *queued*, so the caregiver
     * email could not say whether anyone actually picked up - the one fact that
     * decides whether a family needs to act. Polling rather than a push because the
     * phone has no address the backend can reach.
     *
     * Returns UNKNOWN on timeout, which callers must treat as "not answered": assuming
     * a silent call was answered is exactly the failure that hides a missed dose.
     */
    suspend fun awaitCallOutcome(
        sid: String,
        timeoutMillis: Long = CALL_STATUS_TIMEOUT_MS
    ): CallOutcome = withContext(Dispatchers.IO) {
        val client = api ?: return@withContext CallOutcome.UNKNOWN
        val deadline = System.currentTimeMillis() + timeoutMillis

        while (System.currentTimeMillis() < deadline) {
            try {
                val status = client.callStatus(BuildConfig.AMI_ESCALATION_SHARED_SECRET, sid).body()
                if (status != null && status.finished) {
                    return@withContext if (status.answered) CallOutcome.ANSWERED else CallOutcome.NOT_ANSWERED
                }
            } catch (e: Exception) {
                Log.w(TAG, "Call status poll failed for $sid", e)
            }
            delay(CALL_STATUS_POLL_MS)
        }
        Log.i(TAG, "No call outcome for $sid within ${timeoutMillis}ms")
        CallOutcome.UNKNOWN
    }

    companion object {
        private const val TAG = "AMI_ESCALATION"

        /** A ring-out plus voicemail detection comfortably fits inside this. */
        private const val CALL_STATUS_TIMEOUT_MS = 90_000L
        private const val CALL_STATUS_POLL_MS = 5_000L
    }
}
