package com.equalai.assistant

import android.app.Application
import android.app.Notification
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

// ============================================================================
// DATA MODELS
// ============================================================================

data class AppNotificationItem(
    val key: String,
    val packageName: String,
    val appName: String,
    val sender: String,
    val message: String,
    val timestamp: String,
    val replyAction: Notification.Action? = null,
    val contentIntent: android.app.PendingIntent? = null
)

private val idCounter = AtomicInteger(((System.currentTimeMillis() / 1000) % 1_000_000_000).toInt())
fun newTaskId(): Int = idCounter.incrementAndGet()

data class ScheduledActionItem(
    val id: Int,
    val actionType: String,          // WHATSAPP | SMS | CALL_SPEAK
    val target: String,
    val message: String,
    val triggerAt: Long,
    val attachmentUri: String? = null
) {
    val timeLabel: String
        get() = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(triggerAt))

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", actionType)
        .put("target", target)
        .put("message", message)
        .put("at", triggerAt)
        .put("att", attachmentUri ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = ScheduledActionItem(
            id = o.getInt("id"),
            actionType = o.getString("type"),
            target = o.getString("target"),
            message = o.optString("message", ""),
            triggerAt = o.getLong("at"),
            attachmentUri = if (o.isNull("att")) null else o.getString("att")
        )
    }
}

data class CallRecord(
    val id: String,
    val number: String,
    val caller: String,
    val summary: String,
    val transcript: String,
    val startedAt: Long,
    val updatedAt: Long,
    val durationSec: Int = 0,
    val recordingFile: String? = null,   // file name inside the app's recordings folder
    val state: String = "DONE",          // ANSWERING | SUMMARIZING | PENDING (waiting for internet) | DONE
    val audioOk: Boolean = true,         // false when the phone blocked the microphone during the call
    val diag: String = ""               // which microphone sources were tried (shown when audioOk is false)
) {
    val hasRecording: Boolean get() = recordingFile != null
    val timeLabel: String
        get() = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(startedAt))
}

data class PendingAction(
    val description: String,
    val expiresAt: Long,
    val run: suspend () -> Unit
)

// ============================================================================
// GLOBAL STATE
// ============================================================================

object AssistantBridge {
    val notifications = mutableStateListOf<AppNotificationItem>()
    val scheduledTasks = mutableStateListOf<ScheduledActionItem>()
    val callLogs = mutableStateListOf<CallRecord>()
    val commandLogs = mutableStateListOf<String>()

    var readNotificationsAloud by mutableStateOf(true)
    var isContinuousListeningEnabled by mutableStateOf(true)
    var offlineMode by mutableStateOf(false)
    var wakeWordRequired by mutableStateOf(true)

    @Volatile var latestNotification: AppNotificationItem? = null
    @Volatile var forceNextCommand: Boolean = false
    @Volatile var followUpUntil: Long = 0L
    @Volatile var pendingAction: PendingAction? = null
    @Volatile var autoClickSendUntil: Long = 0L
    @Volatile var callInProgress: Boolean = false

    var promptInTextBoxCallback: ((String) -> Unit)? = null

    var notificationService: EqualNotificationService? = null
    var foregroundService: EqualForegroundService? = null
    var accessibilityService: EqualAccessibilityService? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    fun log(msg: String) {
        val time = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date())
        onMain {
            commandLogs.add(0, "[$time] $msg")
            if (commandLogs.size > 200) commandLogs.removeAt(commandLogs.lastIndex)
        }
    }

    fun upsertCall(c: CallRecord) {
        onMain {
            val list = callLogs.toMutableList()
            val i = list.indexOfFirst { it.id == c.id }
            if (i >= 0) list[i] = c else list.add(c)
            list.sortByDescending { it.startedAt }
            callLogs.clear()
            callLogs.addAll(list)
        }
    }
}

// ============================================================================
// ENCRYPTED SETTINGS (API key, backend URL/token, toggles) + TASK STORE
// ============================================================================

object AppSettings {
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        val app = ctx.applicationContext
        prefs = try {
            create(app)
        } catch (e: Exception) {
            // Keystore data can become unreadable after a restore; start fresh rather than crash.
            app.deleteSharedPreferences("equal_secure")
            create(app)
        }
    }

    private fun create(app: Context): SharedPreferences {
        val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            app,
            "equal_secure",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    val store: SharedPreferences get() = prefs

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(v) { prefs.edit().putString("api_key", v.trim()).apply() }

    var modelName: String
        get() = prefs.getString("model_name", "gemini-2.5-flash") ?: "gemini-2.5-flash"
        set(v) { prefs.edit().putString("model_name", v.trim()).apply() }

    var offlineMode: Boolean
        get() = prefs.getBoolean("offline_mode", false)
        set(v) { prefs.edit().putBoolean("offline_mode", v).apply() }

    var wakeWordRequired: Boolean
        get() = prefs.getBoolean("wake_word", true)
        set(v) { prefs.edit().putBoolean("wake_word", v).apply() }

    var autoAnswer: Boolean
        get() = prefs.getBoolean("auto_answer", false)
        set(v) { prefs.edit().putBoolean("auto_answer", v).apply() }

    var ringDelaySec: Int
        get() = prefs.getInt("ring_delay", 15)
        set(v) { prefs.edit().putInt("ring_delay", v.coerceIn(5, 60)).apply() }

    var ownerName: String
        get() = prefs.getString("owner_name", "") ?: ""
        set(v) { prefs.edit().putString("owner_name", v.trim()).apply() }

    /** Text-to-speech speed: 1.0 is the phone's normal voice, lower is slower. */
    var speechRate: Float
        get() = prefs.getFloat("speech_rate_v2", 0.72f)
        set(v) { prefs.edit().putFloat("speech_rate_v2", v.coerceIn(0.4f, 1.2f)).apply() }

    /** Custom text the assistant tells or asks every caller, on top of its normal questions. */
    var callerNote: String
        get() = prefs.getString("caller_note", "") ?: ""
        set(v) { prefs.edit().putString("caller_note", v.trim()).apply() }

    /** Scheduled call: seconds to wait before speaking when the dialer's call timer can't be read. */
    var outgoingWaitSec: Int
        get() = prefs.getInt("outgoing_wait", 15)
        set(v) { prefs.edit().putInt("outgoing_wait", v.coerceIn(5, 45)).apply() }

    /** true = the assistant talks on the loud alarm channel during calls (caller hears it best). */
    var loudVoice: Boolean
        get() = prefs.getBoolean("loud_voice", true)
        set(v) { prefs.edit().putBoolean("loud_voice", v).apply() }
}

object TaskStore {
    private const val KEY = "tasks"

    @Synchronized
    fun all(ctx: Context): List<ScheduledActionItem> {
        AppSettings.init(ctx)
        val raw = AppSettings.store.getString(KEY, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull {
            try { ScheduledActionItem.fromJson(arr.getJSONObject(it)) } catch (e: Exception) { null }
        }
    }

    @Synchronized
    private fun saveAll(ctx: Context, list: List<ScheduledActionItem>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        AppSettings.store.edit().putString(KEY, arr.toString()).apply()
    }

    fun get(ctx: Context, id: Int): ScheduledActionItem? = all(ctx).firstOrNull { it.id == id }
    fun add(ctx: Context, item: ScheduledActionItem) = saveAll(ctx, all(ctx) + item)
    fun remove(ctx: Context, id: Int) = saveAll(ctx, all(ctx).filter { it.id != id })
}

// ============================================================================
// APPLICATION
// ============================================================================

class EqualApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        AssistantBridge.offlineMode = AppSettings.offlineMode
        AssistantBridge.wakeWordRequired = AppSettings.wakeWordRequired
        AssistantBridge.scheduledTasks.addAll(TaskStore.all(this).sortedBy { it.triggerAt })
        CallStore.recoverInterrupted(this)
        CallStore.purge(this, 30)
        AssistantBridge.callLogs.addAll(CallStore.all(this))
    }
}
