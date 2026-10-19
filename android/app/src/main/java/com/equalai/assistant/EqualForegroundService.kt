package com.equalai.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    // Uses StreamingSpeechHelper: the on-device (offline) recognizer when Offline mode is on or there is
    // no internet, the cloud recognizer otherwise. No language is forced, so the installed pack is used.

    private var speech: StreamingSpeechHelper? = null
    private var lastNote = ""

    private fun note(msg: String) {
        if (msg != lastNote) { lastNote = msg; AssistantBridge.log(msg) }
    }

    private fun initSpeechRecognizer() {
        val h = StreamingSpeechHelper(this)
        speech = h
        if (!h.isAvailable() && !h.isOnDeviceAvailable()) {
            AssistantBridge.log("Speech recognition is not available on this device")
            return
        }
        scheduleRestart(200)
    }

    fun startListeningInternal(playBeep: Boolean) {
        main.post {
            if (isListening || isSpeaking || AssistantBridge.callInProgress) return@post
            if (!AssistantBridge.isContinuousListeningEnabled && !AssistantBridge.forceNextCommand) return@post
            val h = speech ?: return@post

            if (playBeep) {
                try {
                    ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100).startTone(ToneGenerator.TONE_PROP_PROMPT, 150)
                } catch (e: Exception) { }
                AssistantBridge.promptInTextBoxCallback?.invoke("🎤 Listening...")
            }

            val online = !AssistantBridge.offlineMode && h.isOnline()
            if (!online && !h.isOnDeviceAvailable() && !AssistantBridge.offlineMode) {
                note("No internet. Using the offline microphone.")
            }
            isListening = true
            h.begin(
                online = online,
                partialResults = true,
                onPartial = { AssistantBridge.promptInTextBoxCallback?.invoke("Hearing: $it...") },
                onResult = { text ->
                    isListening = false
                    lastNote = ""
                    AssistantBridge.promptInTextBoxCallback?.invoke(text)
                    if (text.isNotBlank() && handleSpoken(text)) {
                        // command accepted and running: empty the box so it doesn't look like it is waiting for Enter
                        AssistantBridge.promptInTextBoxCallback?.invoke("")
                    } else if (text.isNotBlank() && AssistantBridge.wakeWordRequired) {
                        note("Heard \"$text\" but ignored it: say \"Hey Equal\" first, or tap the mic button.")
                    }
                    scheduleRestart(700)
                },
                onFail = { code, msg ->
                    isListening = false
                    when (code) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleRestart(300)
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT, 11 -> {
                            h.stop()
                            scheduleRestart(1500)
                        }
                        else -> {
                            note(msg)
                            scheduleRestart(if (code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS || code == -1) 8000 else 4000)
                        }
                    }
                },
                onListeningState = { isListening = it }
            )
        }
    }

    /** Used by the Setup tab: download the offline speech pack. */
    fun downloadOfflineSpeech(): String = (speech ?: StreamingSpeechHelper(this)).downloadOfflineModel()

    private fun scheduleRestart(delayMs: Long) {
        main.removeCallbacks(restartRunnable)
        main.postDelayed(restartRunnable, delayMs)
    }

    private fun handleSpoken(raw: String): Boolean {
        val text = raw.trim()
        val now = System.currentTimeMillis()
        val force = AssistantBridge.forceNextCommand
        val inFollowUp = now < AssistantBridge.followUpUntil
        val (heardWake, command) = WakeWord.extract(text)

        // Ambient speech (TV, other people) is ignored unless the wake word was said,
        // the mic button was tapped, or we just asked a question.
        if (AssistantBridge.wakeWordRequired && !heardWake && !force && !inFollowUp) return false

        AssistantBridge.forceNextCommand = false
        AssistantBridge.followUpUntil = 0L

        if (heardWake && command.isBlank()) {
            speakBackground("Yes?", listenAfter = true)
            return false
        }
        val cmd = if (heardWake) command else text
        AssistantBridge.log("Command: \"$cmd\"")
        scope.launch { CommandRouter.handle(applicationContext, cmd) }
        return true
    }

    // ---------------------------------------------------------------- notification queue
    // One notification at a time: it is read out, then we wait for what you want to do. Others that arrive
    // meanwhile wait in line and are announced as soon as the current one is dealt with (or times out).

    private val notifQueue = java.util.ArrayDeque<AppNotificationItem>()
    private var notifActive: AppNotificationItem? = null
    private val notifTimeout = Runnable { resolveActiveNotification(force = true) }

    private fun promptFor(item: AppNotificationItem): String {
        val waiting = notifQueue.size
        return "From ${item.appName}: ${item.message}" + (if (waiting > 0) " ($waiting more waiting)" else "") + " -> What next?"
    }

    fun announceNotification(item: AppNotificationItem) {
        main.post {
            notifQueue.addLast(item)
            while (notifQueue.size > 20) notifQueue.removeFirst()
            val active = notifActive
            if (active == null) nextNotification()
            else AssistantBridge.promptInTextBoxCallback?.invoke(promptFor(active))     // update the waiting count
        }
    }

    private fun nextNotification() {
        if (AssistantBridge.callInProgress) {                       // never talk over a phone call
            main.postDelayed({ if (notifActive == null) nextNotification() }, 5_000L)
            return
        }
        val item = notifQueue.pollFirst()
        if (item == null) {
            notifActive = null
            return
        }
        notifActive = item
        AssistantBridge.latestNotification = item
        AssistantBridge.promptInTextBoxCallback?.invoke(promptFor(item))
        speakBackground(
            "Notification from ${item.appName}. ${item.sender} says: ${item.message}. What would you like to do next?",
            listenAfter = true
        )
        main.removeCallbacks(notifTimeout)
        main.postDelayed(notifTimeout, 45_000L)                     // safety net if nothing is ever said
    }

    /**
     * A notification was cleared (swiped away, dismissed in Equal, or removed by its app): drop it from the
     * waiting line, and if it is the one being shown or read out, stop and move on to the next.
     */
    fun notificationRemoved(key: String) {
        main.post {
            notifQueue.removeAll { it.key == key }
            val active = notifActive
            if (active != null && active.key == key) {
                main.removeCallbacks(notifTimeout)
                if (AssistantBridge.latestNotification?.key == key) AssistantBridge.latestNotification = null
                notifActive = null
                tts?.stop()
                isSpeaking = false
                AssistantBridge.promptInTextBoxCallback?.invoke("")
                if (notifQueue.isNotEmpty()) nextNotification()
            } else if (active != null) {
                AssistantBridge.promptInTextBoxCallback?.invoke(promptFor(active))     // update the waiting count
            }
        }
    }

    /** "Clear all" in the app: forget every waiting notification too. */
    fun clearNotificationQueue() {
        main.post {
            notifQueue.clear()
            main.removeCallbacks(notifTimeout)
            if (notifActive != null) {
                notifActive = null
                tts?.stop()
                isSpeaking = false
                AssistantBridge.promptInTextBoxCallback?.invoke("")
            }
        }
    }

    /** You answered (spoken or typed): move on to the next waiting notification shortly. */
    fun notificationHandled() {
        main.post {
            if (notifActive == null) return@post
            main.removeCallbacks(notifTimeout)
            main.postDelayed({ resolveActiveNotification(force = false) }, 2_500L)
        }
    }

    private fun resolveActiveNotification(force: Boolean) {
        if (!force && (AssistantBridge.pendingAction != null || isSpeaking)) {
            main.postDelayed({ resolveActiveNotification(false) }, 3_000L)       // a confirmation is still open
            return
        }
        main.removeCallbacks(notifTimeout)
        notifActive = null
        if (notifQueue.isNotEmpty()) nextNotification()
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
            speech?.stop()
            isListening = false
            isSpeaking = true
            tts?.setSpeechRate(AppSettings.speechRate)
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
        tts?.setSpeechRate(AppSettings.speechRate)
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
                if (notifActive != null) {                              // give up on this one after a while
                    main.removeCallbacks(notifTimeout)
                    main.postDelayed(notifTimeout, 25_000L)
                }
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
        try { speech?.stop() } catch (e: Exception) { }
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
                tts?.setSpeechRate(AppSettings.speechRate)
                val params = Bundle().apply {
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_VOICE_CALL)
                }
                // One sentence at a time with a short pause between them, so the caller can follow.
                val sentences = text.take(1500).split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
                sentences.forEachIndexed { i, sentence ->
                    val last = i == sentences.lastIndex
                    tts?.speak(sentence, TextToSpeech.QUEUE_ADD, params, if (last) id else "$id-$i")
                    if (!last) tts?.playSilentUtterance(450L, TextToSpeech.QUEUE_ADD, "$id-s$i")
                }
            }
            withTimeoutOrNull(60_000L) { done.await() }
        } finally {
            callUtterances.remove(id)
        }
    }

    /**
     * Speaks into a call. The sentence is synthesised to a file, played on the loud channel (alarm
     * stream by default) so the caller hears it through the telephone microphone, and the same audio is
     * added to the call recording. Falls back to plain TTS if synthesis fails.
     */
    suspend fun speakIntoCall(text: String, rec: CallRecorder?) {
        if (!isTtsReady || text.isBlank()) return
        val sentences = text.take(1500).split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        for (sentence in sentences) {
            val wav = synthesize(sentence)
            if (wav == null) {
                speakAndWait(sentence)
            } else {
                val (pcm, rate) = wav
                rec?.addAiAudio(resampleTo16k(pcm, rate))
                playPcm(pcm, rate)
            }
            delay(550)                                              // a clear pause between sentences
        }
    }

    /** Plays a pre-recorded voice clip into the call (loud channel) and returns when it ends. */
    suspend fun playClip(path: String) = withContext(Dispatchers.Main) {
        val done = CompletableDeferred<Unit>()
        val mp = MediaPlayer()
        try {
            val usage = if (AppSettings.loudVoice) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_VOICE_COMMUNICATION
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            mp.setDataSource(path)
            mp.setOnCompletionListener { done.complete(Unit) }
            mp.setOnErrorListener { _, _, _ -> done.complete(Unit); true }
            mp.prepare()
            mp.setVolume(1f, 1f)
            mp.start()
            withTimeoutOrNull(120_000L) { done.await() }
        } catch (e: Exception) {
            AssistantBridge.log("Could not play the voice clip: ${e.localizedMessage}")
        } finally {
            try { mp.release() } catch (e: Exception) { }
        }
    }

    private suspend fun synthesize(sentence: String): Pair<ByteArray, Int>? {
        val id = "SYN_${System.nanoTime()}"
        val file = File(cacheDir, "$id.wav")
        val done = CompletableDeferred<Unit>()
        callUtterances[id] = done
        try {
            val started = withContext(Dispatchers.Main) {
                tts?.setSpeechRate(AppSettings.speechRate)
                tts?.synthesizeToFile(sentence, Bundle(), file, id)
            }
            if (started != TextToSpeech.SUCCESS) return null
            withTimeoutOrNull(15_000L) { done.await() } ?: return null
            return withContext(Dispatchers.IO) { readTtsWav(file) }
        } finally {
            callUtterances.remove(id)
            file.delete()
        }
    }

    private fun readTtsWav(f: File): Pair<ByteArray, Int>? {
        if (!f.exists() || f.length() < 48) return null
        val b = f.readBytes()
        if (String(b, 0, 4) != "RIFF") return null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var rate = 22050
        var channels = 1
        var bits = 16
        var dataOff = -1
        var dataLen = 0
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4)
            val len = bb.getInt(pos + 4)
            if (id == "fmt ") {
                channels = bb.getShort(pos + 10).toInt()
                rate = bb.getInt(pos + 12)
                bits = bb.getShort(pos + 22).toInt()
            } else if (id == "data") {
                dataOff = pos + 8
                dataLen = if (len <= 0 || dataOff + len > b.size) b.size - dataOff else len
                break
            }
            if (len < 0) break
            pos += 8 + len + (len and 1)
        }
        if (dataOff < 0 || bits != 16 || dataLen < 200 || rate < 8000) return null
        var pcm = b.copyOfRange(dataOff, dataOff + dataLen)
        if (channels == 2) {                                        // keep the left channel
            val mono = ByteArray(pcm.size / 2)
            var i = 0
            var o = 0
            while (i + 3 < pcm.size) {
                mono[o] = pcm[i]; mono[o + 1] = pcm[i + 1]
                i += 4; o += 2
            }
            pcm = mono
        }
        return pcm to rate
    }

    private suspend fun playPcm(pcm: ByteArray, rate: Int) = withContext(Dispatchers.IO) {
        val usage = if (AppSettings.loudVoice) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_VOICE_COMMUNICATION
        var track: AudioTrack? = null
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val fmt = AudioFormat.Builder()
                .setSampleRate(rate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(pcm.size, minBuf))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track = t
            t.setVolume(1.0f)
            t.write(pcm, 0, pcm.size)
            t.play()
            val frames = pcm.size / 2
            val ms = frames * 1000L / rate
            val until = System.currentTimeMillis() + ms + 1500L
            while (System.currentTimeMillis() < until && t.playbackHeadPosition < frames - 100) delay(40)
            delay(150)
        } catch (e: Exception) {
            AssistantBridge.log("Voice playback error: ${e.localizedMessage}")
        } finally {
            try { track?.stop() } catch (e: Exception) { }
            try { track?.release() } catch (e: Exception) { }
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
        speech?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
