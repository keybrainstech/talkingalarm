package com.example.talkingalarm

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.util.Calendar

class MainActivity : ComponentActivity() {

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlarmStore.ensureLoaded(this)
        AlarmScheduler.rescheduleAll(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent { TalkingAlarmTheme { AlarmListScreen() } }
    }

    override fun onDestroy() {
        Speaker.release()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmListScreen() {
    val context = LocalContext.current
    val alarms by AlarmStore.alarms.collectAsState()
    var editing by remember { mutableStateOf<Alarm?>(null) }
    var editorOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Talking Alarm", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { editing = null; editorOpen = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) { Icon(Icons.Default.Add, contentDescription = "New alarm") }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { NextAlarmLine(alarms) }
            item { ReliabilityCard() }

            if (alarms.isEmpty()) {
                item {
                    Text(
                        "No alarms yet. Tap + to add one, write what it should say, and it will read that out loud at the time you set.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 32.dp)
                    )
                }
            }

            items(alarms, key = { it.id }) { alarm ->
                AlarmCard(
                    alarm = alarm,
                    onToggle = { on ->
                        val updated = alarm.copy(enabled = on)
                        AlarmStore.upsert(context, updated)
                        AlarmScheduler.schedule(context, updated)
                    },
                    onEdit = { editing = alarm; editorOpen = true },
                    onPreview = { Speaker.preview(context, alarm.text) },
                    onDelete = {
                        AlarmScheduler.cancel(context, alarm)
                        AlarmStore.delete(context, alarm.id)
                    }
                )
            }
        }
    }

    if (editorOpen) {
        AlarmEditor(
            existing = editing,
            onDismiss = { editorOpen = false },
            onSave = { saved ->
                AlarmStore.upsert(context, saved)
                AlarmScheduler.schedule(context, saved)
                editorOpen = false
            }
        )
    }
}

@Composable
private fun NextAlarmLine(alarms: List<Alarm>) {
    val next = alarms.filter { it.enabled }
        .minByOrNull { AlarmScheduler.nextTriggerMillis(it) }

    val label = if (next == null) {
        "Nothing scheduled"
    } else {
        val minutes = ((AlarmScheduler.nextTriggerMillis(next) - System.currentTimeMillis()) / 60000L)
            .coerceAtLeast(0)
        val h = minutes / 60
        val m = minutes % 60
        val gap = if (h > 0) "in ${h}h ${m}m" else "in ${m}m"
        "Next: ${next.timeText()}, $gap"
    }

    Text(
        label,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 15.sp,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun AlarmCard(
    alarm: Alarm,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onPreview: () -> Unit,
    onDelete: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        alarm.timeText(),
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Light,
                        color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        alarm.daysText(),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = alarm.enabled, onCheckedChange = onToggle)
            }

            if (alarm.text.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    alarm.text,
                    fontSize = 17.sp,
                    lineHeight = 24.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onPreview) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Hear this alarm",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete alarm",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        Dialog(onDismissRequest = { confirmDelete = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("Delete this alarm?", fontSize = 18.sp)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { confirmDelete = false }) { Text("Keep") }
                        TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlarmEditor(
    existing: Alarm?,
    onDismiss: () -> Unit,
    onSave: (Alarm) -> Unit
) {
    val context = LocalContext.current
    val now = Calendar.getInstance()

    var hour by remember { mutableIntStateOf(existing?.hour ?: now.get(Calendar.HOUR_OF_DAY)) }
    var minute by remember { mutableIntStateOf(existing?.minute ?: 0) }
    var text by remember { mutableStateOf(existing?.text ?: "") }
    var days by remember { mutableStateOf(existing?.days ?: emptySet()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp)
            ) {
                Text(
                    if (existing == null) "New alarm" else "Edit alarm",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(18.dp))

                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(18.dp)
                        )
                        .clickable {
                            TimePickerDialog(
                                context,
                                android.R.style.Theme_Material_Dialog_Alert,
                                { _, h, m -> hour = h; minute = m },
                                hour, minute, false
                            ).show()
                        }
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        Alarm(0, hour, minute, "").timeText(),
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Light,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    "Tap the time to change it",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )

                Spacer(Modifier.height(18.dp))

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("What should it say?") },
                    placeholder = { Text("Take your medicine and drink water") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences
                    )
                )

                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { Speaker.preview(context, text) }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Hear it")
                }

                Spacer(Modifier.height(18.dp))
                Text("Repeat", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))

                val dayLabels = mapOf(
                    Calendar.MONDAY to "Mon", Calendar.TUESDAY to "Tue",
                    Calendar.WEDNESDAY to "Wed", Calendar.THURSDAY to "Thu",
                    Calendar.FRIDAY to "Fri", Calendar.SATURDAY to "Sat",
                    Calendar.SUNDAY to "Sun"
                )

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Alarm.WEEK_ORDER.forEach { day ->
                        val selected = days.contains(day)
                        FilterChip(
                            selected = selected,
                            onClick = {
                                days = if (selected) days - day else days + day
                            },
                            label = { Text(dayLabels[day] ?: "") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }
                Text(
                    if (days.isEmpty()) "No days picked: it will ring once, at the next time this clock hits."
                    else "Rings every week on the days above.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )

                Spacer(Modifier.height(22.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            val id = existing?.id ?: AlarmStore.nextId(context)
                            onSave(
                                Alarm(
                                    id = id,
                                    hour = hour,
                                    minute = minute,
                                    text = text.trim(),
                                    enabled = true,
                                    days = days
                                )
                            )
                        }
                    ) { Text("Save alarm") }
                }
            }
        }
    }
}

/**
 * Android kills background apps aggressively on many phones. This card points at the
 * two settings that actually decide whether an alarm fires on time.
 */
@Composable
private fun ReliabilityCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val unrestricted = remember(tick) {
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
    }

    if (unrestricted) return

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Alarms may be delayed", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Battery saving is allowed to sleep this app. Turn it off so alarms ring exactly on time.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { openBatterySettings(context) }) {
                Text("Allow background running")
            }
        }
    }
}

private fun openBatterySettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:" + context.packageName))
        )
    }.onFailure {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
