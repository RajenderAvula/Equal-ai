package com.equalai.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Call history kept on the phone: calls.json plus WAV recordings in filesDir/recordings. */
object CallStore {

    private fun file(ctx: Context) = File(ctx.filesDir, "calls.json")

    fun recordingsDir(ctx: Context): File = File(ctx.filesDir, "recordings").apply { mkdirs() }

    private fun toJson(c: CallRecord) = JSONObject()
        .put("id", c.id)
        .put("number", c.number)
        .put("caller", c.caller)
        .put("summary", c.summary)
        .put("transcript", c.transcript)
        .put("startedAt", c.startedAt)
        .put("updatedAt", c.updatedAt)
        .put("durationSec", c.durationSec)
        .put("recordingFile", c.recordingFile ?: JSONObject.NULL)
        .put("state", c.state)
        .put("audioOk", c.audioOk)

    private fun fromJson(o: JSONObject) = CallRecord(
        id = o.getString("id"),
        number = o.optString("number", ""),
        caller = o.optString("caller", ""),
        summary = o.optString("summary", ""),
        transcript = o.optString("transcript", ""),
        startedAt = o.optLong("startedAt"),
        updatedAt = o.optLong("updatedAt"),
        durationSec = o.optInt("durationSec"),
        recordingFile = if (o.isNull("recordingFile")) null else o.optString("recordingFile"),
        state = o.optString("state", "DONE"),
        audioOk = o.optBoolean("audioOk", true)
    )

    @Synchronized
    fun all(ctx: Context): List<CallRecord> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull {
                try { fromJson(arr.getJSONObject(it)) } catch (e: Exception) { null }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun writeAll(ctx: Context, list: List<CallRecord>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        val tmp = File(ctx.filesDir, "calls.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file(ctx))
    }

    @Synchronized
    fun upsert(ctx: Context, c: CallRecord) {
        val list = all(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == c.id }
        if (i >= 0) list[i] = c else list.add(c)
        writeAll(ctx, list.sortedByDescending { it.startedAt })
        AssistantBridge.upsertCall(c)
    }

    @Synchronized
    fun delete(ctx: Context, id: String) {
        val list = all(ctx)
        list.firstOrNull { it.id == id }?.recordingFile?.let { File(recordingsDir(ctx), it).delete() }
        writeAll(ctx, list.filter { it.id != id })
        AssistantBridge.onMain { AssistantBridge.callLogs.removeAll { it.id == id } }
    }

    /** Recordings older than [days] are deleted; the summary and transcript stay. */
    @Synchronized
    fun purge(ctx: Context, days: Int) {
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        var changed = false
        val out = all(ctx).map { c ->
            val name = c.recordingFile
            if (c.startedAt < cutoff && name != null) {
                File(recordingsDir(ctx), name).delete()
                changed = true
                c.copy(recordingFile = null)
            } else c
        }
        if (changed) writeAll(ctx, out)
    }

    /** If the app was killed mid-call or mid-summary, queue those calls for another attempt. */
    @Synchronized
    fun recoverInterrupted(ctx: Context) {
        val list = all(ctx)
        if (list.none { it.state == "ANSWERING" || it.state == "SUMMARIZING" }) return
        writeAll(ctx, list.map {
            if (it.state == "ANSWERING" || it.state == "SUMMARIZING") {
                it.copy(state = "PENDING", summary = "Waiting to summarize…")
            } else it
        })
    }
}
