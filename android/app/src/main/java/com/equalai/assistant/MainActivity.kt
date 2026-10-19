package com.equalai.assistant

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private val Ink = Color(0xFF0F172A)
private val Green = Color(0xFF10B981)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Ink,
                    secondary = Green,
                    background = Color(0xFFF8FAFC)
                )
            ) {
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { startAssistantServiceIfPossible() }

                LaunchedEffect(Unit) {
                    val perms = requiredPermissions()
                    val missing = perms.filter {
                        ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isEmpty()) startAssistantServiceIfPossible() else launcher.launch(missing.toTypedArray())
                }

                EqualAssistantMainScreen()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The microphone service may only be started while the app is visible (Android 12+).
        startAssistantServiceIfPossible()
    }

    private fun startAssistantServiceIfPossible() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (AssistantBridge.foregroundService != null) return
        try {
            ContextCompat.startForegroundService(this, Intent(this, EqualForegroundService::class.java))
        } catch (e: Exception) {
            AssistantBridge.log("Could not start voice service: ${e.localizedMessage}")
        }
    }
}

// ============================================================================
// MAIN SCREEN
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualAssistantMainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(0) }
    var universalInput by remember { mutableStateOf("") }

    fun runTyped() {
        if (universalInput.isBlank()) return
        // the box may still hold "From WhatsApp: ... -> What next?": keep only what was typed after it
        val cmd = universalInput.removePrefix("🎤 Listening...").removePrefix("Hearing: ")
            .substringAfterLast("What next?").trim().trimEnd('.')
        universalInput = ""
        AssistantBridge.followUpUntil = 0L
        scope.launch { CommandRouter.handle(context, cmd) }
    }

    DisposableEffect(Unit) {
        AssistantBridge.promptInTextBoxCallback = { text ->
            universalInput = text      // blank text = a command was accepted: empty the box
        }
        onDispose { AssistantBridge.promptInTextBoxCallback = null }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Green)
                        Spacer(Modifier.width(8.dp))
                        Text("Equal AI", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                        Text(
                            if (AssistantBridge.isContinuousListeningEnabled) "Listening" else "Paused",
                            fontSize = 11.sp, color = Color.Gray
                        )
                        Spacer(Modifier.width(4.dp))
                        Switch(
                            checked = AssistantBridge.isContinuousListeningEnabled,
                            onCheckedChange = {
                                AssistantBridge.isContinuousListeningEnabled = it
                                if (it) AssistantBridge.foregroundService?.startListeningInternal(false)
                            }
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Icon(Icons.Default.Notifications, null) }, label = { Text("Alerts") })
                NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Icon(Icons.Default.Schedule, null) }, label = { Text("Scheduler") })
                NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 }, icon = { Icon(Icons.Default.PhoneCallback, null) }, label = { Text("Calls") })
                NavigationBarItem(selected = selectedTab == 3, onClick = { selectedTab = 3 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Setup") })
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).background(Color(0xFFF8FAFC)).padding(16.dp)
        ) {
            // Command box: live speech appears here; type a command too (typing skips the wake word)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = universalInput,
                    onValueChange = { universalInput = it },
                    placeholder = { Text("Say \"Hey Equal…\" or type") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { runTyped() })
                )
                IconButton(
                    onClick = {
                        if (AssistantBridge.foregroundService == null) {
                            Toast.makeText(context, "Voice service not running. Grant mic permission.", Toast.LENGTH_SHORT).show()
                        } else {
                            AssistantBridge.forceNextCommand = true
                            AssistantBridge.foregroundService?.startListeningInternal(true)
                        }
                    },
                    modifier = Modifier.clip(CircleShape).background(Green)
                ) { Icon(Icons.Default.Mic, contentDescription = "Talk now", tint = Color.White) }
                IconButton(
                    onClick = { runTyped() },
                    modifier = Modifier.clip(CircleShape).background(Ink)
                ) { Icon(Icons.Default.Send, contentDescription = "Run", tint = Color.White) }
            }

            Spacer(Modifier.height(8.dp))

            // Offline mode, wake word, summarize
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                LabeledSwitch("Offline mode", AssistantBridge.offlineMode) {
                    AssistantBridge.offlineMode = it
                    AppSettings.offlineMode = it
                }
                LabeledSwitch("Wake word", AssistantBridge.wakeWordRequired) {
                    AssistantBridge.wakeWordRequired = it
                    AppSettings.wakeWordRequired = it
                }
                IconButton(onClick = { scope.launch { CommandRouter.summarizeAll(context) } }) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = "Summarize everything", tint = Green)
                }
            }

            Spacer(Modifier.height(8.dp))

            var online by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                while (true) {
                    online = Net.isOnline(context)
                    delay(5_000)
                }
            }
            if (AssistantBridge.offlineMode || !online) {
                Text(
                    "Working offline: voice commands, call messages, scheduled calls and texts and notifications " +
                        "still work. Gemini replies and summaries resume when internet returns.",
                    fontSize = 12.sp, color = Color(0xFFB45309)
                )
                Spacer(Modifier.height(6.dp))
            }

            when (selectedTab) {
                0 -> NotificationsTab()
                1 -> SchedulerTab(context)
                2 -> CallsTab(context)
                else -> SetupTab(context)
            }
        }
    }
}

@Composable
fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = Color.DarkGray)
        Spacer(Modifier.width(4.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** One live notification on a full-screen page: full text, open the app, reply, dismiss. */
@Composable
fun NotificationFullScreen(item: AppNotificationItem, onClose: () -> Unit) {
    val context = LocalContext.current
    var reply by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF8FAFC)) {
            Column(modifier = Modifier.fillMaxSize().padding(20.dp).statusBarsPadding().navigationBarsPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.appName, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(item.timestamp, color = Color.Gray, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(item.sender, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text(item.message, fontSize = 17.sp)
                }
                if (status.isNotBlank()) Text(status, fontSize = 12.sp, color = Green)
                if (item.replyAction != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = reply, onValueChange = { reply = it },
                            placeholder = { Text("Reply…") }, modifier = Modifier.weight(1f), singleLine = true
                        )
                        Button(onClick = {
                            if (reply.isNotBlank()) {
                                val ok = AssistantBridge.notificationService?.replyTo(item, reply.trim()) == true
                                status = if (ok) "Reply sent." else "That notification can't be replied to."
                                if (ok) reply = ""
                            }
                        }) { Text("Send") }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            if (ActionExecutor.openNotification(context, item)) onClose()
                            else status = "Could not open ${item.appName}"
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Open app") }
                    OutlinedButton(
                        onClick = {
                            val svc = AssistantBridge.notificationService
                            if (svc != null) svc.dismissItem(item) else AssistantBridge.notifications.remove(item)
                            onClose()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Dismiss") }
                }
            }
        }
    }
}

// ============================================================================
// ALERTS TAB
// ============================================================================

@Composable
fun NotificationsTab() {
    val context = LocalContext.current
    var detail by remember { mutableStateOf<AppNotificationItem?>(null) }
    detail?.let { item ->
        NotificationFullScreen(item) { detail = null }
    }
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Read notifications aloud", fontWeight = FontWeight.Bold)
                    Text("Reads each alert, then asks what to do next", fontSize = 12.sp, color = Color.Gray)
                }
                Switch(
                    checked = AssistantBridge.readNotificationsAloud,
                    onCheckedChange = { AssistantBridge.readNotificationsAloud = it }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Live notifications (${AssistantBridge.notifications.size})", fontWeight = FontWeight.Bold)
            TextButton(
                onClick = {
                    AssistantBridge.notifications.clear(); AssistantBridge.latestNotification = null
                    AssistantBridge.foregroundService?.clearNotificationQueue()
                },
                enabled = AssistantBridge.notifications.isNotEmpty()
            ) { Text("Clear all") }
        }
        Text("Tap a notification to open it full screen. The X removes it here and from your phone's notification bar.", fontSize = 11.sp, color = Color.Gray)
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(AssistantBridge.notifications.toList()) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { detail = item },
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Row(modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
                        Column(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${item.appName} • ${item.sender}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text(item.timestamp, color = Color.Gray, fontSize = 11.sp)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(item.message, fontSize = 13.sp)
                        }
                        IconButton(onClick = {
                            val svc = AssistantBridge.notificationService
                            if (svc != null) svc.dismissItem(item) else AssistantBridge.notifications.remove(item)
                        }) { Icon(Icons.Default.Close, contentDescription = "Remove notification", tint = Color.Gray) }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Action & diagnostic log", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            TextButton(
                onClick = { AssistantBridge.commandLogs.clear() },
                enabled = AssistantBridge.commandLogs.isNotEmpty()
            ) { Text("Clear log") }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1E293B)).padding(8.dp)
        ) {
            items(AssistantBridge.commandLogs) { log -> Text(log, color = Green, fontSize = 11.sp) }
        }
    }
}

// ============================================================================
// SCHEDULER TAB
// ============================================================================

@Composable
fun SchedulerTab(context: Context) {
    var target by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var actionType by remember { mutableStateOf("WHATSAPP") }
    var triggerAt by remember { mutableStateOf(System.currentTimeMillis() + 10 * 60_000L) }
    var attachmentUriString by remember { mutableStateOf<String?>(null) }
    var appLabel by remember { mutableStateOf("") }
    var converse by remember { mutableStateOf(true) }
    var retries by remember { mutableStateOf(0f) }
    var gapSec by remember { mutableStateOf("120") }
    var fallback by remember { mutableStateOf("") }
    var greet by remember { mutableStateOf("") }
    var simSlot by remember { mutableStateOf(AppSettings.defaultSim) }
    val sched = rememberCoroutineScope()
    var previewing by remember { mutableStateOf(false) }
    var textPreview by remember { mutableStateOf("") }
    val clipPlayer = remember { arrayOfNulls<MediaPlayer>(1) }

    fun stopPreview() {
        try { clipPlayer[0]?.stop() } catch (e: Exception) { }
        try { clipPlayer[0]?.release() } catch (e: Exception) { }
        clipPlayer[0] = null
        previewing = false
    }

    fun startPreview(uri: String) {
        stopPreview()
        try {
            val mp = MediaPlayer()
            mp.setDataSource(context, Uri.parse(uri))
            mp.setOnCompletionListener { stopPreview() }
            mp.prepare()
            mp.start()
            clipPlayer[0] = mp
            previewing = true
        } catch (e: Exception) {
            Toast.makeText(context, "Can't play that clip", Toast.LENGTH_SHORT).show()
        }
    }
    DisposableEffect(Unit) { onDispose { stopPreview() } }
    var showAppPicker by remember { mutableStateOf(false) }

    if (showAppPicker) {
        AppPickerDialog(
            context = context,
            onPick = { pkg, label -> target = pkg; appLabel = label; showAppPicker = false },
            onDismiss = { showAppPicker = false }
        )
    }

    val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        uri?.let {
            val cursor = context.contentResolver.query(it, null, null, null, null)
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getString(c.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                    val pCursor = context.contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        null,
                        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                        arrayOf(id),
                        null
                    )
                    pCursor?.use { pc ->
                        if (pc.moveToFirst()) {
                            target = pc.getString(pc.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                        }
                    }
                }
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) { }
            attachmentUriString = uri.toString()
        }
    }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) { }
            attachmentUriString = uri.toString()
        }
    }

    val audioRecorder = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.data?.let { attachmentUriString = it.toString() }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Schedule an action", fontWeight = FontWeight.Bold, fontSize = 17.sp)

        if (!Scheduler.canExact(context)) {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7))) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Exact alarms are blocked, so tasks may run a few minutes late.", fontSize = 13.sp)
                    TextButton(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                            )
                        }
                    }) { Text("Allow exact alarms") }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("WHATSAPP" to "WhatsApp", "CALL_SPEAK" to "Call", "SMS" to "SMS", "OPEN_APP" to "App").forEach { (k, v) ->
                FilterChip(
                    selected = actionType == k,
                    onClick = {
                        if (actionType != k) { target = ""; appLabel = ""; attachmentUriString = null }
                        actionType = k
                    },
                    label = { Text(v) }
                )
            }
        }

        if (actionType == "OPEN_APP") {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = appLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("App to open") },
                    placeholder = { Text("Tap the apps button to browse") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                IconButton(onClick = { showAppPicker = true }) {
                    Icon(Icons.Default.Apps, contentDescription = "Browse apps", tint = Green)
                }
            }
            Text(
                "Equal opens this app at the chosen time. Turn on the Equal AI accessibility service in Setup " +
                    "so Android allows opening apps from the background.",
                fontSize = 12.sp, color = Color.Gray
            )
        } else {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { Text("Contact number or name") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                IconButton(onClick = { contactPicker.launch(null) }) {
                    Icon(Icons.Default.Contacts, contentDescription = "Pick contact", tint = Green)
                }
            }
    
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                label = { Text(if (actionType == "CALL_SPEAK") "Custom text, spoken after the voice clip" else "Message text") },
                modifier = Modifier.fillMaxWidth()
            )
    
        }

        if (actionType == "CALL_SPEAK") {
            OutlinedTextField(
                value = greet,
                onValueChange = { greet = it },
                label = { Text("Greeting, said first when they answer (optional)") },
                placeholder = { Text("e.g. Hello Priya, this is Equal calling for Ravi") },
                modifier = Modifier.fillMaxWidth()
            )
            Text("Pre-recorded voice (played after the greeting, once they answer)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = { audioPicker.launch(arrayOf("audio/*")) }) {
                    Icon(Icons.Default.AttachFile, contentDescription = "Choose an audio file")
                }
                IconButton(onClick = {
                    try {
                        audioRecorder.launch(Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION))
                    } catch (e: Exception) {
                        Toast.makeText(context, "No voice recorder app found", Toast.LENGTH_SHORT).show()
                    }
                }) { Icon(Icons.Default.Mic, contentDescription = "Record a voice clip") }
                if (attachmentUriString != null) {
                    Text("Voice clip ready", color = Green, fontSize = 12.sp)
                    TextButton(onClick = { if (previewing) stopPreview() else startPreview(attachmentUriString!!) }) {
                        Icon(if (previewing) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (previewing) "Stop" else "Preview")
                    }
                    TextButton(onClick = { stopPreview(); attachmentUriString = null }) { Text("Remove") }
                } else {
                    Text("Optional: record one or choose a file", fontSize = 12.sp, color = Color.Gray)
                }
            }
            if (message.isNotBlank()) {
                TextButton(onClick = {
                    sched.launch {
                        textPreview = "Speaking…"
                        textPreview = CallVoice.speakIntoCall(message, null) ?: "Played"
                    }
                }) { Text("Preview the custom text (voice and speed)") }
                if (textPreview.isNotBlank()) Text(textPreview, fontSize = 11.sp, color = Color.Gray)
            }
            LabeledSwitch("Assistant talks with them afterwards", converse) { converse = it }

            Text("If not answered, call again ${retries.toInt()} time(s)", fontSize = 13.sp)
            Slider(value = retries, onValueChange = { retries = it }, valueRange = 0f..10f, steps = 9)
            if (retries.toInt() > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("0" to "Immediately", "30" to "30 s", "120" to "2 min", "300" to "5 min").forEach { (v, label) ->
                        FilterChip(selected = gapSec == v, onClick = { gapSec = v }, label = { Text(label) })
                    }
                }
                OutlinedTextField(
                    value = gapSec,
                    onValueChange = { gapSec = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("Seconds before calling again (0 = immediately)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            OutlinedTextField(
                value = fallback,
                onValueChange = { fallback = it },
                label = { Text("If still not answered, send this text (optional)") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (actionType == "CALL_SPEAK" || actionType == "SMS") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SIM", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                listOf(0 to "SIM 1", 1 to "SIM 2").forEach { (k, v) ->
                    FilterChip(selected = simSlot == k, onClick = { simSlot = k }, label = { Text(v) })
                }
            }
        }

        if (actionType == "WHATSAPP") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Default.AttachFile, contentDescription = "Attach file")
                }
                IconButton(onClick = {
                    try {
                        audioRecorder.launch(Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION))
                    } catch (e: Exception) {
                        Toast.makeText(context, "No voice recorder app found", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.Default.Mic, contentDescription = "Record audio")
                }
                if (attachmentUriString != null) {
                    Text("Attachment ready", color = Green, fontSize = 12.sp)
                    TextButton(onClick = { attachmentUriString = null }) { Text("Remove") }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val cal = Calendar.getInstance().apply { timeInMillis = triggerAt }
                    DatePickerDialog(context, { _, y, m, d ->
                        val c = Calendar.getInstance().apply {
                            timeInMillis = triggerAt
                            set(Calendar.YEAR, y); set(Calendar.MONTH, m); set(Calendar.DAY_OF_MONTH, d)
                        }
                        triggerAt = c.timeInMillis
                    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Ink)
            ) {
                Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Pick date")
            }

            Button(
                onClick = {
                    val cal = Calendar.getInstance().apply { timeInMillis = triggerAt }
                    TimePickerDialog(context, { _, h, min ->
                        val c = Calendar.getInstance().apply {
                            timeInMillis = triggerAt
                            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, min); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                        }
                        triggerAt = c.timeInMillis
                    }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Ink)
            ) {
                Icon(Icons.Default.AccessTime, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Pick time")
            }
        }

        Text(
            "Selected: ${SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(triggerAt)}",
            fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Green
        )

        Button(
            onClick = {
                when {
                    target.isBlank() ->
                        Toast.makeText(context, if (actionType == "OPEN_APP") "Choose an app" else "Enter a contact", Toast.LENGTH_SHORT).show()
                    actionType != "OPEN_APP" && message.isBlank() && attachmentUriString == null ->
                        Toast.makeText(context, "Enter a message", Toast.LENGTH_SHORT).show()
                    triggerAt <= System.currentTimeMillis() + 5_000L ->
                        Toast.makeText(context, "Pick a time in the future", Toast.LENGTH_SHORT).show()
                    else -> {
                        val id = newTaskId()
                        val clipPath = when (actionType) {
                            "WHATSAPP" -> attachmentUriString
                            "CALL_SPEAK" -> attachmentUriString?.let { copyClip(context, Uri.parse(it), id) }
                            else -> null
                        }
                        if (actionType == "CALL_SPEAK" && attachmentUriString != null && clipPath == null) {
                            Toast.makeText(context, "Could not read that voice clip", Toast.LENGTH_SHORT).show()
                        } else {
                            val isCall = actionType == "CALL_SPEAK"
                            Scheduler.add(
                                context,
                                ScheduledActionItem(
                                    id = id,
                                    actionType = actionType,
                                    target = target.trim(),
                                    message = if (actionType == "OPEN_APP") appLabel else message,
                                    triggerAt = triggerAt,
                                    attachmentUri = clipPath,
                                    converse = converse,
                                    simSlot = simSlot,
                                    retries = if (isCall) retries.toInt() else 0,
                                    retryGapSec = (gapSec.toIntOrNull() ?: 120).coerceIn(0, 3600),
                                    fallbackText = if (isCall) fallback.trim() else "",
                                    greeting = if (isCall) greet.trim() else ""
                                )
                            )
                            target = ""; message = ""; attachmentUriString = null; appLabel = ""; fallback = ""; greet = ""
                            Toast.makeText(context, "Scheduled", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Green)
        ) { Text("Schedule task") }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("Scheduled queue", fontWeight = FontWeight.Bold)
        AssistantBridge.scheduledTasks.forEach { task ->
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            when (task.actionType) {
                                "OPEN_APP" -> "Open app: ${task.message.ifBlank { task.target }}"
                                "CALL_SPEAK" -> "Call ${task.target} · SIM ${task.simSlot + 1}"
                                "SMS" -> "SMS to ${task.target} · SIM ${task.simSlot + 1}"
                                else -> "${task.actionType} to ${task.target}"
                            },
                            fontWeight = FontWeight.Bold
                        )
                        Text(task.timeLabel, fontSize = 12.sp, color = Green)
                        if (task.actionType != "OPEN_APP") Text(task.message, fontSize = 12.sp, color = Color.Gray)
                        if (task.actionType == "CALL_SPEAK") {
                            val extras = buildList {
                                if (task.attachmentUri != null) add("voice clip")
                                if (task.converse) add("assistant talks")
                                if (task.retries > 0) add("${task.retries} retries")
                                if (task.fallbackText.isNotBlank()) add("fallback text")
                                if (task.greeting.isNotBlank()) add("greeting")
                            }.joinToString(" · ")
                            if (extras.isNotBlank()) Text(extras, fontSize = 11.sp, color = Color.Gray)
                        }
                    }
                    IconButton(onClick = { Scheduler.cancel(context, task.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Cancel task")
                    }
                }
            }
        }
    }
}

@Composable
fun AppPickerDialog(context: Context, onPick: (String, String) -> Unit, onDismiss: () -> Unit) {
    val pm = context.packageManager
    val apps = remember {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .filter { it.second != context.packageName }
            .sortedBy { it.first.lowercase() }
    }
    var query by remember { mutableStateOf("") }
    val shown = remember(query, apps) {
        if (query.isBlank()) apps else apps.filter { it.first.contains(query.trim(), ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Choose an app") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search apps") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    items(shown, key = { it.second }) { (label, pkg) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onPick(pkg, label) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val icon = remember(pkg) {
                                try { pm.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() } catch (e: Exception) { null }
                            }
                            if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(36.dp))
                            else Spacer(Modifier.size(36.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(label)
                        }
                    }
                }
            }
        }
    )
}

private fun copyClip(ctx: Context, uri: Uri, id: Int): String? {
    return try {
        val dir = File(ctx.filesDir, "clips").apply { mkdirs() }
        val out = File(dir, "clip_$id.audio")
        val input = ctx.contentResolver.openInputStream(uri) ?: return null
        input.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        if (out.length() > 0) out.absolutePath else null
    } catch (e: Exception) {
        null
    }
}

// ============================================================================
// CALLS TAB
// ============================================================================

@Composable
fun CallsTab(context: Context) {
    val scope = rememberCoroutineScope()
    var autoAnswer by remember { mutableStateOf(AppSettings.autoAnswer) }
    var callerNote by remember { mutableStateOf(AppSettings.callerNote) }
    var greetingText by remember { mutableStateOf(AppSettings.greeting) }
    var showSettings by remember { mutableStateOf(false) }
    var playingId by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = RecordingImport.import(context, uri)
                if (!ok) Toast.makeText(context, "Could not read that audio file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    DisposableEffect(Unit) { onDispose { try { player?.release() } catch (e: Exception) { } } }

    fun stopPlayback() {
        try { player?.release() } catch (e: Exception) { }
        player = null
        playingId = null
    }

    fun togglePlay(call: CallRecord) {
        if (playingId == call.id) { stopPlayback(); return }
        stopPlayback()
        val name = call.recordingFile ?: return
        val f = File(CallStore.recordingsDir(context), name)
        try {
            val mp = MediaPlayer()
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener { stopPlayback() }
            mp.prepare()
            mp.start()
            player = mp
            playingId = call.id
        } catch (e: Exception) {
            Toast.makeText(context, "Can't play this recording", Toast.LENGTH_SHORT).show()
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      item {
       Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-answer calls", fontWeight = FontWeight.Bold)
                    Text(
                        "After ${AppSettings.ringDelaySec}s of ringing, Equal answers on speaker, talks to the caller " +
                            "with Gemini and records the call. Summary appears when it ends.",
                        fontSize = 12.sp, color = Color.Gray
                    )
                }
                Switch(checked = autoAnswer, onCheckedChange = { autoAnswer = it; AppSettings.autoAnswer = it })
            }
        }

        Text(
            "Last call event: ${AssistantBridge.lastCallNote}", fontSize = 12.sp, color = Color.Gray,
            maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )

        TextButton(onClick = { showSettings = !showSettings }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showSettings) "Hide call messages ▲" else "Call messages and recordings ▼")
        }
        if (showSettings) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = greetingText,
            onValueChange = { greetingText = it; AppSettings.greeting = it },
            label = { Text("Custom greeting for callers (optional)") },
            placeholder = { Text("e.g. Hello, you have reached Ravi's assistant") },
            supportingText = { Text("Replaces the standard \"Hello, this is Equal...\" opening. Leave blank for the standard one.") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = callerNote,
            onValueChange = { callerNote = it; AppSettings.callerNote = it },
            label = { Text("Custom text for callers (optional)") },
            placeholder = { Text("e.g. Please also tell me your company name") },
            supportingText = { Text("Said or asked in the greeting, on top of Gemini's own questions.") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedButton(
            onClick = { importPicker.launch(arrayOf("audio/*")) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Summarize a recording (pick an audio file)") }
        }

        Text("Call history", fontWeight = FontWeight.Bold)
       }
      }
            items(AssistantBridge.callLogs, key = { it.id }) { call ->
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(call.caller, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
                            Text(call.timeLabel, fontSize = 11.sp, color = Color.Gray)
                        }
                        val status = when (call.state) {
                            "ANSWERING" -> "In progress"
                            "SUMMARIZING" -> "Summarizing…"
                            "PENDING" -> "Waiting for internet"
                            else -> ""
                        }
                        if (status.isNotBlank()) Text(status, fontSize = 11.sp, color = Color(0xFFB45309))
                        Spacer(Modifier.height(6.dp))
                        Text("AI summary", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Green)
                        Text(call.summary, fontSize = 13.sp)
                        if (!call.audioOk) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "This phone blocked the microphone during the call.",
                                fontSize = 12.sp, color = Color(0xFFB45309)
                            )
                            if (call.diag.isNotBlank()) Text(call.diag, fontSize = 11.sp, color = Color.Gray)
                        }
                        if (call.transcript.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text("Transcript", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color.DarkGray)
                            Text(call.transcript, fontSize = 12.sp, color = Color.DarkGray)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (call.hasRecording) {
                                TextButton(onClick = { togglePlay(call) }) {
                                    val playing = playingId == call.id
                                    Icon(
                                        if (playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                                        contentDescription = null, modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (playing) "Stop" else "Play recording")
                                }
                            }
                            if (call.state == "PENDING") {
                                TextButton(onClick = { scope.launch { CallSummarizer.process(context, call) } }) {
                                    Text("Summarize now")
                                }
                            }
                            TextButton(onClick = {
                                if (playingId == call.id) stopPlayback()
                                CallStore.delete(context, call.id)
                            }) { Text("Delete") }
                        }
                    }
                }
            }
        }
}

// ============================================================================
// SETUP TAB
// ============================================================================

@Composable
fun SetupTab(context: Context) {
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf(AppSettings.apiKey) }
    var modelName by remember { mutableStateOf(AppSettings.modelName) }
    var ownerName by remember { mutableStateOf(AppSettings.ownerName) }
    var delaySec by remember { mutableStateOf(AppSettings.ringDelaySec.toFloat()) }
    var speed by remember { mutableStateOf(AppSettings.speechRate) }
    var level by remember { mutableStateOf(AppSettings.callVoiceLevel) }
    var loud by remember { mutableStateOf(AppSettings.loudVoice) }
    var speakerOn by remember { mutableStateOf(AppSettings.speakerOn) }
    var logCalls by remember { mutableStateOf(AppSettings.logOtherCalls) }
    var recMode by remember { mutableStateOf(AppSettings.phoneRecorder) }
    var recNote by remember { mutableStateOf("") }
    var defSim by remember { mutableStateOf(AppSettings.defaultSim) }
    var waitSec by remember { mutableStateOf(AppSettings.outgoingWaitSec.toFloat()) }
    var voiceStatus by remember { mutableStateOf("") }
    var offlineOk by remember { mutableStateOf(false) }
    var offlineText by remember { mutableStateOf("Checking…") }
    var downloading by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    fun open(intent: Intent) {
        try { context.startActivity(intent) } catch (e: Exception) {
            Toast.makeText(context, "Could not open that settings page", Toast.LENGTH_SHORT).show()
        }
    }

    fun refreshOffline() {
        StreamingSpeechHelper(context).checkOfflineSupport { ok, text ->
            offlineOk = ok
            offlineText = text
            if (ok) downloading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshOffline()
        while (true) {
            delay(1_500)
            tick++
        }
    }
    LaunchedEffect(downloading) {
        var n = 0
        while (downloading && n < 60) {            // check every 3 s for up to 3 minutes
            delay(3_000)
            refreshOffline()
            n++
        }
        if (downloading) downloading = false
    }

    @Suppress("UNUSED_VARIABLE") val liveTick = tick     // re-reads the live states below on every refresh
    val missingPerms = requiredPermissions().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
    val missingNames = missingPerms.map { permissionLabel(it) }.distinct().joinToString(", ")
    val batteryOk = try {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
    } catch (e: Exception) { false }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("1. Status and permissions", fontWeight = FontWeight.Bold)
        Text("Live status. Tap Enable on anything that is OFF.", fontSize = 12.sp, color = Color.Gray)

        StatusRow(
            "Permissions", missingPerms.isEmpty(),
            if (missingPerms.isEmpty()) "Phone, microphone, call log, SMS, contacts, audio files all allowed"
            else "Missing: $missingNames",
            "Allow"
        ) { permLauncher.launch(missingPerms.toTypedArray()) }

        StatusRow(
            "Voice service", AssistantBridge.foregroundService != null,
            "Keeps listening in the background and lets the microphone work during calls", "Start"
        ) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, EqualForegroundService::class.java))
            } catch (e: Exception) {
                Toast.makeText(context, "Android would not start it from here. Close and reopen Equal AI.", Toast.LENGTH_LONG).show()
            }
        }

        StatusRow(
            "Accessibility service", AssistantBridge.accessibilityService != null,
            "Reads screens, opens apps, spots when a scheduled call is answered, presses Speaker", "Enable"
        ) { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        StatusRow(
            "Notification access", AssistantBridge.notificationService != null,
            "Lets Equal read and answer your notifications", "Enable"
        ) { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

        StatusRow(
            "Display over other apps", Settings.canDrawOverlays(context),
            "Helps Equal open apps from the background", "Enable"
        ) { open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }

        StatusRow(
            "Exact alarms", Scheduler.canExact(context),
            "Scheduled calls and texts run on time", "Enable"
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
            }
        }

        StatusRow(
            "Battery unrestricted", batteryOk,
            "Stops Android from killing Equal, so it can answer calls", "Enable"
        ) { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }

        StatusRow(
            "Voice engine (text to speech)", CallVoice.isReady,
            if (CallVoice.isReady) "Ready" else "Not ready. Install a voice in phone Settings, Text-to-speech.", "Open"
        ) {
            CallVoice.init(context)
            open(Intent("com.android.settings.TTS_SETTINGS"))
        }

        StatusRow(
            "Offline speech pack", offlineOk,
            if (downloading) "$offlineText (checking every 3 seconds…)" else offlineText,
            if (downloading) null else "Download"
        ) {
            Toast.makeText(context, StreamingSpeechHelper(context).downloadOfflineModel(), Toast.LENGTH_LONG).show()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                downloading = true
                offlineText = "Download requested…"
            }
        }
        if (!offlineOk && !downloading) {
            TextButton(onClick = { refreshOffline() }) { Text("Check again") }
        }

        StatusRow("Gemini API key", apiKey.isNotBlank(), "Needed for replies and summaries (not for offline use)", null) { }

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7))) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Accessibility or Notification toggle greyed out?", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    "Android blocks sideloaded apps until you allow it. The \"Allow restricted settings\" option " +
                        "only appears AFTER you have tried to switch the toggle on once:\n" +
                        "1. Tap \"Open Accessibility settings\", tap Equal AI, and see the blocked message.\n" +
                        "2. Tap \"Open App info\", then the ⋮ menu (top right), then Allow restricted settings. " +
                        "Confirm with your PIN or fingerprint.\n" +
                        "3. Go back to Accessibility and switch Equal AI on. Repeat for Notification access.\n\n" +
                        "Menu still missing? Install the APK from a computer instead: adb install -r equal-ai.apk. " +
                        "Or, with the phone connected: adb shell cmd appops set com.equalai.assistant " +
                        "ACCESS_RESTRICTED_SETTINGS allow",
                    fontSize = 12.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Open Accessibility settings") }
                }
                OutlinedButton(onClick = {
                    open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }) { Text("Open App info") }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("2. Google AI (Gemini)", fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; AppSettings.apiKey = it },
            label = { Text("Google AI Studio API key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = modelName,
            onValueChange = { modelName = it; AppSettings.modelName = it },
            label = { Text("Model name (needs audio input)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("3. Calls", fontWeight = FontWeight.Bold)
        Text(
            "Switch on auto-answer in the Calls tab. Many phones silence the microphone for apps during calls; " +
                "if a call card shows a warning, this phone can't capture the caller.",
            fontSize = 12.sp, color = Color.Gray
        )
        OutlinedTextField(
            value = ownerName,
            onValueChange = { ownerName = it; AppSettings.ownerName = it },
            label = { Text("Your name (said in the greeting)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Text("Default SIM for calls and texts (used without asking)", fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "SIM 1", 1 to "SIM 2").forEach { (k, v) ->
                FilterChip(selected = defSim == k, onClick = { defSim = k; AppSettings.defaultSim = k }, label = { Text(v) })
            }
        }
        Text(if (delaySec.toInt() == 0) "Answer immediately (0 seconds)" else "Answer after ${delaySec.toInt()} seconds of ringing", fontSize = 13.sp)
        Slider(
            value = delaySec,
            onValueChange = { delaySec = it },
            onValueChangeFinished = { AppSettings.ringDelaySec = delaySec.toInt() },
            valueRange = 0f..40f
        )
        LabeledSwitch("List and summarize all my other calls", logCalls) {
            logCalls = it
            AppSettings.logOtherCalls = it
        }
        Text(
            "Calls you answer, dial or miss yourself are added to Call history. If your phone's call recorder saves " +
                "audio files, Equal finds the recording and Gemini summarizes it. Needs the Call log and Audio files " +
                "permissions, and call recording switched on in your phone app.",
            fontSize = 12.sp, color = Color.Gray
        )
        Text(
            "Scheduled calls: with the accessibility service on, Equal sees when the other person answers. " +
                "Without it, it speaks after the seconds below and uses the call log afterwards to tell whether " +
                "anyone really picked up (for the redial).",
            fontSize = 12.sp, color = Color.Gray
        )
        Text("Scheduled call: start speaking ${waitSec.toInt()}s after dialing if the timer can't be read", fontSize = 13.sp)
        Slider(
            value = waitSec,
            onValueChange = { waitSec = it },
            onValueChangeFinished = { AppSettings.outgoingWaitSec = waitSec.toInt() },
            valueRange = 5f..45f
        )

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("4. Assistant voice", fontWeight = FontWeight.Bold)
        Text("Voice speed: ${"%.2f".format(speed)}x (lower is slower)", fontSize = 13.sp)
        Slider(
            value = speed,
            onValueChange = { speed = it },
            onValueChangeFinished = { AppSettings.speechRate = speed },
            valueRange = 0.4f..1.2f
        )
        Text("Voice loudness on calls: ${(level * 100).toInt()}%", fontSize = 13.sp)
        Slider(
            value = level,
            onValueChange = { level = it },
            onValueChangeFinished = { AppSettings.callVoiceLevel = level },
            valueRange = 0.3f..1.0f
        )
        Text(
            "If the caller hears a faint, garbled voice, try 60 to 80%: at full volume the speaker beside the microphone " +
                "can distort. Also try switching \"Loudspeaker during calls\" off below.",
            fontSize = 12.sp, color = Color.Gray
        )
        SetupButton("Test the voice") {
            scope.launch {
                voiceStatus = "Speaking…"
                val err = CallVoice.speakIntoCall("Hello, this is Equal. This is how I will sound on your calls.", null)
                voiceStatus = err ?: "Played. If you heard nothing, raise the alarm volume and check the voice engine above."
            }
        }
        if (voiceStatus.isNotBlank()) Text(voiceStatus, fontSize = 12.sp, color = Green)
        LabeledSwitch("Loud voice for callers (alarm channel)", loud) {
            loud = it
            AppSettings.loudVoice = it
        }
        Text(
            "On: the assistant speaks on the alarm channel at full volume, the most reliable way for the caller to " +
                "hear it. Turn off to use the normal call channel.",
            fontSize = 12.sp, color = Color.Gray
        )
        LabeledSwitch("Loudspeaker during calls", speakerOn) {
            speakerOn = it
            AppSettings.speakerOn = it
        }
        Text(
            "On (default): the call is put on speaker. If the caller or receiver still can't hear the assistant, try " +
                "switching this off: on some phones the speaker cancels the assistant's own voice out of the call.",
            fontSize = 12.sp, color = Color.Gray
        )
        Text("Phone's own call recorder", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Leave alone", 1 to "ON in calls", 2 to "OFF in calls").forEach { (k, v) ->
                FilterChip(selected = recMode == k, onClick = { recMode = k; AppSettings.phoneRecorder = k }, label = { Text(v) })
            }
        }
        Text(
            "Equal presses Record or Stop recording in your phone app during every call. Needs the Accessibility service " +
                "and a phone app that has a call recorder. Android gives apps no direct switch for it. If the recorder " +
                "starts by itself, also turn off automatic recording in the phone's own settings.",
            fontSize = 12.sp, color = Color.Gray
        )
        OutlinedButton(onClick = { recNote = PhoneRecorder.openPhoneSettings(context) }) { Text("Open phone call settings") }
        if (recNote.isNotBlank()) Text(recNote, fontSize = 12.sp, color = Green)
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, detail: String, action: String?, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        if (ok) "ON" else "OFF",
                        fontWeight = FontWeight.Bold, fontSize = 12.sp,
                        color = if (ok) Green else Color(0xFFB91C1C)
                    )
                }
                if (detail.isNotBlank()) Text(detail, fontSize = 11.sp, color = Color.Gray)
            }
            if (!ok && action != null) {
                Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = Ink)) { Text(action) }
            }
        }
    }
}

fun requiredPermissions(): List<String> {
    val perms = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CONTACTS
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        perms.add(Manifest.permission.POST_NOTIFICATIONS)
        perms.add("android.permission.READ_MEDIA_AUDIO")
    } else {
        perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    return perms
}

private fun permissionLabel(p: String) = when (p) {
    Manifest.permission.RECORD_AUDIO -> "Microphone"
    Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE -> "Phone"
    Manifest.permission.READ_CALL_LOG -> "Call log"
    Manifest.permission.SEND_SMS -> "SMS"
    Manifest.permission.READ_CONTACTS -> "Contacts"
    Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
    else -> "Audio files"
}

@Composable
private fun SetupButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Ink)
    ) { Text(label) }
}
