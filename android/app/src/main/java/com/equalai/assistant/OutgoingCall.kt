package com.equalai.assistant

import android.content.Context
import kotlinx.coroutines.delay

/** One scheduled call about to be dialed. */
data class OutPlan(val task: ScheduledActionItem, val number: String)

/**
 * Scheduled "call and speak". The call is placed with no confirmation. When the phone goes off-hook,
 * CallAnswerer runs the outgoing conversation (voice clip, custom text, Gemini).
 *
 * Android does not tell apps when the other side answers, so with the Equal AI accessibility service on we
 * watch the phone app's screen for the call timer (00:01...). Without it we assume the call was answered
 * after a fixed wait (Setup) if it is still connected.
 */
object OutgoingCall {

    private const val ARM_MS = 90_000L
    private const val WATCH_MS = 60_000L

    @Volatile private var plan: OutPlan? = null
    @Volatile private var armedAt = 0L

    private val timer = Regex("""^\s*\d{1,2}:\d{2}\s*$""")
    private val dialerHints = listOf("incallui", "dialer", "telecom", "phone", "call")

    fun arm(p: OutPlan) {
        plan = p
        armedAt = System.currentTimeMillis()
    }

    /** The phone went off-hook (our outgoing call started dialing). Main thread. */
    fun onOffHook(ctx: Context) {
        val p = plan ?: return
        plan = null
        if (System.currentTimeMillis() - armedAt > ARM_MS) return
        CallAnswerer.startOutbound(ctx, p)
    }

    fun onIdle() {
        plan = null
    }

    /**
     * Suspends until the other person has answered. If the call drops first, the coroutine is cancelled
     * by CallAnswerer, so this never returns for an unanswered call.
     */
    suspend fun waitForAnswer() {
        val a11y = AssistantBridge.accessibilityService
        if (a11y == null) {
            delay(AppSettings.outgoingWaitSec * 1000L)
            return
        }
        val until = System.currentTimeMillis() + WATCH_MS
        while (System.currentTimeMillis() < until) {
            if (timerVisible(a11y.readAllWindows(maxChars = 4000, includePackages = true))) return
            delay(500)
        }
        // still connected after a minute but no timer seen: the dialer is unreadable, assume answered
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
