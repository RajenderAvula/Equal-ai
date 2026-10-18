package com.equalai.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.provider.ContactsContract
import android.telecom.PhoneAccountHandle
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.telecom.TelecomManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

object ActionExecutor {

    /** Starts an activity even from the background: prefers the accessibility service context. */
    private fun launch(ctx: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val starter: Context = AssistantBridge.accessibilityService ?: ctx
        try {
            starter.startActivity(intent)
        } catch (e: Exception) {
            AssistantBridge.log("Could not launch: ${e.localizedMessage}")
        }
    }

    fun resolveCallerName(context: Context, number: String): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return number
        }
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            val cursor = context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            cursor?.use { if (it.moveToFirst()) it.getString(0) else number } ?: number
        } catch (e: Exception) {
            number
        }
    }

    fun resolveContactNumber(context: Context, query: String): String {
        val clean = query.trim()
        if (clean.any { it.isDigit() } && clean.length >= 6) return clean
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return clean
        }
        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$clean%"),
                null
            )
            cursor?.use { if (it.moveToFirst()) it.getString(0) else clean } ?: clean
        } catch (e: Exception) {
            clean
        }
    }

    /** Digits only, with Indian default country code for 10-digit numbers. */
    fun toE164Digits(raw: String): String {
        var d = raw.filter { it.isDigit() }
        if (d.length == 11 && d.startsWith("0")) d = d.drop(1)
        if (d.length == 10) d = "91$d"
        return d
    }

    fun toE164Plus(raw: String): String = "+" + toE164Digits(raw)

    fun openAppByPackageName(context: Context, pkgName: String): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkgName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        launch(context, launchIntent)
        return true
    }

    /** Opens the exact screen the notification points to (e.g. the chat); falls back to the app. */
    fun openNotification(context: Context, n: AppNotificationItem): Boolean {
        val pi = n.contentIntent
        if (pi != null) {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    val opts = android.app.ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                    pi.send(context, 0, null, null, null, null, opts.toBundle())
                } else {
                    pi.send()
                }
                return true
            } catch (e: Exception) {
                AssistantBridge.log("Notification would not open directly, opening the app instead")
            }
        }
        return openAppByPackageName(context, n.packageName)
    }

    fun openAppByName(context: Context, appName: String): Boolean {
        val clean = appName.lowercase().trim()
        if (clean.isEmpty()) return false

        val directMap = mapOf(
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "instagram" to "com.instagram.android",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "camera" to "com.android.camera"
        )

        val targetPkg = directMap[clean] ?: run {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            pm.queryIntentActivities(mainIntent, 0).firstOrNull {
                val label = it.loadLabel(pm).toString().lowercase()
                label.contains(clean) || (label.length > 2 && clean.contains(label))
            }?.activityInfo?.packageName
        }
        return targetPkg != null && openAppByPackageName(context, targetPkg)
    }

    // ---- SIM selection (0 = SIM 1, 1 = SIM 2) ---------------------------------------------------

    @Suppress("MissingPermission")
    fun subscriptionIdFor(context: Context, slot: Int): Int? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val subs = sm.activeSubscriptionInfoList.orEmpty().sortedBy { it.simSlotIndex }
            (subs.firstOrNull { it.simSlotIndex == slot } ?: subs.getOrNull(slot))?.subscriptionId
        } catch (e: Exception) {
            null
        }
    }

    @Suppress("MissingPermission")
    fun phoneAccountFor(context: Context, slot: Int): PhoneAccountHandle? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val accounts = tm.callCapablePhoneAccounts
            val subId = subscriptionIdFor(context, slot)
            if (subId != null) {
                accounts.firstOrNull { h ->
                    h.id == subId.toString() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && try {
                        (context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
                            .createForPhoneAccountHandle(h)?.subscriptionId == subId
                    } catch (e: Exception) { false })
                } ?: accounts.getOrNull(slot)
            } else {
                accounts.getOrNull(slot)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun makePhoneCall(context: Context, target: String, simSlot: Int = AppSettings.defaultSim) {
        val number = resolveContactNumber(context, target)
        val account = phoneAccountFor(context, simSlot)
        if (account == null) AssistantBridge.log("SIM ${simSlot + 1} not found: using the phone's default SIM")
        val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        if (hasPerm) {
            // placeCall needs no screen and no tap, so it works from a background alarm
            try {
                val extras = Bundle()
                if (account != null) extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account)
                (context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager)
                    .placeCall(Uri.fromParts("tel", number, null), extras)
                return
            } catch (e: Exception) {
                AssistantBridge.log("Direct call failed (${e.localizedMessage}), trying the dialer")
            }
        } else {
            AssistantBridge.log("Phone permission missing: the dialer will open. Allow Phone in app settings.")
        }
        val action = if (hasPerm) Intent.ACTION_CALL else Intent.ACTION_DIAL
        launch(context, Intent(action, Uri.parse("tel:" + Uri.encode(number))).apply {
            if (account != null) putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account)
        })
    }

    @Suppress("DEPRECATION")
    fun sendSms(context: Context, target: String, message: String, simSlot: Int = AppSettings.defaultSim) {
        val number = resolveContactNumber(context, target)
        try {
            val subId = subscriptionIdFor(context, simSlot)
            val sms = if (subId != null) {
                SmsManager.getSmsManagerForSubscriptionId(subId)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }
            sms.sendMultipartTextMessage(number, null, sms.divideMessage(message), null, null)
            AssistantBridge.log("SMS sent to $number")
        } catch (e: Exception) {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).apply {
                putExtra("sms_body", message)
            }
            launch(context, intent)
        }
    }

    fun sendWhatsApp(context: Context, target: String, message: String, attachmentUri: String? = null) {
        val number = toE164Digits(resolveContactNumber(context, target))

        if (attachmentUri != null) {
            try {
                val uri = Uri.parse(attachmentUri)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = context.contentResolver.getType(uri) ?: "*/*"
                    setPackage("com.whatsapp")
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, message)
                    putExtra("jid", "$number@s.whatsapp.net")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                AssistantBridge.autoClickSendUntil = System.currentTimeMillis() + 10_000L
                launch(context, intent)
                return
            } catch (e: Exception) {
                AssistantBridge.log("Attachment share failed, sending text only")
            }
        }

        try {
            AssistantBridge.autoClickSendUntil = System.currentTimeMillis() + 8_000L
            val encoded = URLEncoder.encode(message, "UTF-8").replace("+", "%20")
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$number&text=$encoded")
            launch(context, Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.whatsapp") })
        } catch (e: Exception) {
            openAppByName(context, "whatsapp")
        }
    }

    /** Scheduled call: place it, then speak the custom text as soon as the other person answers. */
    private fun callAndSpeakOnDevice(context: Context, task: ScheduledActionItem) {
        OutgoingCall.arm(OutPlan(task, resolveContactNumber(context, task.target)))
        makePhoneCall(context, task.target, task.simSlot)
    }

    suspend fun executeTask(ctx: Context, t: ScheduledActionItem) {
        AssistantBridge.log("Running scheduled ${t.actionType} -> ${t.target}")
        when (t.actionType) {
            "SMS" -> sendSms(ctx, t.target, t.message, t.simSlot)
            "WHATSAPP" -> withContext(Dispatchers.Main) { sendWhatsApp(ctx, t.target, t.message, t.attachmentUri) }
            "CALL_SPEAK" -> withContext(Dispatchers.Main) { callAndSpeakOnDevice(ctx, t) }
            "OPEN_APP" -> withContext(Dispatchers.Main) {
                if (!openAppByPackageName(ctx, t.target)) AssistantBridge.log("Could not open ${t.message.ifBlank { t.target }}")
            }
        }
    }
}
