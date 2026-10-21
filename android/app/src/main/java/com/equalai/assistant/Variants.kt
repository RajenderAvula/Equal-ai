package com.equalai.assistant

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar
import java.util.Locale

/**
 * A different greeting / message / voice clip for certain times of day, weekdays or people.
 * A blank field falls back to the main one. The first variant that matches a call wins.
 * Empty "times / days / people" means "all of them".
 */
class ContentVariant {
    var persons by mutableStateOf(setOf<String>())
    var times by mutableStateOf(setOf<Int>())          // minutes after midnight
    var weekdays by mutableStateOf(setOf<Int>())       // Calendar.SUNDAY (1) .. Calendar.SATURDAY (7)
    var greeting by mutableStateOf("")
    var message by mutableStateOf("")
    var clipUri by mutableStateOf<String?>(null)

    fun matches(person: String?, minutes: Int, weekday: Int): Boolean =
        (persons.isEmpty() || person == null || person in persons) &&
            (times.isEmpty() || minutes in times) &&
            (weekdays.isEmpty() || weekday in weekdays)
}

fun timeLabel(m: Int): String =
    String.format(Locale.getDefault(), "%d:%02d %s", if (m / 60 % 12 == 0) 12 else m / 60 % 12, m % 60, if (m / 60 < 12) "AM" else "PM")

private val dayChips = listOf(
    Calendar.MONDAY to "Mon", Calendar.TUESDAY to "Tue", Calendar.WEDNESDAY to "Wed", Calendar.THURSDAY to "Thu",
    Calendar.FRIDAY to "Fri", Calendar.SATURDAY to "Sat", Calendar.SUNDAY to "Sun"
)

@Composable
fun VariantEditor(
    variants: SnapshotStateList<ContentVariant>,
    timeChoices: List<Int>,
    personChoices: List<String>,
    showGreeting: Boolean,
    pickClip: (ContentVariant) -> Unit
) {
    Text("Different content for certain times, days or people (optional)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    Text(
        "For example 8:00 AM \"take your medicine\", 12:00 PM \"had lunch?\". Anything left blank uses the main message " +
            "and voice clip above; the first matching variant wins. Pick the times in \"Times each day\" first, then choose them here.",
        fontSize = 11.sp, color = Color.Gray
    )
    variants.toList().forEachIndexed { index, v ->
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Variant ${index + 1}", fontWeight = FontWeight.Bold)
                    TextButton(onClick = { variants.remove(v) }) { Text("Remove") }
                }
                if (timeChoices.isNotEmpty()) {
                    Text("At these times (none = any)", fontSize = 11.sp, color = Color.Gray)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        timeChoices.forEach { m ->
                            FilterChip(
                                selected = m in v.times,
                                onClick = { v.times = if (m in v.times) v.times - m else v.times + m },
                                label = { Text(timeLabel(m)) }
                            )
                        }
                    }
                }
                Text("On these days (none = every day)", fontSize = 11.sp, color = Color.Gray)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    dayChips.forEach { (d, label) ->
                        FilterChip(
                            selected = d in v.weekdays,
                            onClick = { v.weekdays = if (d in v.weekdays) v.weekdays - d else v.weekdays + d },
                            label = { Text(label) }
                        )
                    }
                }
                if (personChoices.isNotEmpty()) {
                    Text("For these people (none = everyone chosen)", fontSize = 11.sp, color = Color.Gray)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        personChoices.forEach { p ->
                            FilterChip(
                                selected = p in v.persons,
                                onClick = { v.persons = if (p in v.persons) v.persons - p else v.persons + p },
                                label = { Text(p) }
                            )
                        }
                    }
                }
                if (showGreeting) {
                    OutlinedTextField(
                        value = v.greeting, onValueChange = { v.greeting = it },
                        label = { Text("Greeting for this variant") }, modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = v.message, onValueChange = { v.message = it },
                    label = { Text("Message for this variant") }, modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickClip(v) }) { Text(if (v.clipUri == null) "Voice clip for this variant" else "Change voice clip") }
                    if (v.clipUri != null) TextButton(onClick = { v.clipUri = null }) { Text("Remove clip") }
                }
            }
        }
    }
    OutlinedButton(onClick = { variants.add(ContentVariant()) }, modifier = Modifier.fillMaxWidth()) {
        Text("Add a variant")
    }
}
