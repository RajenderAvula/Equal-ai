package com.equalai.assistant

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EqualNotificationService : NotificationListenerService() {

    private var lastSignature = ""
    private var lastSignatureAt = 0L

    override fun onListenerConnected() {
        super.onListenerConnected()
        AssistantBridge.notificationService = this
        AssistantBridge.log("Notification reader active")
    }

    override fun onListenerDisconnected() {
        if (AssistantBridge.notificationService === this) AssistantBridge.notificationService = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.isOngoing) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName || pkg == "android" || pkg.contains("systemui")) return

        val notif = sbn.notification ?: return
        if ((notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = notif.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (title.isBlank() && text.isBlank()) return

        // Ignore duplicate re-posts of the same notification within a few seconds
        val sig = "${sbn.key}|$title|$text"
        val now = System.currentTimeMillis()
        if (sig == lastSignature && now - lastSignatureAt < 4000) return
        lastSignature = sig
        lastSignatureAt = now

        val appName = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg.substringAfterLast('.')
        }

        val replyAction = notif.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
        val time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())

        val item = AppNotificationItem(
            key = sbn.key,
            packageName = pkg,
            appName = appName,
            sender = title.ifBlank { appName },
            message = text,
            timestamp = time,
            replyAction = replyAction
        )

        AssistantBridge.onMain {
            AssistantBridge.latestNotification = item
            AssistantBridge.notifications.add(0, item)
            if (AssistantBridge.notifications.size > 100) {
                AssistantBridge.notifications.removeAt(AssistantBridge.notifications.lastIndex)
            }

            if (AssistantBridge.readNotificationsAloud) {
                val spoken = "Notification from $appName. ${item.sender} says: ${item.message}. What would you like to do next?"
                AssistantBridge.promptInTextBoxCallback?.invoke("From $appName: ${item.message} -> What next?")
                AssistantBridge.foregroundService?.speakBackground(spoken, listenAfter = true)
            }
        }
    }

    fun dismissLatest() {
        val target = AssistantBridge.latestNotification ?: AssistantBridge.notifications.firstOrNull() ?: return
        try { cancelNotification(target.key) } catch (e: Exception) { }
        AssistantBridge.onMain { AssistantBridge.notifications.remove(target) }
        AssistantBridge.log("Dismissed notification from ${target.sender}")
    }

    fun replyToLatest(replyText: String): Boolean {
        val target = AssistantBridge.latestNotification ?: AssistantBridge.notifications.firstOrNull()
        val action = target?.replyAction ?: return false
        val remoteInputs = action.remoteInputs ?: return false

        val intent = Intent()
        val bundle = Bundle()
        for (input in remoteInputs) bundle.putCharSequence(input.resultKey, replyText)
        RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
        return try {
            action.actionIntent.send(this, 0, intent)
            AssistantBridge.log("Replied to ${target.sender}: $replyText")
            true
        } catch (e: Exception) {
            false
        }
    }
}
