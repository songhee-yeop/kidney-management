package kr.songhy.kidney.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.songhy.kidney.data.AppData
import kr.songhy.kidney.data.LabRecord
import kr.songhy.kidney.domain.Catalog
import kr.songhy.kidney.domain.Kfre
import kr.songhy.kidney.notify.Scheduler
import java.time.LocalDateTime

@Composable
fun HomeScreen(data: AppData, now: LocalDateTime, onPaste: () -> Unit, onGoLabs: () -> Unit) {
    LazyColumn(
        Modifier.statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text("신장 관리", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Muted(now.toLocalDate().fmtDow())
            }
        }
        item { PermissionCard() }
        item { MedsCard(data, now) }
        item { RiskCard(data, onPaste, onGoLabs) }
        item { NextAppointmentCard(data, now) }
    }
}

@Composable
private fun MedsCard(data: AppData, now: LocalDateTime) {
    val ctx = LocalContext.current
    Section("오늘 복약") {
        data.slots.filter { it.enabled }.forEach { cfg ->
            val st = Scheduler.status(cfg, data, now)
            val prefix = if (st.date != now.toLocalDate()) "어제 " else ""
            val (stateText, color) = when {
                st.takenAt != null -> {
                    val late = st.takenAt.toLocalTime().isAfter(cfg.end) || st.takenAt.toLocalDate() != st.date
                    "복용 완료 ${st.takenAt.toLocalTime().fmt()}${if (late) " · 늦음" else ""}" to Sem.good
                }
                st.missed -> "확인하지 않음" to Sem.bad
                st.active -> "복용 확인이 필요합니다" to Sem.warn
                else -> "${cfg.start.fmt()}부터 알림" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("$prefix${cfg.slot.label}약", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Muted(cfg.message)
                    Text(stateText, color = color, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(8.dp))
                when {
                    st.takenAt != null -> TextButton(onClick = { Scheduler.undo(ctx, cfg.slot, st.date) }) { Text("취소") }
                    else -> Button(onClick = { Scheduler.confirm(ctx, cfg.slot, st.date) }) { Text("복용 확인") }
                }
            }
        }
    }
}

/** 위험도를 계산할 수 있는 가장 최근 기록과 그 이전 기록 */
fun riskHistory(data: AppData): List<Pair<LabRecord, Kfre.Eval>> {
    val p = data.profile ?: return emptyList()
    return data.labsSorted.map { it to Kfre.evaluate(it, p) }.filter { it.second.risk != null }
}

@Composable
private fun RiskCard(data: AppData, onPaste: () -> Unit, onGoLabs: () -> Unit) {
    val hist = riskHistory(data)
    val latest = hist.lastOrNull()
    Section("신부전 위험 (KFRE)", action = { TextButton(onClick = onPaste) { Text("+ 검사결과") } }) {
        if (latest == null) {
            Muted("eGFR(또는 크레아티닌)과 소변 알부민/크레아티닌 비(ACR)가 있는 검사결과를 붙여넣으면 2년·5년 위험이 계산됩니다.")
            FilledTonalButton(onClick = onPaste, modifier = Modifier.fillMaxWidth()) { Text("검사결과 붙여넣기") }
            return@Section
        }
        val (rec, ev) = latest
        val risk = ev.risk!!
        val prev = hist.getOrNull(hist.size - 2)?.second?.risk
        val (level, verdict) = Kfre.verdict(risk)
        Muted("${rec.date.fmt()} 검사 · ${risk.model}변수 모델 · 만 ${ev.age}세")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RiskNumber("2년 내", risk.r2, prev?.r2, Modifier.weight(1f))
            RiskNumber("5년 내", risk.r5, prev?.r5, Modifier.weight(1f))
        }
        Text(
            verdict, color = Sem.of(level), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth().background(Sem.of(level).copy(alpha = 0.12f), RoundedCornerShape(8.dp)).padding(10.dp),
        )
        ev.egfr?.let { e ->
            val acr = rec.values["acr"]!!.value
            Text(
                "CKD ${Kfre.gStage(e)} ${Kfre.aStage(acr)} · ${Kfre.gLabel(e)}",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            )
        }
        KeyLabs(data)
        OutlinedButton(onClick = onGoLabs, modifier = Modifier.fillMaxWidth()) { Text("그래프 보기") }
    }
}

@Composable
private fun RiskNumber(label: String, v: Double, prev: Double?, modifier: Modifier) {
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp)).padding(12.dp),
    ) {
        Muted(label)
        Text("${fmtNum(v, 2)}%", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        if (prev != null) {
            val d = v - prev
            val c = if (d > 0.005) Sem.bad else if (d < -0.005) Sem.good else MaterialTheme.colorScheme.onSurfaceVariant
            Text("지난 검사 대비 ${if (d >= 0) "+" else ""}${fmtNum(d, 2)}%p", color = c, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** eGFR · Creatinine · ACR 최근값과 직전 대비 변화 */
@Composable
private fun KeyLabs(data: AppData) {
    val p = data.profile ?: return
    val recs = data.labsSorted
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("egfr", "cr", "acr").forEach { key ->
            val series = recs.mapNotNull { r ->
                val v = if (key == "egfr") Kfre.evaluate(r, p).egfr else r.values[key]?.value
                v?.let { r.date to it }
            }
            val cur = series.lastOrNull()
            val prev = series.getOrNull(series.size - 2)
            Column(Modifier.weight(1f)) {
                Muted(if (key == "acr") "ACR" else Catalog.item(key)!!.name)
                Text(cur?.let { fmtNum(it.second, if (key == "acr") 1 else 2) } ?: "–", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (cur != null && prev != null) {
                    val d = cur.second - prev.second
                    val worse = Catalog.worsened(key, prev.second, cur.second)
                    Text(
                        "${if (d >= 0) "▲" else "▼"} ${fmtNum(kotlin.math.abs(d), 2)}",
                        color = if (worse) Sem.bad else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun NextAppointmentCard(data: AppData, now: LocalDateTime) {
    val next = data.appointments.filter { it.time.isAfter(now) }.minByOrNull { it.time }
    Section("다음 진료") {
        if (next == null) {
            Muted("예정된 일정이 없습니다. 일정 탭에서 추가하세요.")
        } else {
            Text(
                "${next.time.toLocalDate().fmtDow()} ${next.time.toLocalTime().fmt()}",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            )
            Text(next.title)
            if (next.note.isNotBlank()) {
                Text(
                    next.note, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().background(Sem.warn.copy(alpha = 0.12f), RoundedCornerShape(8.dp)).padding(10.dp),
                )
            }
            Muted("전날 오전 10:00에 알림")
        }
    }
}
