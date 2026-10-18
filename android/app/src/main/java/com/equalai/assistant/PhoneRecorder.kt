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

    private val startLabels = listOf("Start recording", "Record call", "Record")
    private val stopLabels = listOf("Stop recording", "Stop call recording", "Stop recorder", "Stop record", "Recording")

    /** Call when a call becomes active. Tries a few times because the call screen takes a moment to appear. */
    fun onCallActive() {
        val mode = AppSettings.phoneRecorder
        if (mode == 0) return
        for (delay in longArrayOf(1500, 3500, 7000)) {
            handler.postDelayed({ if (apply(mode)) handler.removeCallbacksAndMessages(null) }, delay)
        }
    }

    fun cancel() = handler.removeCallbacksAndMessages(null)

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
                val ok = startLabels.any { svc.clickByText(it) }
                if (ok) AssistantBridge.callNote("Phone recorder: pressed Record.")
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
