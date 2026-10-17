package com.equalai.assistant

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Scheduled "call and speak": the call is placed with no confirmation, and as soon as the other person
 * picks up, the assistant speaks the custom text and then hangs up.
 *
 * Android does not tell apps when the other side answers, so when the Equal AI accessibility service is on
 * we watch the phone app's screen for the call timer (00:01...). Without it we wait a fixed number of
 * seconds (Setup).
 */
object OutgoingCall {

    private const val ARM_MS = 90_000L
    private const val WATCH_MS = 40_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var message: String? = null
    @Volatile private var armedAt = 0L
    private var job: Job? = null

    private val timer = Regex("""^\s*\d{1,2}:\d{2}\s*$""")
    private val dialerHints = listOf("incallui", "dialer", "telecom", "phone", "call")

    fun arm(text: String) {
        message = text
        armedAt = System.currentTimeMillis()
    }

    /** The phone went off-hook (our outgoing call started dialing). */
    fun onOffHook(ctx: Context) {
        val m = message ?: return
        message = null
        if (System.currentTimeMillis() - armedAt > ARM_MS) return
        job?.cancel()
        job = scope.launch {
            try {
                run(ctx, m)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AssistantBridge.log("Scheduled call error: ${e.localizedMessage}")
            }
        }
    }

    fun onIdle() {
        message = null
        job?.cancel()
        job = null
    }

    private suspend fun run(ctx: Context, text: String) {
        val svc = AssistantBridge.foregroundService
        if (svc == null) {
            AssistantBridge.log("Scheduled call connected, but the voice service isn't running. Open Equal AI once.")
            return
        }
        AssistantBridge.callInProgress = true
        val route = CallAudioRoute(ctx)
        try {
            withContext(Dispatchers.Main) { svc.enterCallMode() }
            route.enable()

            val seen = waitForAnswer()
            AssistantBridge.log(
                if (seen) "Scheduled call answered. Speaking your message."
                else "Speaking your message on the scheduled call."
            )
            delay(900)
            svc.speakIntoCall(text, null)
            delay(2_500)
            CallAnswerer.hangUp(ctx)
        } finally {
            withContext(NonCancellable) {
                svc.stopSpeakingNow()
                route.restore()
                AssistantBridge.callInProgress = false
                withContext(Dispatchers.Main) { svc.exitCallMode() }
            }
        }
    }

    /** True when the call timer was seen. False when we fell back to waiting. */
    private suspend fun waitForAnswer(): Boolean {
        val a11y = AssistantBridge.accessibilityService
        if (a11y == null) {
            delay(AppSettings.outgoingWaitSec * 1000L)
            return false
        }
        val until = System.currentTimeMillis() + WATCH_MS
        while (System.currentTimeMillis() < until) {
            if (timerVisible(a11y.readAllWindows(maxChars = 4000, includePackages = true))) return true
            delay(500)
        }
        return false
    }

    /** Looks for 00:03-style text, but only inside the phone/dialer app (not the status-bar clock). */
    private fun timerVisible(screen: String): Boolean {
        var pkg = ""
        for (line in screen.lineSequence()) {
            if (line.startsWith("[") && line.endsWith("]")) {
                pkg = line.substring(1, line.length - 1).lowercase()
                continue
            }
            if (pkg.contains("systemui") || dialerHints.none { pkg.contains(it) }) continue
            if (timer.matches(line)) {
                val mins = line.trim().substringBefore(':').toIntOrNull() ?: 99
                if (mins < 60) return true
            }
        }
        return false
    }
}
