package kr.songhy.kidney.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kr.songhy.kidney.data.AppData
import kr.songhy.kidney.data.Store
import kr.songhy.kidney.data.Vital
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@Composable
fun VitalsScreen(data: AppData) {
    val ctx = LocalContext.current
    var sys by rememberSaveable { mutableStateOf("") }
    var dia by rememberSaveable { mutableStateOf("") }
    var pulse by rememberSaveable { mutableStateOf("") }
    var weight by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val vitals = data.vitals.sortedBy { it.time }
    val primary = MaterialTheme.colorScheme.primary

    LazyColumn(
        Modifier.statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("혈압 · 체중", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            Section(
                "기록하기",
                action = { TextButton(onClick = { pickDate(ctx, LocalDate.parse(date)) { date = it.toString() } }) { Text(LocalDate.parse(date).fmt()) } },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("수축기", sys, { sys = it }, Modifier.weight(1f))
                    NumField("이완기", dia, { dia = it }, Modifier.weight(1f))
                    NumField("맥박", pulse, { pulse = it }, Modifier.weight(1f))
                }
                NumField("체중 (kg)", weight, { weight = it }, Modifier.fillMaxWidth(), decimal = true)
                error?.let { Text(it, color = Sem.bad, style = MaterialTheme.typography.bodySmall) }
                Button(
                    onClick = {
                        val s = sys.toIntOrNull(); val d = dia.toIntOrNull(); val p = pulse.toIntOrNull()
                        val w = weight.replace(',', '.').toDoubleOrNull()
                        error = when {
                            (s == null) != (d == null) -> "혈압은 수축기와 이완기를 함께 입력해 주세요."
                            s == null && p == null && w == null -> "입력한 값이 없습니다."
                            else -> null
                        }
                        if (error == null) {
                            val day = LocalDate.parse(date)
                            val t = if (day == LocalDate.now()) LocalDateTime.now() else day.atTime(9, 0)
                            Store.update { it.copy(vitals = it.vitals + Vital(UUID.randomUUID().toString(), t, s, d, p, w)) }
                            sys = ""; dia = ""; pulse = ""; weight = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("저장") }
            }
        }
        item {
            Section("혈압") {
                val bp = vitals.filter { it.sys != null && it.dia != null }
                LineChart(
                    listOf(
                        Series("수축기", primary, bp.map { Pt(it.time, it.sys!!.toDouble()) }),
                        Series("이완기", Sem.series2, bp.map { Pt(it.time, it.dia!!.toDouble()) }),
                    ),
                    refs = listOf(RefLine(130.0, "130"), RefLine(80.0, "80")),
                    decimals = 0,
                )
                Muted("mmHg · 점선은 130/80")
            }
        }
        item {
            Section("체중") {
                LineChart(listOf(Series("체중", primary, vitals.mapNotNull { v -> v.weight?.let { Pt(v.time, it) } })), decimals = 1)
                Muted("kg")
            }
        }
        item { Text("최근 기록", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (vitals.isEmpty()) item { Muted("아직 기록이 없습니다.") }
        items(vitals.reversed().take(60), key = { it.id }) { v ->
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(10.dp)).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Muted("${v.time.toLocalDate().fmt()} ${v.time.toLocalTime().fmt()}")
                    Text(
                        listOfNotNull(
                            if (v.sys != null && v.dia != null) "${v.sys}/${v.dia} mmHg" else null,
                            v.pulse?.let { "맥박 $it" },
                            v.weight?.let { "${fmtNum(it, 1)} kg" },
                        ).joinToString(" · "),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                IconButton(onClick = { Store.update { d -> d.copy(vitals = d.vitals.filterNot { it.id == v.id }) } }) {
                    Icon(Icons.Filled.Delete, contentDescription = "삭제", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier, decimal: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = { s -> onChange(s.filter { it.isDigit() || (decimal && (it == '.' || it == ',')) }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}
