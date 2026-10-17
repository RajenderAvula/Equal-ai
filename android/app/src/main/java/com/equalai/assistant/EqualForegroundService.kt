package com.equalai.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Wake word: "hey equal", "ok equal", "okay equal", "hi equal". */
object WakeWord {
    private val regex = Regex("""\b(?:hey|hi|ok|okay)\s+equal\b[,.!]?\s*""", RegexOption.IGNORE_CASE)

    /** Returns (wake word heard, command text after the wake word). */
    fun extract(text: String): Pair<Boolean, String> {
        val m = regex.find(text) ?: return false to text.trim()
        return true to text.substring(m.range.last + 1).trim()
    }
}

class EqualForegroundService : Service(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var recognizer: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())
    private var isListening = false
    private var isSpeaking = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null

    private val restartRunnable = Runnable {
        if (!isSpeaking && !AssistantBridge.callInProgress &&
            (AssistantBridge.isContinuousListeningEnabled || AssistantBridge.forceNextCommand)) {
            startListeningInternal(false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        AssistantBridge.foregroundService = this
        AppSettings.init(this)

        createNotificationChannel()
        try {
            val type = when {
                Build.VERSION.SDK_INT >= 34 ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                Build.VERSION.SDK_INT >= 30 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else -> 0
            }
            ServiceCompat.startForeground(this, 1001, buildNotification("Equal AI is listening"), type)
        } catch (e: Exception) {
            AssistantBridge.log("Foreground start failed: ${e.localizedMessage}")
        }

        try {
            tts = TextToSpeech(this, this)
        } catch (e: Exception) {
            AssistantBridge.log("TTS init error: ${e.localizedMessage}")
        }

        main.postDelayed({ initSpeechRecognizer() }, 1200)
        startPolling()
    }

    // ---------------------------------------------------------------- speech in

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            AssistantBridge.log("Speech recognition is not available on this device")
            return
        }
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        AssistantBridge.promptInTextBoxCallback?.invoke(text ?: "")
                        if (!text.isNullOrBlank()) handleSpoken(text)
                        scheduleRestart(700)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (!t.isNullOrBlank()) AssistantBridge.promptInTextBoxCallback?.invoke("Hearing: $t...")
                    }

                    override fun onError(error: Int) {
                        isListening = false
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleRestart(300)
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                                recognizer?.cancel()
                                scheduleRestart(1500)
                            }
                            SpeechRecognizer.ERROR_CLIENT, 11 -> {      // 11 = server disconnected
                                main.postDelayed({ initSpeechRecognizer() }, 1500)
                            }
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                                AssistantBridge.log("Microphone permission missing")
                            }
                            12, 13 -> {                                 // language not supported / unavailable
                                AssistantBridge.log(
                                    if (AssistantBridge.offlineMode)
                                        "Offline speech pack missing: install English (India) offline speech in Google app settings."
                                    else "Speech language unavailable"
                                )
                                scheduleRestart(5000)
                            }
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                                AssistantBridge.log("Speech needs network (or turn on Offline mode)")
                                scheduleRestart(4000)
                            }
                            else -> {
                                AssistantBridge.log("Speech error $error")
                                scheduleRestart(1500)
                            }
                        }
                    }

                    override fun onReadyForSpeech(params: Bundle?) { isListening = true }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
            scheduleRestart(200)
        } catch (e: Exception) {
            AssistantBridge.log("Recognizer init error: ${e.localizedMessage}")
        }
    }

    fun startListeningInternal(playBeep: Boolean) {
        main.post {
            if (isListening || isSpeaking || AssistantBridge.callInProgress) return@post
            if (!AssistantBridge.isContinuousListeningEnabled && !AssistantBridge.forceNextCommand) return@post

            if (playBeep) {
                try {
                    ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100).startTone(ToneGenerator.TONE_PROP_PROMPT, 150)
                } catch (e: Exception) { }
                AssistantBridge.promptInTextBoxCallback?.invoke("🎤 Listening...")
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                if (AssistantBridge.offlineMode) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            try {
                recognizer?.startListening(intent)
                isListening = true
            } catch (e: Exception) {
                isListening = false
                scheduleRestart(1500)
            }
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        main.removeCallbacks(restartRunnable)
        main.postDelayed(restartRunnable, delayMs)
    }

    private fun handleSpoken(raw: String) {
        val text = raw.trim()
        val now = System.currentTimeMillis()
        val force = AssistantBridge.forceNextCommand
        val inFollowUp = now < AssistantBridge.followUpUntil
        val (heardWake, command) = WakeWord.extract(text)

        // Ambient speech (TV, other people) is ignored unless the wake word was said,
        // the mic button was tapped, or we just asked a question.
        if (AssistantBridge.wakeWordRequired && !heardWake && !force && !inFollowUp) return

        AssistantBridge.forceNextCommand = false
        AssistantBridge.followUpUntil = 0L

        if (heardWake && command.isBlank()) {
            speakBackground("Yes?", listenAfter = true)
            return
        }
        val cmd = if (heardWake) command else text
        AssistantBridge.log("Command: \"$cmd\"")
        scope.launch { CommandRouter.handle(applicationContext, cmd) }
    }

    // ---------------------------------------------------------------- speech out

    fun speakBackground(text: String, listenAfter: Boolean = false) {
        main.post {
            if (AssistantBridge.callInProgress) return@post      // never talk over a phone call
            if (!isTtsReady || text.isBlank()) {
                if (!isTtsReady) AssistantBridge.log("Text-to-speech not ready")
                return@post
            }
            main.removeCallbacks(restartRunnable)
            recognizer?.cancel()
            isListening = false
            isSpeaking = true
            tts?.speak(text.take(3500), TextToSpeech.QUEUE_FLUSH, null, if (listenAfter) "LISTEN_AFTER" else "SPEAK_ONLY")
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            AssistantBridge.log("Text-to-speech failed to start")
            return
        }
        val r = tts?.setLanguage(Locale("en", "IN"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.language = Locale.getDefault()
        }
        isTtsReady = true
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) = finished(id)
            override fun onStop(id: String?, interrupted: Boolean) {
                id?.let { callUtterances.remove(it)?.complete(Unit) }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = finished(id)
        })
    }

    private fun finished(id: String?) {
        if (id != null) {
            val d = callUtterances.remove(id)
            if (d != null) { d.complete(Unit); return }
        }
        main.post {
            if (AssistantBridge.callInProgress) return@post
            isSpeaking = false
            if (id == "LISTEN_AFTER") {
                AssistantBridge.followUpUntil = System.currentTimeMillis() + 12_000L
                startListeningInternal(true)
            } else {
                scheduleRestart(800)
            }
        }
    }

    // ---------------------------------------------------------------- phone-call support

    private val callUtterances = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /** Pauses the wake-word listener so the microphone is free for the call recorder. */
    fun enterCallMode() {
        main.removeCallbacks(restartRunnable)
        try { recognizer?.cancel() } catch (e: Exception) { }
        isListening = false
        isSpeaking = false
        tts?.stop()
    }

    fun exitCallMode() {
        scheduleRestart(1500)
    }

    fun stopSpeakingNow() {
        main.post { tts?.stop() }
    }

    /** Speaks into the call (voice-call stream, loud) and returns once the sentence has finished. */
    suspend fun speakAndWait(text: String) {
        if (!isTtsReady || text.isBlank()) return
        val id = "CALL_${System.nanoTime()}"
        val done = CompletableDeferred<Unit>()
        callUtterances[id] = done
        try {
            withContext(Dispatchers.Main) {
                val params = Bundle().apply {
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_VOICE_CALL)
                }
                tts?.speak(text.take(1500), TextToSpeech.QUEUE_ADD, params, id)
            }
            withTimeoutOrNull(45_000L) { done.await() }
        } finally {
            callUtterances.remove(id)
        }
    }

    // ---------------------------------------------------------------- summaries waiting for internet

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                try {
                    CallAnswerer.retryPending(applicationContext)
                } catch (e: Exception) {
                    AssistantBridge.log("Summary retry error: ${e.localizedMessage}")
                }
                delay(60_000L)
            }
        }
    }

    // ---------------------------------------------------------------- plumbing

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("equal_bg_channel", "Equal AI background", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "equal_bg_channel")
            .setContentTitle("Equal AI Assistant")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (AssistantBridge.foregroundService === this) AssistantBridge.foregroundService = null
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
