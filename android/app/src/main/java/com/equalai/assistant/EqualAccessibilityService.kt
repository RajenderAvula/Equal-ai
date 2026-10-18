package com.equalai.assistant

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Sees every app's windows (no packageNames filter in accessibility_config.xml).
 * Screen text is only read on demand (voice command), never logged or sent anywhere automatically.
 * Password fields are always skipped.
 */
class EqualAccessibilityService : AccessibilityService() {

    @Volatile var currentPackage: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        AssistantBridge.accessibilityService = this
        AssistantBridge.log("Accessibility service connected")
    }

    override fun onDestroy() {
        if (AssistantBridge.accessibilityService === this) AssistantBridge.accessibilityService = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != "com.android.systemui") {
            currentPackage = pkg
        }

        // Auto-press Send only inside WhatsApp, only right after we opened a prefilled chat
        if (pkg == "com.whatsapp" && System.currentTimeMillis() < AssistantBridge.autoClickSendUntil) {
            val root = rootInActiveWindow ?: return
            val nodes = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
                .ifEmpty { root.findAccessibilityNodeInfosByText("Send") }
            for (node in nodes) {
                if (clickNode(node)) {
                    AssistantBridge.autoClickSendUntil = 0L
                    AssistantBridge.log("Auto-pressed WhatsApp Send")
                    break
                }
            }
        }
    }

    override fun onInterrupt() {}

    // ---- Reading ----------------------------------------------------------

    /** Visible text from every window on screen (all apps). */
    fun readAllWindows(maxChars: Int = 6000, includePackages: Boolean = true): String {
        val sb = StringBuilder()
        val roots: List<AccessibilityNodeInfo> = try {
            windows.mapNotNull { it.root }
        } catch (e: Exception) {
            emptyList()
        }
        val list = if (roots.isNotEmpty()) roots else listOfNotNull(rootInActiveWindow)
        for (root in list) {
            val pkg = root.packageName?.toString() ?: "?"
            if (pkg == packageName) continue
            val start = sb.length
            if (includePackages) sb.append("[").append(pkg).append("]\n")
            val before = sb.length
            collect(root, sb, 0, maxChars)
            if (sb.length == before) sb.setLength(start)   // nothing readable in this window
            if (sb.length >= maxChars) break
        }
        return sb.toString().take(maxChars).trim()
    }

    private fun collect(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int, max: Int) {
        if (node == null || sb.length >= max || depth > 40) return
        if (!node.isPassword && node.isVisibleToUser) {
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            val s = if (t.isNotEmpty()) t else d
            if (s.isNotEmpty()) sb.append(s).append('\n')
        }
        for (i in 0 until node.childCount) collect(node.getChild(i), sb, depth + 1, max)
    }

    // ---- Acting -------------------------------------------------------------

    private fun clickNode(start: AccessibilityNodeInfo?): Boolean {
        var n = start
        var hops = 0
        while (n != null && hops < 6) {
            if (n.isClickable && n.isEnabled) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
            hops++
        }
        return false
    }

    fun clickByText(text: String): Boolean {
        val needle = text.trim()
        if (needle.isEmpty()) return false
        val roots: List<AccessibilityNodeInfo> = try { windows.mapNotNull { it.root } } catch (e: Exception) { emptyList() }
        for (root in if (roots.isNotEmpty()) roots else listOfNotNull(rootInActiveWindow)) {
            if (root.packageName?.toString() == packageName) continue
            for (node in root.findAccessibilityNodeInfosByText(needle)) {
                if (clickNode(node)) return true
            }
        }
        return false
    }

    /** True if any other app's window shows this text (or content description). */
    fun hasText(text: String): Boolean {
        val needle = text.trim()
        if (needle.isEmpty()) return false
        val roots: List<AccessibilityNodeInfo> = try { windows.mapNotNull { it.root } } catch (e: Exception) { emptyList() }
        for (root in if (roots.isNotEmpty()) roots else listOfNotNull(rootInActiveWindow)) {
            if (root.packageName?.toString() == packageName) continue
            if (root.findAccessibilityNodeInfosByText(needle).isNotEmpty()) return true
        }
        return false
    }

    fun typeText(text: String): Boolean {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}
