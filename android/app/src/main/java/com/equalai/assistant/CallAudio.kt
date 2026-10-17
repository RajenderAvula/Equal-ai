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
                val r = AudioRecord(
                    src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
                )
                if (r.state != AudioRecord.STATE_INITIALIZED) {
                    r.release()
                    continue
                }
                r.startRecording()
                if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    r.release()
                    continue
                }
                record = r
                sourceName = name
                peak = 0
                return true
            } catch (e: Throwable) {
                // not allowed on this phone: try the next source
            }
        }
        return false
    }

    fun start(): Boolean {
        if (running) return true
        if (!openNext()) return false
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

    private fun loop() {
        val buf = ByteArray(640)                       // 20 ms
        var errors = 0
        while (running) {
            if (switchRequested) {
                switchRequested = false
                val previous = sourceIndex
                if (!openNext()) {
                    sourceIndex = previous - 1         // fall back to the source we had
                    if (!openNext()) { running = false; break }
                }
            }
            val r = record ?: break
            val n = try { r.read(buf, 0, buf.size) } catch (e: Exception) { -1 }
            if (n > 0) {
                errors = 0
                wav?.let { w -> if (w.bytes < MAX_WAV_BYTES) w.write(buf, n) }
                val p = peakOf(buf, n)
                if (p > peak) peak = p
                capture?.onFrame(buf, n)
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

    fun enable() {
        try {
            oldSpeaker = am.isSpeakerphoneOn
            oldVolume = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            am.setStreamVolume(
                AudioManager.STREAM_VOICE_CALL,
                am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
                0
            )
        } catch (e: Exception) { }
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

    fun restore() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
            else am.isSpeakerphoneOn = oldSpeaker
        } catch (e: Exception) { }
        try {
            if (oldVolume >= 0) am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, oldVolume, 0)
        } catch (e: Exception) { }
    }
}
