package com.example.ami

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.ami.data.ActionLogRecord
import com.example.ami.data.MedicineRepository
import com.example.ami.navigation.ScreenChangeDetector
import com.example.ami.navigation.SensitiveApps
import com.example.ami.navigation.SettingsDeepLinks
import kotlinx.coroutines.*
import java.util.*

class AmiAccessibilityService : AccessibilityService(), TextToSpeech.OnInitListener {

    private lateinit var cursorManager: AmiCursorManager
    private val plannerManager = AmiPlannerManager()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private var activeGoal: String? = null
    private var actionHistory = StringBuilder()
    private var analysisJob: Job? = null

    /**
     * Replaces the old "is this string different from last time" test, which treated a
     * ticking clock or an unread badge as a new screen and fired a planner call for it.
     */
    private val changeDetector = ScreenChangeDetector()

    private var lastCallTime: Long = 0
    private val MIN_CALL_INTERVAL = 1500L

    private val MAX_NODES = 100
    private val MAX_SCREEN_CHARS = 2000
    private val MAX_SCROLL_ATTEMPTS = 5
    private val SCROLL_SETTLE_MS = 600L

    /**
     * Scrolling fires TYPE_WINDOW_CONTENT_CHANGED, which would otherwise re-enter the
     * planner mid-search and race with the scroll loop. Suppress auto-analysis while
     * AMI is the one moving the screen.
     */
    @Volatile
    private var isDrivingScreen = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || activeGoal == null) return

        val eventType = event.eventType
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            debounceAutoAnalysis()
        }
    }

    private fun debounceAutoAnalysis() {
        if (isDrivingScreen) return
        analysisJob?.cancel()
        analysisJob = serviceScope.launch {
            delay(800)
            val goal = activeGoal ?: return@launch
            val rootNode = rootInActiveWindow ?: return@launch
            val packageName = rootNode.packageName?.toString()

            if (refuseIfSensitive(goal, packageName)) return@launch

            val currentText = getCleanScreenText(rootNode)
            if (!changeDetector.accept(currentText, packageName)) return@launch

            val currentTime = System.currentTimeMillis()
            if (currentTime - lastCallTime <= MIN_CALL_INTERVAL) return@launch

            lastCallTime = currentTime
            processStep(goal)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        cursorManager = AmiCursorManager(this)
        mainHandler.post { initSpeechTools() }

        cursorManager.showTrigger(
            onTriggerClicked = { startListening() },
            onStopClicked = { stopCurrentTask("I'll be here if you need me.") }
        )
    }

    private fun stopCurrentTask(
        message: String,
        outcome: String = ActionLogRecord.OUTCOME_STOPPED,
        packageName: String? = null
    ) {
        val goal = activeGoal
        activeGoal = null
        analysisJob?.cancel()
        changeDetector.reset()
        tts?.stop()
        cursorManager.updateCursorText(message)
        cursorManager.setStopButtonVisibility(false)
        speak(message)
        goal?.let { logAction(it, outcome, message, packageName, outcome) }
        serviceScope.launch {
            delay(2000)
            cursorManager.hideCursor()
        }
    }

    /**
     * Refuses to read banking, payment and credential apps.
     *
     * Guided navigation ships the visible labels of the foreground app to a third-party
     * model. Excluding password fields was never enough on its own - a balance, an
     * account number or a one-time code is ordinary text. Inside those apps AMI stops
     * the task outright rather than degrading, and says why, because silently doing
     * nothing would read as a broken app.
     *
     * Returns whether the task was refused.
     */
    private fun refuseIfSensitive(goal: String, packageName: String?): Boolean {
        if (!SensitiveApps.isSensitive(packageName)) return false

        Log.i(TAG, "Refusing to read sensitive package $packageName")
        // stopCurrentTask writes the log row, including the package, so the caregiver
        // can see which app was refused. Logging here too would double every refusal.
        stopCurrentTask(SensitiveApps.REFUSAL, ActionLogRecord.OUTCOME_BLOCKED, packageName)
        return true
    }

    /**
     * Records one step for the caregiver's review screen.
     *
     * Fire-and-forget on the IO dispatcher: a database write must never delay the
     * cursor. Only the action and what AMI said are stored - never the screen text
     * that was sent to the model, which is the sensitive part.
     */
    private fun logAction(
        goal: String,
        action: String,
        spokenText: String?,
        packageName: String?,
        outcome: String = ActionLogRecord.OUTCOME_OK
    ) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                MedicineRepository.getInstance(applicationContext)
                    .recordAction(goal, action, spokenText, packageName, outcome)
            } catch (e: Exception) {
                Log.w(TAG, "Could not write action log", e)
            }
        }
    }

    private fun initSpeechTools() {
        try {
            if (SpeechRecognizer.isRecognitionAvailable(this)) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                speechRecognizer?.setRecognitionListener(AmiRecognitionListener())
            }
            tts = TextToSpeech(this, this)
        } catch (e: Exception) {}
    }

    private fun startListening() {
        mainHandler.post {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            cursorManager.showCursor("I'm listening...")
            speechRecognizer?.startListening(intent)
        }
    }

    private fun speak(text: String) {
        if (isTtsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "AMI_TTS")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            isTtsReady = true
        }
    }

    private inner class AmiRecognitionListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            cursorManager.updateCursorText("I didn't catch that. Could you say it again?")
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val query = matches[0]
                if (query.lowercase().contains("stop") || query.lowercase().contains("cancel")) {
                    stopCurrentTask("Understood.")
                    return
                }
                activeGoal = query
                actionHistory = StringBuilder("Goal: $query. ")
                // A new goal must always be planned against the current screen, even
                // if it is the same screen the last goal ended on.
                changeDetector.reset()
                cursorManager.setStopButtonVisibility(true)
                serviceScope.launch {
                    lastCallTime = System.currentTimeMillis()
                    processStep(query)
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                cursorManager.updateCursorText(matches[0])
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private suspend fun processStep(goal: String) {
        val rootNode = rootInActiveWindow ?: return
        val currentPackage = rootNode.packageName?.toString()

        if (refuseIfSensitive(goal, currentPackage)) return

        val screenText = getCleanScreenText(rootNode)
        // Keep the detector in step with what was actually planned against, so the
        // very next content-changed event doesn't re-plan the identical screen.
        changeDetector.accept(screenText, currentPackage)

        cursorManager.updateCursorText("Let me think...")

        val (action, voiceResponse) = plannerManager.findNextAction(screenText, goal, actionHistory.toString())

        if (action == "GOAL_REACHED") {
            stopCurrentTask(
                voiceResponse ?: "You did it! Great job.",
                ActionLogRecord.OUTCOME_GOAL_REACHED
            )
            return
        }

        voiceResponse?.let {
            cursorManager.updateCursorText(it)
            speak(it)
            actionHistory.append("$it. ")
        }

        if (!action.isNullOrBlank()) {
            logAction(goal, action, voiceResponse, currentPackage)
            when {
                action.startsWith("SETTINGS:") -> openSettings(action.substringAfter("SETTINGS:"))
                action.startsWith("LAUNCH:") -> launchAppByName(action.substringAfter("LAUNCH:"))
                action.startsWith("ACTION:") -> performGlobalSystemAction(action.substringAfter("ACTION:"))
                action.startsWith("SWIPE:") -> cursorManager.animateSwipe(action.substringAfter("SWIPE:"))
                else -> findAndPointToNode(rootNode, action, goal, currentPackage)
            }
        }
    }

    private fun openSettings(target: String) {
        val destination = SettingsDeepLinks.open(this, target)
        if (destination == null) {
            val message = "I couldn't open the settings."
            cursorManager.updateCursorText(message)
            speak(message)
        }
    }

    // Package visibility is already granted by the MAIN/LAUNCHER <intent> in the manifest's
    // <queries> block, so the filtered list still contains every launchable app - which is
    // exactly the set this resolves against. Lint cannot see that correlation.
    @SuppressLint("QueryPermissionsNeeded")
    private fun launchAppByName(name: String) {
        val target = name.trim()
        val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }

        val match = apps.firstOrNull { it.loadLabel(packageManager).toString().equals(target, ignoreCase = true) }
            ?: apps.firstOrNull { it.loadLabel(packageManager).toString().contains(target, ignoreCase = true) }

        // Launching a banking app on request is fine; reading it is not. The refusal
        // fires on the next screen event, once that app is actually in the foreground.
        val intent = match?.let { packageManager.getLaunchIntentForPackage(it.packageName) }
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } else {
            val message = "I couldn't find an app called $target."
            cursorManager.updateCursorText(message)
            speak(message)
        }
    }

    private fun performGlobalSystemAction(action: String) {
        when (action.uppercase()) {
            "NOTIFICATIONS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    /**
     * Flattens the visible tree for the planner, annotating each label with what can be
     * done to it. Without the affordances the model cannot tell a heading from a button,
     * nor see that a toggle is already on. Password nodes stay excluded.
     */
    private fun getCleanScreenText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        val stack = mutableListOf<AccessibilityNodeInfo>()
        stack.add(node)
        var count = 0
        while (stack.isNotEmpty() && count < MAX_NODES) {
            val current = stack.removeAt(stack.size - 1)
            if (current.isVisibleToUser && !current.isPassword) {
                val label = current.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: current.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                if (label != null) {
                    sb.append(label).append(describeAffordances(current)).append(" | ")
                    count++
                }
            }
            for (i in 0 until current.childCount) {
                current.getChild(i)?.let { stack.add(it) }
            }
        }
        return sb.toString().take(MAX_SCREEN_CHARS)
    }

    /**
     * API 36 replaced the boolean `isChecked` with a tri-state `getChecked()`, because a
     * checkbox can also sit half-selected. The model only ever gets told on or off, so
     * partial reads as off - "not yet confirmed" is the safer thing for it to believe.
     */
    private fun isNodeChecked(node: AccessibilityNodeInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            node.checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE
        } else {
            @Suppress("DEPRECATION")
            node.isChecked
        }

    private fun describeAffordances(node: AccessibilityNodeInfo): String {
        val flags = mutableListOf<String>()
        when {
            node.isCheckable -> flags.add(if (isNodeChecked(node)) "toggle:on" else "toggle:off")
            node.isClickable -> flags.add("tap")
        }
        if (node.isScrollable) flags.add("scrollable")
        return if (flags.isEmpty()) "" else flags.joinToString(",", prefix = " [", postfix = "]")
    }

    /**
     * Points at [text], scrolling to look for it when it isn't on screen yet.
     *
     * Without this, any target below the fold simply failed silently - which is most
     * of a Settings screen.
     */
    private suspend fun findAndPointToNode(
        rootNode: AccessibilityNodeInfo,
        text: String,
        goal: String,
        packageName: String?
    ) {
        if (pointToNodeIfPresent(rootNode, text)) return

        isDrivingScreen = true
        try {
            repeat(MAX_SCROLL_ATTEMPTS) {
                val scrollable = findScrollableNode(rootInActiveWindow ?: return) ?: return@repeat
                val scrolled = scrollable.performAction(
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                )
                if (!scrolled) return@repeat
                delay(SCROLL_SETTLE_MS)
                val fresh = rootInActiveWindow ?: return
                if (pointToNodeIfPresent(fresh, text)) return
            }
        } finally {
            isDrivingScreen = false
            // The scrolling above moved the screen; whatever is showing now is what
            // the next planner call must compare against.
            changeDetector.reset()
        }

        val message = "I can't find that on this screen."
        cursorManager.updateCursorText(message)
        speak(message)
        logAction(goal, text, message, packageName, ActionLogRecord.OUTCOME_NOT_FOUND)
    }

    /** Nearest scrollable node, breadth-first from the root. */
    private fun findScrollableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val current = queue.removeFirst()
            visited++
            if (current.isScrollable) return current
            for (i in 0 until current.childCount) {
                current.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /** Points at [text] if it is currently on screen. Returns whether it was found. */
    private fun pointToNodeIfPresent(rootNode: AccessibilityNodeInfo, text: String): Boolean {
        val cleanText = text.replace("\"", "").replace("'", "").trim()
        val nodes = rootNode.findAccessibilityNodeInfosByText(cleanText)
        if (nodes.isNotEmpty()) {
            moveCursorToNode(nodes[0])
            return true
        }
        val partialNode = findNodeByPartialText(rootNode, cleanText)
        if (partialNode != null) {
            moveCursorToNode(partialNode)
            return true
        }
        return false
    }

    private fun findNodeByPartialText(node: AccessibilityNodeInfo, targetText: String): AccessibilityNodeInfo? {
        val stack = mutableListOf<AccessibilityNodeInfo>()
        stack.add(node)
        while (stack.isNotEmpty()) {
            val current = stack.removeAt(stack.size - 1)
            if (current.isVisibleToUser) {
                val nodeText = current.text?.toString() ?: ""
                val nodeDesc = current.contentDescription?.toString() ?: ""
                if (nodeText.contains(targetText, ignoreCase = true) ||
                    nodeDesc.contains(targetText, ignoreCase = true)) {
                    return current
                }
            }
            for (i in 0 until current.childCount) {
                current.getChild(i)?.let { stack.add(it) }
            }
        }
        return null
    }

    private fun moveCursorToNode(node: AccessibilityNodeInfo) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        cursorManager.showCursor()
        cursorManager.moveCursorTo(rect.centerX(), rect.centerY())
    }

    override fun onInterrupt() {
        serviceScope.cancel()
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
    }

    companion object {
        private const val TAG = "AMI_A11Y"
    }
}
