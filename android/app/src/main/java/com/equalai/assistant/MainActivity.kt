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
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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
                    }
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

    DisposableEffect(Unit) {
        AssistantBridge.promptInTextBoxCallback = { text ->
            if (text.isNotBlank() || universalInput.startsWith("🎤") || universalInput.startsWith("Hearing")) {
                universalInput = text
            }
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
                    shape = RoundedCornerShape(12.dp)
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
                    onClick = {
                        if (universalInput.isNotBlank()) {
                            val cmd = universalInput.removePrefix("🎤 Listening...").removePrefix("Hearing: ").trim()
                            universalInput = ""
                            AssistantBridge.followUpUntil = 0L
                            scope.launch { CommandRouter.handle(context, cmd) }
                        }
                    },
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
                TextButton(onClick = { scope.launch { CommandRouter.summarizeAll(context) } }) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Summarize", fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(8.dp))

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

// ============================================================================
// ALERTS TAB
// ============================================================================

@Composable
fun NotificationsTab() {
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

        Text("Live notifications (${AssistantBridge.notifications.size})", fontWeight = FontWeight.Bold)
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(AssistantBridge.notifications) { item ->
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${item.appName} • ${item.sender}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(item.timestamp, color = Color.Gray, fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(item.message, fontSize = 13.sp)
                    }
                }
            }
        }

        Text("Action & diagnostic log", fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
            listOf("WHATSAPP" to "WhatsApp", "CALL_SPEAK" to "Call & speak", "SMS" to "SMS").forEach { (k, v) ->
                FilterChip(selected = actionType == k, onClick = { actionType = k }, label = { Text(v) })
            }
        }

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
            label = { Text(if (actionType == "CALL_SPEAK") "Text to speak on the call" else "Message text") },
            modifier = Modifier.fillMaxWidth()
        )

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
                    target.isBlank() -> Toast.makeText(context, "Enter a contact", Toast.LENGTH_SHORT).show()
                    message.isBlank() && attachmentUriString == null ->
                        Toast.makeText(context, "Enter a message", Toast.LENGTH_SHORT).show()
                    triggerAt <= System.currentTimeMillis() + 5_000L ->
                        Toast.makeText(context, "Pick a time in the future", Toast.LENGTH_SHORT).show()
                    else -> {
                        Scheduler.add(
                            context,
                            ScheduledActionItem(
                                newTaskId(), actionType, target.trim(), message,
                                triggerAt, if (actionType == "WHATSAPP") attachmentUriString else null
                            )
                        )
                        target = ""; message = ""; attachmentUriString = null
                        Toast.makeText(context, "Scheduled", Toast.LENGTH_SHORT).show()
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
                        Text("${task.actionType} to ${task.target}", fontWeight = FontWeight.Bold)
                        Text(task.timeLabel, fontSize = 12.sp, color = Green)
                        Text(task.message, fontSize = 12.sp, color = Color.Gray)
                    }
                    IconButton(onClick = { Scheduler.cancel(context, task.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Cancel task")
                    }
                }
            }
        }
    }
}

// ============================================================================
// CALLS TAB
// ============================================================================

@Composable
fun CallsTab(context: Context) {
    val scope = rememberCoroutineScope()
    var autoAnswer by remember { mutableStateOf(AppSettings.autoAnswer) }
    var playingId by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

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

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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

        Text("Call history", fontWeight = FontWeight.Bold)
        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
}

// ============================================================================
// SETUP TAB
// ============================================================================

@Composable
fun SetupTab(context: Context) {
    var apiKey by remember { mutableStateOf(AppSettings.apiKey) }
    var modelName by remember { mutableStateOf(AppSettings.modelName) }
    var ownerName by remember { mutableStateOf(AppSettings.ownerName) }
    var delaySec by remember { mutableStateOf(AppSettings.ringDelaySec.toFloat()) }

    fun open(intent: Intent) {
        try { context.startActivity(intent) } catch (e: Exception) {
            Toast.makeText(context, "Could not open that settings page", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("1. System permissions", fontWeight = FontWeight.Bold)
        Text(
            "Sideloaded on Android 13+? Open App info, tap the ⋮ menu, choose \"Allow restricted settings\", " +
                "or the Accessibility and Notification toggles stay greyed out.",
            fontSize = 12.sp, color = Color.Gray
        )

        SetupButton("App info (allow restricted settings)") {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
        SetupButton("Enable accessibility (reads screens, opens apps)") { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        SetupButton("Enable notification listener") { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        SetupButton("Enable display over other apps") {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
        }
        SetupButton("Allow exact alarms (scheduler)") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
            }
        }
        SetupButton("Battery: set Equal AI to unrestricted") { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }

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
        Text("3. Call answering", fontWeight = FontWeight.Bold)
        Text(
            "Turn it on in the Calls tab. Open Equal AI once after every reboot so the voice service is running; " +
                "calls are only answered while it runs. Many phones silence the microphone for apps during calls. " +
                "If a call card shows a warning, this phone can't do it.",
            fontSize = 12.sp, color = Color.Gray
        )
        OutlinedTextField(
            value = ownerName,
            onValueChange = { ownerName = it; AppSettings.ownerName = it },
            label = { Text("Your name (said in the greeting)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Text("Answer after ${delaySec.toInt()} seconds of ringing", fontSize = 13.sp)
        Slider(
            value = delaySec,
            onValueChange = { delaySec = it },
            onValueChangeFinished = { AppSettings.ringDelaySec = delaySec.toInt() },
            valueRange = 5f..40f
        )
    }
}

@Composable
private fun SetupButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Ink)
    ) { Text(label) }
}
