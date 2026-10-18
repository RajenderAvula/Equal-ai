package com.equalai.assistant

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CallLog
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

data class LogEntry(val number: String, val type: Int, val date: Long, val durationSec: Int)

/** Reads the phone's call log. */
object CallLogReader {

    fun canRead(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    /** The newest call-log entry that started at or after [sinceMs]. */
    fun latest(ctx: Context, sinceMs: Long, outgoingOnly: Boolean = false): LogEntry? {
        if (!canRead(ctx)) return null
        return try {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                "${CallLog.Calls.DATE} >= ?",
                arrayOf((sinceMs - 8_000L).toString()),
                "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                var found: LogEntry? = null
                while (found == null && c.moveToNext()) {
                    val type = c.getInt(1)
                    if (outgoingOnly && type != CallLog.Calls.OUTGOING_TYPE) continue
                    found = LogEntry(c.getString(0).orEmpty(), type, c.getLong(2), c.getInt(3))
                }
                found
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Calls Equal did not handle (you answered, you dialed, or you missed them): adds them to Call history,
 * and when the phone's own call recording can be found, summarises it with Gemini.
 */
object CallLogSummarizer {

    private fun kind(type: Int) = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "Incoming call"
        CallLog.Calls.OUTGOING_TYPE -> "Outgoing call"
        CallLog.Calls.MISSED_TYPE -> "Missed call"
        CallLog.Calls.REJECTED_TYPE -> "Rejected call"
        else -> "Call"
    }

    private fun duration(sec: Int) = if (sec >= 60) "${sec / 60} min ${sec % 60} s" else "$sec s"

    suspend fun handle(ctx: Context, sinceMs: Long) {
        if (!AppSettings.logOtherCalls) return
        if (!CallLogReader.canRead(ctx)) {
            AssistantBridge.callNote("Call ended. Allow the Call log permission so Equal can list and summarize it.")
            return
        }
        delay(5_000)                                           // the call log is written a moment after hang-up
        var entry: LogEntry? = null
        repeat(4) {
            if (entry == null) {
                entry = CallLogReader.latest(ctx, sinceMs)
                if (entry == null) delay(2_500)
            }
        }
        val e = entry ?: run {
            AssistantBridge.callNote("A call ended but it isn't in the call log yet.")
            return
        }
        val id = "log_${e.date}_${e.number}"
        if (CallStore.all(ctx).any { it.id == id }) return

        val who = ActionExecutor.resolveCallerName(ctx, e.number).ifBlank { "Unknown" }
        val k = kind(e.type)
        val missed = e.type == CallLog.Calls.MISSED_TYPE || e.type == CallLog.Calls.REJECTED_TYPE
        val basic = if (missed) "$k from $who." else "$k with $who, lasted ${duration(e.durationSec)}."

        val found = if (!missed && e.durationSec >= 4) RecordingFinder.find(ctx, e.date, e.durationSec) else null
        var rec = CallRecord(
            id = id,
            number = e.number,
            caller = "$k: $who",
            summary = basic,
            transcript = "",
            startedAt = e.date,
            updatedAt = System.currentTimeMillis(),
            durationSec = e.durationSec
        )
        if (found != null) {
            rec = rec.copy(recordingFile = found.first, diag = "external:${found.second}", state = "SUMMARIZING", summary = "Summarizing…")
        } else if (!missed) {
            rec = rec.copy(
                summary = "$basic No recording was found, so there is no AI summary. Turn on your phone's call " +
                    "recording (and allow Equal to read audio files), or use \"Summarize a recording\"."
            )
        }
        CallStore.upsert(ctx, rec)
        AssistantBridge.callNote("$k logged: $who" + if (found != null) " (recording found, summarizing)" else "")
        if (found != null) CallSummarizer.process(ctx, rec, announce = false)
    }
}

/** Looks for the phone's own call recording (Samsung, Xiaomi, Oppo and others save them as audio files). */
object RecordingFinder {

    private val audioPerm: String
        get() = if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_AUDIO"
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun canRead(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, audioPerm) == PackageManager.PERMISSION_GRANTED

    /** Returns (file name in Equal's recordings folder, mime type), or null. */
    fun find(ctx: Context, startMs: Long, durationSec: Int): Pair<String, String>? {
        if (!canRead(ctx)) return null
        val endMs = startMs + durationSec * 1000L
        return try {
            val proj = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.DATA
            )
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                proj,
                "${MediaStore.Audio.Media.DATE_ADDED} >= ?",
                arrayOf((startMs / 1000 - 60).toString()),
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1).orEmpty()
                    val added = c.getLong(2) * 1000L
                    val path = (c.getString(4) ?: "").lowercase(Locale.ROOT)
                    val hay = name.lowercase(Locale.ROOT) + " " + path
                    val looksLikeCall = hay.contains("call") || hay.contains("record")
                    if (!looksLikeCall || added > endMs + 180_000L) continue
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                    val ext = name.substringAfterLast('.', "m4a").lowercase(Locale.ROOT)
                    val out = File(CallStore.recordingsDir(ctx), "ext_${System.currentTimeMillis()}.$ext")
                    val input = ctx.contentResolver.openInputStream(uri) ?: continue
                    input.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                    if (out.length() > 0) return@use out.name to normalizeMime(c.getString(3), ext)
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun normalizeMime(mime: String?, ext: String): String {
        val m = (mime ?: "").lowercase(Locale.ROOT)
        return when {
            ext == "mp3" || m == "audio/mpeg" || m == "audio/mp3" -> "audio/mp3"
            ext == "wav" || m.contains("wav") -> "audio/wav"
            ext == "ogg" || ext == "opus" || m.contains("ogg") -> "audio/ogg"
            ext == "flac" || m.contains("flac") -> "audio/flac"
            ext == "aac" || ext == "m4a" || m.contains("aac") || m.contains("mp4") -> "audio/aac"
            ext == "amr" || ext == "3gp" || m.contains("amr") || m.contains("3gpp") -> "audio/amr"
            m.isNotBlank() -> m
            else -> "audio/aac"
        }
    }
}

/** "Summarize a recording": pick any audio file (for example a call your phone recorded) and summarize it. */
object RecordingImport {
    suspend fun import(ctx: Context, uri: android.net.Uri): Boolean {
        return try {
            var display = "recording"
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) display = c.getString(0) ?: display
            }
            val ext = display.substringAfterLast('.', "m4a").lowercase(Locale.ROOT)
            val mime = RecordingFinder.normalizeMime(ctx.contentResolver.getType(uri), ext)
            val out = File(CallStore.recordingsDir(ctx), "imp_${System.currentTimeMillis()}.$ext")
            val input = ctx.contentResolver.openInputStream(uri) ?: return false
            input.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            if (out.length() == 0L) return false
            val now = System.currentTimeMillis()
            val rec = CallRecord(
                id = "imp_$now", number = "", caller = "Recording: $display", summary = "Summarizing…", transcript = "",
                startedAt = now, updatedAt = now, durationSec = 0, recordingFile = out.name,
                state = "SUMMARIZING", audioOk = true, diag = "external:$mime"
            )
            CallStore.upsert(ctx, rec)
            CallSummarizer.process(ctx, rec, announce = false)
            true
        } catch (e: Exception) {
            AssistantBridge.log("Import failed: ${e.localizedMessage}")
            false
        }
    }
}
