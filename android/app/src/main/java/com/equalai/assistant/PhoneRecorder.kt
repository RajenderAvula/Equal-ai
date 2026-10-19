package com.equalai.assistant

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Switches the phone's OWN call recorder on or off while a call is going on.
 *
 * Android has no public switch for this, so (with the accessibility service) Equal presses the
 * Record / Stop recording button in the phone app, exactly like you would. This only works if your
 * phone's dialer has a call recorder and shows that button during calls.
 */
object PhoneRecorder {
    private val handler = Handler(Looper.getMainLooper())
    private val followUp = Handler(Looper.getMainLooper())     // second step of the menu path; kept apart so it isn't cancelled
    private val menuLabels = listOf("More options", "More", "Options", "More actions")

    private val startLabels = listOf("Start recording", "Record call", "Record")
    private val stopLabels = listOf("Stop recording", "Stop call recording", "Stop recorder", "Stop record", "Recording")

    /** Call when a call becomes active. Tries a few times because the call screen takes a moment to appear. */
    fun onCallActive() {
        val mode = AppSettings.phoneRecorder
        if (mode == 0) return
        val delays = longArrayOf(1500, 3500, 7000)
        for ((i, delay) in delays.withIndex()) {
            handler.postDelayed({
                if (apply(mode)) handler.removeCallbacksAndMessages(null)
                else if (i == delays.lastIndex) {
                    AssistantBridge.callNote(
                        "Phone recorder: Record button not found. Call screen shows: " +
                            (AssistantBridge.accessibilityService?.screenSummary() ?: "(accessibility off)")
                    )
                }
            }, delay)
        }
    }

    fun cancel() {
        handler.removeCallbacksAndMessages(null)
        followUp.removeCallbacksAndMessages(null)
    }

    /** Returns true when nothing more needs doing (already in the wanted state, or button pressed). */
    private fun apply(mode: Int): Boolean {
        val svc = AssistantBridge.accessibilityService
        if (svc == null) {
            AssistantBridge.callNote("Phone recorder: accessibility is off, so Equal can't press the recorder button.")
            return true
        }
        val recording = stopLabels.any { svc.hasText(it) }
        return when {
            mode == 1 && recording -> { AssistantBridge.callNote("Phone recorder: already recording."); true }
            mode == 1 -> {
                var ok = startLabels.any { svc.clickByText(it) }
                if (ok) {
                    AssistantBridge.callNote("Phone recorder: pressed Record.")
                } else if (menuLabels.any { svc.clickByText(it) }) {
                    // Samsung: Record sits in the three-dot menu at the top right of the call screen
                    ok = true
                    AssistantBridge.callNote("Phone recorder: opened the menu, choosing Record.")
                    followUp.postDelayed({
                        val s2 = AssistantBridge.accessibilityService
                        val pressed = s2 != null && startLabels.any { s2.clickByText(it) }
                        AssistantBridge.callNote(
                            if (pressed) "Phone recorder: pressed Record in the menu."
                            else "Phone recorder: Record wasn't found in the menu. Start it by hand once and tell me what it says."
                        )
                    }, 800)
                } else {
                    AssistantBridge.callNote(
                        "Phone recorder: no Record button found on screen. Equal can see: " +
                            svc.readAllWindows(200).replace("\n", " | ").ifBlank { "nothing (call screen hidden, or phone locked?)" }
                    )
                }
                ok
            }
            mode == 2 && recording -> {
                val ok = stopLabels.any { svc.clickByText(it) }
                if (ok) AssistantBridge.callNote("Phone recorder: pressed Stop recording.")
                ok
            }
            else -> false   // OFF wanted and no recording seen yet: keep watching for the next tries
        }
    }

    /** Opens the phone app's call settings, where the recorder's automatic-recording switch usually is. */
    fun openPhoneSettings(context: Context): String {
        val tries = listOf(
            Intent("android.telecom.action.SHOW_CALL_SETTINGS"),
            Intent("android.telecom.action.CONFIGURE_PHONE_ACCOUNT"),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (i in tries) {
            try {
                context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return "Opened call settings. Look for \"Call recording\"."
            } catch (e: Exception) { }
        }
        return "Open your Phone app, then Settings, then Call recording."
    }
}
