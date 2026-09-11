package com.example.ami.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.ami.AmiPlannerManager
import com.example.ami.R
import com.example.ami.call.AmiCallActivity
import com.example.ami.call.NurseConversation
import com.example.ami.care.EscalationPlan
import com.example.ami.caretaker.SymptomExtractor
import com.example.ami.data.AmiPreferences
import com.example.ami.data.CheckInRecord
import com.example.ami.data.MedicineRepository
import com.example.ami.data.ScheduledDose
import com.example.ami.escalation.EscalationClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Drives a medicine check-in as an incoming call.
 *
 * The phone rings with a full-screen incoming-call screen; answering hands over to
 * [NurseConversation] for an actual back-and-forth, and the outcome is written to the
 * check-in history. If nobody picks up, the escalation ladder runs.
 *
 * All of this lives in the service rather than the activity so that a user wandering
 * away from the screen mid-call doesn't kill the conversation or skip the escalation.
 */
class AmiWellnessService : Service(), TextToSpeech.OnInitListener {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tts: TextToSpeech? = null

    /**
     * Completed from [onInit]. TextToSpeech initialises asynchronously, so anything
     * that speaks must await this rather than test a boolean that is almost always
     * still false when the first utterance is queued.
     */
    private val ttsReady = CompletableDeferred<Boolean>()

    private var speechRecognizer: SpeechRecognizer? = null

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            ttsReady.complete(true)
        } else {
            Log.w(TAG, "TextToSpeech failed to initialise (status=$status)")
            ttsReady.complete(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ANSWER -> {
                answered.takeIf { !it.isCompleted }?.complete(true)
                return START_NOT_STICKY
            }
            ACTION_DECLINE -> {
                answered.takeIf { !it.isCompleted }?.complete(false)
                return START_NOT_STICKY
            }
        }

        val doseId = intent?.getLongExtra(MedicineAlarmScheduler.EXTRA_DOSE_ID, -1L) ?: -1L
        if (doseId == -1L) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        serviceScope.launch {
            try {
                runCheckIn(doseId)
            } catch (e: Exception) {
                Log.e(TAG, "Check-in failed", e)
            } finally {
                _callActive.value = false
                _callCaption.value = ""
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runCheckIn(doseId: Long) {
        val repository = MedicineRepository.getInstance(this)
        val scheduled = repository.getScheduledDose(doseId) ?: return
        val medicine = scheduled.medicine

        // A dose already confirmed today - by an earlier check-in, or by the user
        // marking it in the app - must not ring again. Being asked twice about the
        // same tablet is how someone ends up taking it twice.
        if (scheduled.dose.isTakenToday) {
            Log.i(TAG, "Dose $doseId already confirmed today, skipping check-in")
            return
        }

        answered = CompletableDeferred()
        _callActive.value = true
        _callCaption.value = ""
        startForegroundRinging(scheduled)

        val pickedUp = withTimeoutOrNull(RING_TIMEOUT_MS) { answered.await() }

        when (pickedUp) {
            null -> {
                // The ring is over; escalation then waits up to 90s on the call
                // outcome, and leaving an ongoing "AMI is calling" notification up
                // that whole time would have the phone claiming to ring at nobody.
                showFollowUpNotification(scheduled)
                record(repository, scheduled, CheckInRecord.OUTCOME_NO_ANSWER, null)
                escalate(repository, scheduled, CheckInRecord.OUTCOME_NO_ANSWER, null)
                return
            }
            false -> {
                showFollowUpNotification(scheduled)
                record(repository, scheduled, CheckInRecord.OUTCOME_DECLINED, null)
                escalate(repository, scheduled, CheckInRecord.OUTCOME_DECLINED, null)
                return
            }
            else -> Unit // answered - fall through to the conversation
        }

        val conversation = NurseConversation(
            planner = AmiPlannerManager(),
            speak = { text ->
                _callCaption.value = text
                speakAndWait(text)
            },
            listen = { listenForResponse() }
        )

        val result = withTimeoutOrNull(CONVERSATION_TIMEOUT_MS) {
            conversation.run(medicineName = medicine.name, dosage = medicine.dosage)
        } ?: NurseConversation.Result(CheckInRecord.OUTCOME_NO_ANSWER)

        val checkInId = record(repository, scheduled, result.outcome, result.wellbeingNote)
        repository.recordSymptoms(checkInId, result.symptoms)

        if (result.outcome == CheckInRecord.OUTCOME_TAKEN) {
            repository.markDoseTakenToday(scheduled.dose.id)
        }

        // A confirmed dose normally ends the call quietly. A red flag overrides that:
        // someone can take their tablet and still have told AMI they had chest pain.
        if (result.outcome != CheckInRecord.OUTCOME_TAKEN || result.needsEscalation) {
            escalate(repository, scheduled, result.outcome, result.wellbeingNote, result.symptoms)
        }
    }

    private suspend fun record(
        repository: MedicineRepository,
        scheduled: ScheduledDose,
        outcome: String,
        note: String?
    ): Long =
        repository.recordCheckIn(
            medicineId = scheduled.medicine.id,
            doseId = scheduled.dose.id,
            medicineName = scheduled.medicine.name,
            scheduledTime = scheduled.dose.time,
            outcome = outcome,
            wellbeingNote = note
        )

    /**
     * Escalation ladder: the in-app call has already rung out by this point, so the
     * remaining rungs are a real phone call and then the caregiver chain.
     *
     * The phone call is only worth placing when nobody actually spoke to AMI. If they
     * answered and said they had not taken the dose, ringing them on the telephone to
     * ask the same question again would just be nagging - that case goes straight to
     * the caregivers.
     *
     * The call outcome is now waited for rather than assumed. A call that was picked up
     * is a materially different situation from one that rang out, and the email says
     * which. The email still goes either way: a reminder someone heard is not the same
     * as a dose they took.
     *
     * How far along the chain this reaches is [EscalationPlan]'s decision, from the
     * outcome and the length of the current run of missed check-ins.
     */
    private suspend fun escalate(
        repository: MedicineRepository,
        scheduled: ScheduledDose,
        outcome: String,
        note: String?,
        symptoms: List<SymptomExtractor.Detected> = emptyList()
    ) {
        val medicine = scheduled.medicine
        val preferences = AmiPreferences(applicationContext)
        val client = EscalationClient()

        val history = repository.recentCheckInsFor(medicine.id)
        val misses = EscalationPlan.consecutiveMisses(history)
        val redFlags = symptoms.filter { it.symptom.redFlag }

        // A red flag reaches everyone at once. Walking it up the chain a rung at a time,
        // the way a missed dose is walked up, would mean the person who can actually get
        // there hears about a fall two check-ins later.
        val recipients = if (redFlags.isNotEmpty()) {
            preferences.getCaregiversOnce()
        } else {
            EscalationPlan.recipientsFor(
                contacts = preferences.getCaregiversOnce(),
                outcome = outcome,
                consecutiveMisses = misses
            )
        }

        var callLine = ""
        if (outcome == CheckInRecord.OUTCOME_NO_ANSWER || outcome == CheckInRecord.OUTCOME_DECLINED) {
            val sid = client.requestPhoneCall(
                phoneNumber = preferences.getUserPhoneNumberOnce(),
                medicineName = medicine.name,
                message = "Hello. This is AMI. It is time for your ${scheduled.displayName}. " +
                    "Please remember to take it."
            )
            callLine = when (sid?.let { client.awaitCallOutcome(it) }) {
                EscalationClient.CallOutcome.ANSWERED ->
                    " AMI also rang their phone and the call was answered, so the reminder was heard."
                EscalationClient.CallOutcome.NOT_ANSWERED ->
                    " AMI also rang their phone and nobody picked up."
                // No verdict in time is reported as no verdict, never as answered.
                EscalationClient.CallOutcome.UNKNOWN ->
                    " AMI also rang their phone, but could not confirm whether it was answered."
                null -> ""
            }
            Log.d(TAG, "Phone-call rung for ${medicine.name} sid=$sid")
        }

        val detail = buildString {
            if (redFlags.isNotEmpty()) {
                // Led with, not appended: this is the line that decides whether someone
                // puts their coat on, and it must not sit below the adherence bookkeeping.
                append("URGENT: during the check-in they reported ")
                append(redFlags.joinToString(", and ") { it.symptom.spoken })
                append(". Their words: \"${redFlags.first().heard}\". Please check on them now.\n\n")
            }
            append("Check-in for ${scheduled.displayName} at ${scheduled.dose.time} ended as $outcome.")
            if (!note.isNullOrBlank()) append(" They said: \"$note\"")
            if (misses > 1) append(" That is $misses check-ins in a row without a confirmed dose.")

            val everyday = symptoms.filterNot { it.symptom.redFlag }
            if (everyday.isNotEmpty()) {
                append(" They also mentioned ")
                append(everyday.joinToString(", ") { it.symptom.spoken })
                append(".")
            }
            append(callLine)
        }

        val sent = client.escalate(
            caregiverEmails = recipients.map { it.email },
            medicineName = medicine.name,
            message = detail,
            urgent = redFlags.isNotEmpty() || EscalationPlan.isSustainedSilence(misses)
        )
        Log.d(
            TAG,
            "Escalation for ${medicine.name} outcome=$outcome misses=$misses " +
                "redFlags=${redFlags.size} recipients=${recipients.size} emailed=$sent"
        )
    }

    // --- Ringing -----------------------------------------------------------------

    private fun startForegroundRinging(scheduled: ScheduledDose) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // minSdk is 26, so notification channels always exist here.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "AMI Check-in Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming medicine check-in calls from AMI"
                setShowBadge(true)
            }
        )

        val fullScreenIntent = PendingIntent.getActivity(
            this,
            scheduled.dose.id.toInt(),
            AmiCallActivity.intent(this, scheduled.dose.id, scheduled.displayName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AMI is calling")
            .setContentText("Time for your ${scheduled.displayName}")
            .setSmallIcon(R.drawable.ic_ami_brain)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(fullScreenIntent)
            .setFullScreenIntent(fullScreenIntent, true)
            .build()

        // FOREGROUND_SERVICE_TYPE_MICROPHONE landed in API 30, not 29 - the other service
        // types came in with Q, this one did not. Declaring it below 30 passes a type the
        // platform does not know, so anything older starts as an untyped foreground service.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    /**
     * Replaces the ringing notification once the ring has ended.
     *
     * The service has to stay in the foreground while it places the reminder call and
     * waits for the outcome - dropping out would let the system kill it mid-escalation -
     * but it must stop presenting itself as an incoming call. Same notification id, so
     * this quietly swaps the ringing one out rather than stacking a second.
     */
    private fun showFollowUpNotification(scheduled: ScheduledDose) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Missed check-in")
            .setContentText("Following up about your ${scheduled.displayName}")
            .setSmallIcon(R.drawable.ic_ami_brain)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification)
    }

    // --- Voice -------------------------------------------------------------------

    private suspend fun speakAndWait(text: String) {
        val engine = tts ?: return
        val ready = withTimeoutOrNull(TTS_INIT_TIMEOUT_MS) { ttsReady.await() } ?: false
        if (!ready) {
            Log.w(TAG, "TTS not ready, skipping utterance")
            return
        }
        suspendCancellableCoroutine<Unit> { cont ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }
            })
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "AMI_CHECKIN_${System.currentTimeMillis()}")
        }
    }

    private suspend fun listenForResponse(): String? = withTimeoutOrNull(LISTEN_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val recognizer = speechRecognizer
            if (recognizer == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            _callCaption.value = "Listening…"
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (cont.isActive) cont.resume(matches?.firstOrNull())
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            recognizer.startListening(recognizerIntent)
            cont.invokeOnCancellation { recognizer.cancel() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        _callActive.value = false
        serviceScope.cancel()
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
    }

    companion object {
        private const val TAG = "AMI_WELLNESS"
        private const val CHANNEL_ID = "ami_checkin_call"
        private const val NOTIFICATION_ID = 4201
        private const val LISTEN_TIMEOUT_MS = 8000L
        private const val TTS_INIT_TIMEOUT_MS = 5000L
        private const val RING_TIMEOUT_MS = 45_000L
        private const val CONVERSATION_TIMEOUT_MS = 180_000L

        const val ACTION_ANSWER = "com.example.ami.action.ANSWER"
        const val ACTION_DECLINE = "com.example.ami.action.DECLINE"

        /** Resolved by [AmiCallActivity] answering or declining, or by the ring timeout. */
        private var answered = CompletableDeferred<Boolean>()

        private val _callCaption = MutableStateFlow("")

        /** What AMI is currently saying, surfaced as a caption on the call screen. */
        val callCaption: StateFlow<String> = _callCaption.asStateFlow()

        private val _callActive = MutableStateFlow(false)
        val callActive: StateFlow<Boolean> = _callActive.asStateFlow()

        fun answer(context: Context) = send(context, ACTION_ANSWER)

        fun decline(context: Context) = send(context, ACTION_DECLINE)

        private fun send(context: Context, action: String) {
            val intent = Intent(context, AmiWellnessService::class.java).apply {
                this.action = action
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Could not deliver $action", e)
            }
        }

        /** Used when the call UI can't be shown at all. */
        fun postFallbackNotification(context: Context, doseId: Long) {
            val channelId = "ami_wellness_fallback"
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(channelId, "AMI Reminders", NotificationManager.IMPORTANCE_HIGH)
            )
            val notification = NotificationCompat.Builder(context, channelId)
                .setContentTitle("Medicine reminder")
                .setContentText("Open AMI to confirm you've taken your medicine.")
                .setSmallIcon(R.drawable.ic_ami_brain)
                .setAutoCancel(true)
                .build()
            manager.notify(doseId.toInt(), notification)
        }
    }
}
