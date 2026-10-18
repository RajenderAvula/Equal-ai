package com.equalai.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The assistant's voice for phone calls. It owns its own text-to-speech engine, so calls can be answered
 * and spoken into even when the background voice service is not running.
 *
 * Each sentence is synthesised to a file, played on the loud channel (alarm stream by default, so the
 * caller hears it through the phone's own microphone) and mixed into the call recording.
 */
object CallVoice {

    private var app: Context? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var initializing = false
    private val main = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    fun init(ctx: Context) {
        app = ctx.applicationContext
        if (ready || initializing) return
        initializing = true
        val c = app ?: return
        main.post {
            try {
                tts = TextToSpeech(c) { status ->
                    initializing = false
                    val engine = tts
                    if (status == TextToSpeech.SUCCESS && engine != null) {
                        val r = engine.setLanguage(Locale("en", "IN"))
                        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                            engine.language = Locale.getDefault()
                        }
                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(id: String?) {}
                            override fun onDone(id: String?) { id?.let { pending.remove(it)?.complete(Unit) } }
                            override fun onStop(id: String?, interrupted: Boolean) { id?.let { pending.remove(it)?.complete(Unit) } }
                            @Deprecated("Deprecated in Java")
                            override fun onError(id: String?) { id?.let { pending.remove(it)?.complete(Unit) } }
                        })
                        ready = true
                    } else {
                        tts = null
                        AssistantBridge.log("Voice engine could not start (check Settings > Text-to-speech)")
                    }
                }
            } catch (e: Exception) {
                initializing = false
                tts = null
                AssistantBridge.log("Voice engine error: ${e.localizedMessage}")
            }
        }
    }

    val isReady: Boolean get() = ready

    suspend fun awaitReady(timeoutMs: Long = 5_000L): Boolean {
        app?.let { init(it) }
        val until = System.currentTimeMillis() + timeoutMs
        while (!ready && System.currentTimeMillis() < until) delay(100)
        return ready
    }

    fun stop() {
        main.post { try { tts?.stop() } catch (e: Exception) { } }
    }

    /** Speaks `text` into a call. Returns an error text, or null when it went fine. */
    suspend fun speakIntoCall(text: String, rec: CallRecorder?): String? {
        if (text.isBlank()) return null
        if (!awaitReady()) return "The voice engine is not ready. Install a text-to-speech voice in phone Settings."
        var failed: String? = null
        val sentences = text.take(1500).split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        for (sentence in sentences) {
            val wav = synthesize(sentence)
            if (wav == null) {
                failed = "Voice synthesis failed, used the simple voice path."
                speakSimple(sentence)
            } else {
                val (pcm, rate) = wav
                rec?.addAiAudio(resampleTo16k(pcm, rate))
                playPcm(pcm, rate)
            }
            delay(550)
        }
        return failed
    }

    private suspend fun speakSimple(sentence: String) {
        val id = "SIMPLE_${System.nanoTime()}"
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        try {
            withContext(Dispatchers.Main) {
                tts?.setSpeechRate(AppSettings.speechRate)
                val params = Bundle().apply {
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_VOICE_CALL)
                }
                tts?.speak(sentence, TextToSpeech.QUEUE_FLUSH, params, id)
            }
            withTimeoutOrNull(30_000L) { done.await() }
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun synthesize(sentence: String): Pair<ByteArray, Int>? {
        val ctx = app ?: return null
        val id = "SYN_${System.nanoTime()}"
        val file = File(ctx.cacheDir, "$id.wav")
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        try {
            val started = withContext(Dispatchers.Main) {
                tts?.setSpeechRate(AppSettings.speechRate)
                tts?.synthesizeToFile(sentence, Bundle(), file, id)
            }
            if (started != TextToSpeech.SUCCESS) return null
            withTimeoutOrNull(15_000L) { done.await() } ?: return null
            return withContext(Dispatchers.IO) { readWav(file) }
        } finally {
            pending.remove(id)
            file.delete()
        }
    }

    private fun readWav(f: File): Pair<ByteArray, Int>? {
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
        if (channels == 2) {
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

    /** Plays a pre-recorded voice clip into the call (loud channel) and returns when it ends. */
    suspend fun playClip(path: String): String? = withContext(Dispatchers.Main) {
        val done = CompletableDeferred<Unit>()
        val mp = MediaPlayer()
        var error: String? = null
        try {
            val usage = if (AppSettings.loudVoice) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_VOICE_COMMUNICATION
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            mp.setDataSource(path)
            mp.setOnCompletionListener { done.complete(Unit) }
            mp.setOnErrorListener { _, _, _ -> error = "The voice clip could not be played."; done.complete(Unit); true }
            mp.prepare()
            mp.setVolume(1f, 1f)
            mp.start()
            withTimeoutOrNull(120_000L) { done.await() }
        } catch (e: Exception) {
            error = "Could not play the voice clip: ${e.localizedMessage}"
        } finally {
            try { mp.release() } catch (e: Exception) { }
        }
        error
    }
}

