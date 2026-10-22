package com.equalai.assistant

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ============================================================================
// CALENDAR: see every scheduled event on a month grid, open it, edit it, delete it,
// or start a new one on a chosen day (the new-event form is the Scheduler tab).
// ============================================================================

private val CalGreen = Color(0xFF10B981)
private val CalInk = Color(0xFF0F172A)

private fun dayStartOf(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun typeColor(t: ScheduledActionItem): Color = when {
    t.actionType == "CALL_SPEAK" && t.viaApp -> Color(0xFF7C3AED)
    t.actionType == "CALL_SPEAK" -> Color(0xFF10B981)
    t.actionType == "SMS" -> Color(0xFF2563EB)
    t.actionType == "WHATSAPP" -> Color(0xFF0D9488)
    else -> Color(0xFFF59E0B)
}

private fun typeName(t: ScheduledActionItem): String = when {
    t.actionType == "CALL_SPEAK" && t.viaApp -> "App call"
    t.actionType == "CALL_SPEAK" && t.viaWhatsApp -> "WhatsApp call"
    t.actionType == "CALL_SPEAK" -> "Phone call"
    t.actionType == "SMS" -> "Text message"
    t.actionType == "WHATSAPP" -> "WhatsApp message"
    t.actionType == "OPEN_APP" -> "Open app"
    else -> t.actionType
}

private fun titleOf(t: ScheduledActionItem): String = when {
    t.actionType == "OPEN_APP" -> "Open ${t.message.ifBlank { t.target }}"
    t.actionType == "CALL_SPEAK" && t.viaApp -> "App call to ${t.recipients.ifBlank { t.target }}"
    else -> "${typeName(t)} to ${t.target}"
}

private fun clockOf(ms: Long): String = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(ms))

@Composable
fun CalendarTab(context: Context, onCreate: (Long) -> Unit) {
    val today = dayStartOf(System.currentTimeMillis())
    var month by remember { mutableStateOf(Calendar.getInstance().apply { timeInMillis = today; set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis) }
    var selected by remember { mutableStateOf(today) }
    var opened by remember { mutableStateOf<ScheduledActionItem?>(null) }
    var editing by remember { mutableStateOf<ScheduledActionItem?>(null) }

    val tasks = AssistantBridge.scheduledTasks.toList()
    val byDay = tasks.groupBy { dayStartOf(it.triggerAt) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // month header
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                month = Calendar.getInstance().apply { timeInMillis = month; add(Calendar.MONTH, -1) }.timeInMillis
            }) { Icon(Icons.Default.ChevronLeft, contentDescription = "Previous month") }
            Text(
                SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(month)),
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 18.sp
            )
            IconButton(onClick = {
                month = Calendar.getInstance().apply { timeInMillis = month; add(Calendar.MONTH, 1) }.timeInMillis
            }) { Icon(Icons.Default.ChevronRight, contentDescription = "Next month") }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("S", "M", "T", "W", "T", "F", "S").forEach {
                Text(it, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 12.sp, color = Color.Gray)
            }
        }

        // the grid
        val first = Calendar.getInstance().apply { timeInMillis = month; set(Calendar.DAY_OF_MONTH, 1) }
        val offset = first.get(Calendar.DAY_OF_WEEK) - 1
        val days = first.getActualMaximum(Calendar.DAY_OF_MONTH)
        val rows = (offset + days + 6) / 7
        for (r in 0 until rows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (c in 0 until 7) {
                    val n = r * 7 + c - offset + 1
                    if (n < 1 || n > days) {
                        Spacer(Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val dayMs = Calendar.getInstance().apply { timeInMillis = month; add(Calendar.DAY_OF_MONTH, n - 1) }.timeInMillis
                        val list = byDay[dayMs].orEmpty()
                        val isSel = dayMs == selected
                        Box(
                            modifier = Modifier.weight(1f).aspectRatio(1f).padding(2.dp)
                                .background(
                                    when {
                                        isSel -> CalGreen.copy(alpha = 0.25f)
                                        dayMs == today -> Color(0xFFE2E8F0)
                                        else -> Color.Transparent
                                    },
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { selected = dayMs },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    n.toString(), fontSize = 14.sp,
                                    fontWeight = if (dayMs == today || isSel) FontWeight.Bold else FontWeight.Normal
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(8.dp)) {
                                    list.take(3).forEach {
                                        Box(Modifier.size(5.dp).background(typeColor(it), CircleShape))
                                    }
                                }
                                if (list.size > 3) Text("+${list.size - 3}", fontSize = 9.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        }

        Text(
            "Green: phone call · Purple: app call · Blue: text · Teal: WhatsApp · Amber: open app",
            fontSize = 11.sp, color = Color.Gray
        )

        // the chosen day
        Text(
            SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.getDefault()).format(Date(selected)),
            fontWeight = FontWeight.Bold, fontSize = 16.sp
        )
        Button(
            onClick = { onCreate(selected) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = CalGreen)
        ) { Text("Add an event on this day") }

        val dayTasks = byDay[selected].orEmpty().sortedBy { it.triggerAt }
        if (dayTasks.isEmpty()) Text("Nothing scheduled.", fontSize = 13.sp, color = Color.Gray)
        dayTasks.forEach { t ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { opened = t },
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(typeColor(t), CircleShape))
                    Spacer(Modifier.size(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${clockOf(t.triggerAt)}  ·  ${titleOf(t)}", fontWeight = FontWeight.SemiBold)
                        if (t.actionType != "OPEN_APP" && t.message.isNotBlank()) {
                            Text(t.message, fontSize = 12.sp, color = Color.Gray, maxLines = 2)
                        }
                    }
                }
            }
        }

        // what already happened that day
        val happened = AssistantBridge.callLogs.filter { dayStartOf(it.startedAt) == selected }.sortedBy { it.startedAt }
        if (happened.isNotEmpty()) {
            Text("Calls that day", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            happened.forEach { c ->
                Text(
                    "${clockOf(c.startedAt)}  ·  ${c.caller}: ${c.summary.take(90)}",
                    fontSize = 12.sp, color = Color.Gray
                )
            }
        }
    }

    opened?.let { t ->
        EventViewDialog(
            task = t,
            onEdit = { editing = t; opened = null },
            onDelete = { Scheduler.cancel(context, t.id); opened = null },
            onClose = { opened = null }
        )
    }
    editing?.let { t ->
        EventEditDialog(context = context, task = t, onDone = { editing = null })
    }
}

@Composable
private fun EventViewDialog(task: ScheduledActionItem, onEdit: () -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(titleOf(task)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(SimpleDateFormat("EEE, dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(task.triggerAt)), fontWeight = FontWeight.Bold, color = CalGreen)
                Text("Type: ${typeName(task)}")
                if (task.actionType != "OPEN_APP") {
                    if (task.viaApp) Text("Phones: ${task.recipients}") else Text("To: ${task.target}")
                    if (task.greeting.isNotBlank()) Text("Greeting: ${task.greeting}")
                    if (task.message.isNotBlank()) Text("Message: ${task.message}")
                }
                if (task.actionType == "CALL_SPEAK") {
                    if (task.attachmentUri != null) Text("Voice clip: yes")
                    Text("Assistant talks afterwards: ${if (task.converse) "yes" else "no"}")
                    if (task.retries > 0) Text("Tries again up to ${task.retries} time(s), ${task.retryGapSec} s apart")
                    if (task.fallbackText.isNotBlank()) Text("Fallback text: ${task.fallbackText}")
                }
            }
        },
        confirmButton = { TextButton(onClick = onEdit) { Text("Edit") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete", color = Color(0xFFB91C1C)) }
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
    )
}

@Composable
private fun EventEditDialog(context: Context, task: ScheduledActionItem, onDone: () -> Unit) {
    var at by remember(task.id) { mutableStateOf(task.triggerAt) }
    var target by remember(task.id) { mutableStateOf(task.target) }
    var message by remember(task.id) { mutableStateOf(task.message) }
    var greeting by remember(task.id) { mutableStateOf(task.greeting) }
    var recipients by remember(task.id) { mutableStateOf(task.recipients) }
    var converse by remember(task.id) { mutableStateOf(task.converse) }
    var retries by remember(task.id) { mutableStateOf(task.retries.toFloat()) }
    var fallback by remember(task.id) { mutableStateOf(task.fallbackText) }
    var clip by remember(task.id) { mutableStateOf(task.attachmentUri) }
    val isCall = task.actionType == "CALL_SPEAK"
    val isOpen = task.actionType == "OPEN_APP"

    val clipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val dir = File(context.filesDir, "clips").apply { mkdirs() }
                val out = File(dir, "clip_${task.id}.audio")
                context.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                if (out.length() > 0) clip = out.absolutePath
                else Toast.makeText(context, "Could not read that audio file", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Could not read that audio file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Edit: ${typeName(task)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    SimpleDateFormat("EEE, dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(at)),
                    fontWeight = FontWeight.Bold, color = CalGreen
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val cal = Calendar.getInstance().apply { timeInMillis = at }
                        DatePickerDialog(context, { _, y, m, d ->
                            at = Calendar.getInstance().apply {
                                timeInMillis = at
                                set(Calendar.YEAR, y); set(Calendar.MONTH, m); set(Calendar.DAY_OF_MONTH, d)
                            }.timeInMillis
                        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                    }) { Text("Change date") }
                    OutlinedButton(onClick = {
                        val cal = Calendar.getInstance().apply { timeInMillis = at }
                        TimePickerDialog(context, { _, h, min ->
                            at = Calendar.getInstance().apply {
                                timeInMillis = at
                                set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, min); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                            }.timeInMillis
                        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
                    }) { Text("Change time") }
                }
                if (!isOpen) {
                    if (task.viaApp) {
                        OutlinedTextField(
                            value = recipients, onValueChange = { recipients = it },
                            label = { Text("Phone names (comma-separated)") }, modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        OutlinedTextField(
                            value = target, onValueChange = { target = it },
                            label = { Text("Contact or number") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (isCall) {
                        OutlinedTextField(
                            value = greeting, onValueChange = { greeting = it },
                            label = { Text("Greeting") }, modifier = Modifier.fillMaxWidth()
                        )
                    }
                    OutlinedTextField(
                        value = message, onValueChange = { message = it },
                        label = { Text(if (isCall) "Custom text" else "Message") }, modifier = Modifier.fillMaxWidth()
                    )
                }
                if (isCall) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { clipPicker.launch(arrayOf("audio/*")) }) {
                            Text(if (clip == null) "Add voice clip" else "Change voice clip")
                        }
                        if (clip != null) TextButton(onClick = { clip = null }) { Text("Remove clip") }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Assistant talks afterwards", modifier = Modifier.weight(1f))
                        androidx.compose.material3.Switch(checked = converse, onCheckedChange = { converse = it })
                    }
                    Text("If not answered, try again ${retries.toInt()} time(s)", fontSize = 13.sp)
                    Slider(value = retries, onValueChange = { retries = it }, valueRange = 0f..10f, steps = 9)
                    OutlinedTextField(
                        value = fallback, onValueChange = { fallback = it },
                        label = { Text("Fallback text if nobody answers") }, modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    at <= System.currentTimeMillis() + 5_000L ->
                        Toast.makeText(context, "Pick a time in the future", Toast.LENGTH_SHORT).show()
                    !isOpen && task.viaApp && recipients.isBlank() ->
                        Toast.makeText(context, "Enter at least one phone name", Toast.LENGTH_SHORT).show()
                    !isOpen && !task.viaApp && target.isBlank() ->
                        Toast.makeText(context, "Enter a contact or number", Toast.LENGTH_SHORT).show()
                    else -> {
                        val updated = task.copy(
                            triggerAt = at,
                            target = if (task.viaApp && target.isBlank()) recipients.trim() else target.trim(),
                            message = message,
                            greeting = if (isCall) greeting.trim() else task.greeting,
                            recipients = if (task.viaApp) recipients.split(',').joinToString(",") { it.trim() } else task.recipients,
                            converse = if (isCall) converse else task.converse,
                            retries = if (isCall) retries.toInt() else task.retries,
                            fallbackText = if (isCall) fallback.trim() else task.fallbackText,
                            attachmentUri = if (isCall) clip else task.attachmentUri
                        )
                        Scheduler.cancel(context, task.id)
                        Scheduler.add(context, updated)
                        Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                        onDone()
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } }
    )
}
