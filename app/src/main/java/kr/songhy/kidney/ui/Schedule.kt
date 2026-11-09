package kr.songhy.kidney.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kr.songhy.kidney.data.AppData
import kr.songhy.kidney.data.Appointment
import kr.songhy.kidney.data.Store
import kr.songhy.kidney.notify.Scheduler
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

@Composable
fun ScheduleScreen(data: AppData, now: LocalDateTime) {
    var editing by remember { mutableStateOf<Appointment?>(null) }
    val upcoming = data.appointments.filter { it.time.isAfter(now) }.sortedBy { it.time }
    val past = data.appointments.filterNot { it.time.isAfter(now) }.sortedByDescending { it.time }

    LazyColumn(
        Modifier.statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("진료 일정", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Button(onClick = {
                    editing = Appointment("", LocalDate.now().plusDays(7).atTime(10, 0), "신장내과 방문", "")
                }) { Text("+ 일정") }
            }
        }
        item { Muted("전날 오전 10:00에 \"내일 ○○:○○ 신장내과 방문입니다\"와 메모가 상태바에 표시됩니다.") }
        if (upcoming.isEmpty()) item { Muted("예정된 일정이 없습니다.") }
        items(upcoming, key = { it.id }) { a -> ApptRow(a, past = false) { editing = a } }
        if (past.isNotEmpty()) {
            item { Text("지난 일정", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
            items(past.take(20), key = { it.id }) { a -> ApptRow(a, past = true) { editing = a } }
        }
    }

    editing?.let { a -> ApptDialog(a, onDismiss = { editing = null }) }
}

@Composable
private fun ApptRow(a: Appointment, past: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .alpha(if (past) 0.6f else 1f)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("${a.time.toLocalDate().fmtDow()} ${a.time.toLocalTime().fmt()}", fontWeight = FontWeight.Bold)
        Text(a.title)
        if (a.note.isNotBlank()) {
            Text(
                a.note, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().background(Sem.warn.copy(alpha = 0.12f), RoundedCornerShape(8.dp)).padding(8.dp),
            )
        }
    }
}

@Composable
private fun ApptDialog(a: Appointment, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val isNew = a.id.isEmpty()
    var date by remember { mutableStateOf(a.time.toLocalDate()) }
    var time by remember { mutableStateOf(a.time.toLocalTime()) }
    var title by remember { mutableStateOf(a.title) }
    var note by remember { mutableStateOf(a.note) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "일정 추가" else "일정 수정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickDate(ctx, date) { date = it } }, modifier = Modifier.weight(1.4f)) { Text(date.fmtDow()) }
                    OutlinedButton(onClick = { pickTime(ctx, time) { time = it } }, modifier = Modifier.weight(1f)) { Text(time.fmt()) }
                }
                OutlinedTextField(title, { title = it }, label = { Text("내용") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    note, { note = it }, label = { Text("메모 (알림에 표시)") },
                    placeholder = { Text("예: 전날 저녁 9시부터 금식") },
                    minLines = 3, modifier = Modifier.fillMaxWidth(),
                )
                val remind = date.minusDays(1).atTime(LocalTime.of(10, 0))
                Muted("알림: ${remind.toLocalDate().fmtDow()} 10:00")
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    val saved = a.copy(id = a.id.ifEmpty { UUID.randomUUID().toString() }, time = date.atTime(time), title = title.trim(), note = note.trim())
                    Store.update { d -> d.copy(appointments = d.appointments.filterNot { it.id == saved.id } + saved) }
                    Scheduler.resetAppointment(ctx, saved.id)
                    Scheduler.sync(ctx)
                    onDismiss()
                },
            ) { Text("저장") }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = {
                        if (!confirmDelete) { confirmDelete = true; return@TextButton }
                        Store.update { d -> d.copy(appointments = d.appointments.filterNot { it.id == a.id }) }
                        Scheduler.resetAppointment(ctx, a.id)
                        onDismiss()
                    }) { Text(if (confirmDelete) "정말 삭제" else "삭제", color = Sem.bad) }
                }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        },
    )
}
