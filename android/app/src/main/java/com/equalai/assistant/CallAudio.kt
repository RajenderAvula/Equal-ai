package com.equalai.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

const val SAMPLE_RATE = 16_000
const val MIC_SILENT_PEAK = 40                       // below this the mic is considered blocked
private const val MAX_WAV_BYTES = 60 * 1024 * 1024   // ~30 minutes of 16 kHz mono

// ============================================================================
// WAV helpers
// ============================================================================

object Wav {
    fun header(pcmBytes: Int, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray())
        b.putInt(36 + pcmBytes)
        b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray())
        b.putInt(16)
        b.putShort(1.toShort())          // PCM
        b.putShort(1.toShort())          // mono
        b.putInt(sampleRate)
        b.putInt(sampleRate * 2)
        b.putShort(2.toShort())
        b.putShort(16.toShort())
        b.put("data".toByteArray())
        b.putInt(pcmBytes)
        return b.array()
    }

    fun wrap(pcm: ByteArray): ByteArray = header(pcm.size) + pcm
}

class WavFileWriter(file: File) {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0
    private var closed = false

    init {
        raf.setLength(0)
        raf.write(Wav.header(0))
    }

    val bytes: Int get() = dataBytes

    @Synchronized
    fun write(buf: ByteArray, len: Int) {
        if (closed) return
        try {
            raf.write(buf, 0, len)
            dataBytes += len
        } catch (e: Exception) {
            // disk full or file gone: stop writing, keep the call going
            closed = true
        }
    }

    @Synchronized
    fun close() {
        if (closed && dataBytes == 0) return
        try {
            raf.seek(0)
            raf.write(Wav.header(dataBytes))
            raf.close()
        } catch (e: Exception) { }
        closed = true
    }
}

/** Reads raw PCM from a WAV we wrote (the header is always 44 bytes; the real length comes from the file). */
fun readPcmRegion(file: File, offset: Int, length: Int): ByteArray {
    RandomAccessFile(file, "r").use { raf ->
        raf.seek(44L + offset)
        val out = ByteArray(length)
        raf.readFully(out)
        return out
    }
}

fun peakOf(buf: ByteArray, len: Int): Int {
    var p = 0
    var i = 0
    while (i + 1 < len) {
        val v = abs(((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt())
        if (v > p) p = v
        i += 2
    }
    return p
}

private fun rmsOf(buf: ByteArray, len: Int): Double {
    var sum = 0.0
    var count = 0
    var i = 0
    while (i + 1 < len) {
        val v = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt().toDouble()
        sum += v * v
        count++
        i += 2
    }
    return if (count == 0) 0.0 else sqrt(sum / count)
}

// ============================================================================
// One spoken turn: collects audio from the first speech until the speaker pauses
// ============================================================================

class Utterance(
    private val maxMs: Long,
    private val silenceMs: Long,
    private val noSpeechMs: Long
) {
    val pcm = ByteArrayOutputStream()
    @Volatile var done = false
    @Volatile var heardSpeech = false

    private val startedAt = System.currentTimeMillis()
    private var lastVoiceAt = 0L
    private var voicedRun = 0
    private var noise = 250.0
    private val preRoll = ArrayDeque<ByteArray>()

    fun onFrame(buf: ByteArray, len: Int) {
        if (done) return
        val now = System.currentTimeMillis()
        val rms = rmsOf(buf, len)
        val voiced = rms > max(noise * 3.0, 450.0)

        if (!heardSpeech) {
            preRoll.addLast(buf.copyOf(len))
            while (preRoll.size > 20) preRoll.removeFirst()          // keep ~400 ms before speech
            if (voiced) {
                voicedRun++
                if (voicedRun >= 3) {
                    heardSpeech = true
                    lastVoiceAt = now
                    preRoll.forEach { pcm.write(it, 0, it.size) }
                    preRoll.clear()
                }
            } else {
                voicedRun = 0
                noise = noise * 0.95 + rms * 0.05
            }
            if (!heardSpeech && now - startedAt > noSpeechMs) done = true
            return
        }

        pcm.write(buf, 0, len)
        if (voiced) lastVoiceAt = now
        if (now - lastVoiceAt > silenceMs || now - startedAt > maxMs) done = true
    }
}

/** Linear resample of mono PCM16 to 16 kHz. */
fun resampleTo16k(pcm: ByteArray, rate: Int): ByteArray {
    if (rate == SAMPLE_RATE) return pcm
    val inN = pcm.size / 2
    if (inN < 2) return ByteArray(0)
    val outN = (inN.toLong() * SAMPLE_RATE / rate).toInt()
    val out = ByteArray(outN * 2)
    val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
    val ob = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until outN) {
        val pos = i.toDouble() * rate / SAMPLE_RATE
        val i0 = pos.toInt().coerceAtMost(inN - 1)
        val i1 = (i0 + 1).coerceAtMost(inN - 1)
        val f = pos - i0
        val a = bb.getShort(i0 * 2).toInt()
        val b = bb.getShort(i1 * 2).toInt()
        ob.putShort(i * 2, (a + (b - a) * f).toInt().toShort())
    }
    return out
}

// ============================================================================
// In-built call recorder. Runs for the whole call, writes a WAV, and feeds turn capture.
// ============================================================================

class CallRecorder(private val outFile: File) {

    private val sources = listOf(
        MediaRecorder.AudioSource.VOICE_CALL to "voice_call",                 // both sides, only on phones that allow it
        MediaRecorder.AudioSource.VOICE_RECOGNITION to "voice_recognition",
        MediaRecorder.AudioSource.VOICE_COMMUNICATION to "voice_communication",
        MediaRecorder.AudioSource.MIC to "mic"
    )
    private var sourceIndex = -1
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var wav: WavFileWriter? = null
    private val bufferSize = max(
        AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2,
        8192
    )

    @Volatile private var running = false
    @Volatile private var switchRequested = false
    @Volatile var capture: Utterance? = null
    @Volatile var peak = 0                 // loudest sample since the current source was opened
    @Volatile var sourceName = "none"
    @Volatile var maxPeak = 0              // loudest sample over the whole call, any source
    val sourceLog = StringBuilder()        // what happened with each microphone source

    val isRunning: Boolean get() = running
    private val triedAllSources: Boolean get() = sourceIndex >= sources.size - 1

    @SuppressLint("MissingPermission")
    private fun openNext(): Boolean {
        record?.let {
            try { it.stop() } catch (e: Exception) { }
            it.release()
        }
        record = null
        while (sourceIndex < sources.size - 1) {
            sourceIndex++
            val (src, name) = sources[sourceIndex]
            try {
                val r = buildRecord(src)
                if (r.state != AudioRecord.STATE_INITIALIZED) {
                    r.release()
                    sourceLog.append("$name: blocked. ")
                    continue
                }
                r.startRecording()
                if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    r.release()
                    sourceLog.append("$name: would not start. ")
                    continue
                }
                record = r
                sourceName = name
                peak = 0
                sourceLog.append("$name: opened. ")
                return true
            } catch (e: Throwable) {
                // not allowed on this phone: try the next source
                sourceLog.append("$name: error. ")
            }
        }
        return false
    }

    /**
     * On Android 11+ ask for a capture that is NOT privacy-sensitive, so it can run alongside the
     * phone app's own use of the microphone. Falls back to the plain constructor.
     */
    @SuppressLint("MissingPermission")
    private fun buildRecord(src: Int): AudioRecord {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                return AudioRecord.Builder()
                    .setAudioSource(src)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setPrivacySensitive(false)
                    .build()
            } catch (e: Throwable) {
                // fall through to the plain constructor
            }
        }
        return AudioRecord(src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
    }

    fun start(): Boolean {
        if (running) return true
        openNext()                                     // may fail: then only the assistant's side is recorded
        try {
            wav = WavFileWriter(outFile)
        } catch (e: Exception) {
            record?.let { try { it.stop() } catch (x: Exception) { }; it.release() }
            record = null
            return false
        }
        running = true
        thread = Thread { loop() }.apply {
            name = "equal-call-recorder"
            start()
        }
        return true
    }

    // ---- the assistant's own voice, mixed into the recording (the phone may block the microphone)
    private val aiLock = Any()
    private var aiPcm = ByteArray(0)
    private var aiPos = 0
    @Volatile var aiMixedBytes = 0

    /** Queue 16 kHz mono PCM16 of the assistant speaking; it is mixed in as the recording runs. */
    fun addAiAudio(pcm16k: ByteArray) = synchronized(aiLock) {
        val remaining = aiPcm.size - aiPos
        val merged = ByteArray(remaining + pcm16k.size)
        System.arraycopy(aiPcm, aiPos, merged, 0, remaining)
        System.arraycopy(pcm16k, 0, merged, remaining, pcm16k.size)
        aiPcm = merged
        aiPos = 0
    }

    /** Returns the frame to store in the file: the mic, plus the assistant's voice when the mic didn't hear it. */
    private fun frameForFile(buf: ByteArray, n: Int, micPeak: Int): ByteArray {
        val out = buf.copyOf(n)
        synchronized(aiLock) {
            val avail = minOf(n, aiPcm.size - aiPos) and 1.inv()
            if (avail > 0) {
                if (micPeak < 600) {                   // mic is silent or faint: add our own voice
                    var i = 0
                    while (i < avail) {
                        val m = (out[i].toInt() and 0xFF) or (out[i + 1].toInt() shl 8)
                        val a = (aiPcm[aiPos + i].toInt() and 0xFF) or (aiPcm[aiPos + i + 1].toInt() shl 8)
                        val mixed = (m + a).coerceIn(-32768, 32767)
                        out[i] = (mixed and 0xFF).toByte()
                        out[i + 1] = ((mixed shr 8) and 0xFF).toByte()
                        i += 2
                    }
                    aiMixedBytes += avail
                }
                aiPos += avail
            }
        }
        return out
    }

    private fun loop() {
        val buf = ByteArray(640)                       // 20 ms
        var errors = 0
        while (running) {
            if (switchRequested) {
                switchRequested = false
                sourceLog.append("(silent) ")
                val previous = sourceIndex
                if (!openNext()) {
                    sourceIndex = previous - 1         // fall back to the source we had
                    openNext()
                }
            }
            val r = record
            val n = if (r == null) {
                java.util.Arrays.fill(buf, 0)          // no microphone: keep time with silence
                try { Thread.sleep(20) } catch (e: InterruptedException) { }
                buf.size
            } else {
                try { r.read(buf, 0, buf.size) } catch (e: Exception) { -1 }
            }
            if (n > 0) {
                errors = 0
                val p = peakOf(buf, n)
                if (p > peak) peak = p
                if (p > maxPeak) maxPeak = p
                val out = frameForFile(buf, n, p)
                wav?.let { w -> if (w.bytes < MAX_WAV_BYTES) w.write(out, n) }
                capture?.onFrame(buf, n)               // the caller-detector hears the mic only, never our own voice
            } else {
                errors++
                if (errors > 100) break
                try { Thread.sleep(20) } catch (e: InterruptedException) { }
            }
        }
        running = false
        record?.let {
            try { it.stop() } catch (e: Exception) { }
            it.release()
        }
        record = null
    }

    /** Asks the recorder thread to move to the next audio source (used when the mic looks blocked). */
    fun nextSource(): Boolean {
        if (!running || triedAllSources) return false
        switchRequested = true
        return true
    }

    /**
     * Waits for one person-turn. Returns the PCM, or null if nobody spoke
     * (or the sound was shorter than ~250 ms).
     */
    suspend fun captureUtterance(maxMs: Long, silenceMs: Long, noSpeechMs: Long): ByteArray? {
        val u = Utterance(maxMs, silenceMs, noSpeechMs)
        capture = u
        try {
            while (!u.done && running) delay(40)
        } finally {
            capture = null
        }
        val bytes = u.pcm.toByteArray()
        return if (u.heardSpeech && bytes.size >= 8_000) bytes else null
    }

    fun stop() {
        running = false
        try { thread?.join(1_000) } catch (e: Exception) { }
        wav?.close()
        wav = null
    }
}

// ============================================================================
// Speakerphone + volume while the AI is talking into the call
// ============================================================================

@Suppress("DEPRECATION")
class CallAudioRoute(ctx: Context) {
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var oldSpeaker = false
    private var oldVolume = -1
    private var oldAlarm = -1

    fun enable() {
        try {
            oldAlarm = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, (am.getStreamMaxVolume(AudioManager.STREAM_ALARM) * AppSettings.callVoiceLevel).toInt().coerceAtLeast(1), 0)
        } catch (e: Exception) { }
        try {
            oldSpeaker = am.isSpeakerphoneOn
            oldVolume = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            am.setStreamVolume(
                AudioManager.STREAM_VOICE_CALL,
                am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
                0
            )
        } catch (e: Exception) { }
        applySpeaker()
    }

    private fun applySpeaker() {
        if (!AppSettings.speakerOn) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val speaker = am.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) am.setCommunicationDevice(speaker) else am.isSpeakerphoneOn = true
            } else {
                am.isSpeakerphoneOn = true
            }
        } catch (e: Exception) {
            AssistantBridge.log("Could not switch to speaker: ${e.localizedMessage}")
        }
    }

    private fun speakerIsOn(): Boolean = try {
        val comm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        comm || am.isSpeakerphoneOn
    } catch (e: Exception) { false }

    /**
     * Call again right after the other side answers: many phones put the call back on the earpiece at
     * that moment. Tries a few times; if the speaker is still off, presses "Speaker" on the call screen
     * (accessibility service) unless that button already shows as on.
     */
    suspend fun ensureSpeaker() {
        if (!AppSettings.speakerOn) return
        var pressed = false
        for (attempt in 1..3) {
            applySpeaker()
            kotlinx.coroutines.delay(400)
            if (speakerIsOn()) {
                AssistantBridge.log(if (pressed) "Speaker is on (pressed Speaker in the phone app)." else "Speaker is on.")
                return
            }
            val svc = AssistantBridge.accessibilityService
            if (svc != null) {
                when (svc.pressSpeakerIfOff()) {
                    1 -> { pressed = true; kotlinx.coroutines.delay(700); if (speakerIsOn()) { AssistantBridge.log("Speaker is on (pressed Speaker in the phone app)."); return } }
                    0 -> { AssistantBridge.log("The phone app shows Speaker as on."); return }
                }
            }
            kotlinx.coroutines.delay(700)
        }
        AssistantBridge.log(
            "Could not confirm the speaker is on. The assistant's voice still plays on the loud alarm channel, which " +
                "uses the loudspeaker anyway. Call screen shows: " +
                (AssistantBridge.accessibilityService?.screenSummary(300) ?: "(accessibility off)")
        )
    }

    // (see PhoneRecorder below for the phone's own call recorder)
    fun restore() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
            else am.isSpeakerphoneOn = oldSpeaker
        } catch (e: Exception) { }
        try {
            if (oldVolume >= 0) am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, oldVolume, 0)
            if (oldAlarm >= 0) am.setStreamVolume(AudioManager.STREAM_ALARM, oldAlarm, 0)
        } catch (e: Exception) { }
    }
}
