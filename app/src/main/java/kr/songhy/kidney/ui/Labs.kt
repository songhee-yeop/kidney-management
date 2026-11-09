@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package kr.songhy.kidney.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kr.songhy.kidney.data.AppData
import kr.songhy.kidney.data.LabRecord
import kr.songhy.kidney.data.LabValue
import kr.songhy.kidney.data.Store
import kr.songhy.kidney.domain.Catalog
import kr.songhy.kidney.domain.Report
import kr.songhy.kidney.domain.Kfre
import kr.songhy.kidney.domain.LabParser
import java.time.LocalDate

@Composable
fun LabsScreen(data: AppData, onPaste: () -> Unit) {
    val p = data.profile ?: return
    val recs = data.labsSorted
    val evals = remember(recs, p) { recs.map { it to Kfre.evaluate(it, p) } }
    var detail by remember { mutableStateOf<LabRecord?>(null) }
    var extra by rememberSaveable { mutableStateOf<String?>(null) }
    var allCopied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    fun pts(key: String) = recs.mapNotNull { r -> r.values[key]?.let { Pt(r.date.atStartOfDay(), it.value) } }
    val primary = MaterialTheme.colorScheme.primary

    LazyColumn(
        Modifier.statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("검사 수치", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Button(onClick = onPaste) { Text("+ 검사결과") }
            }
        }
        item {
            Section("eGFR") {
                val calc = evals.any { it.second.egfrCalc }
                LineChart(
                    listOf(Series("eGFR", primary, evals.mapNotNull { (r, e) -> e.egfr?.let { Pt(r.date.atStartOfDay(), it) } })),
                    refs = listOf(RefLine(90.0, "G1"), RefLine(60.0, "G2"), RefLine(45.0, "G3a"), RefLine(30.0, "G3b"), RefLine(15.0, "G4")),
                )
                Muted("mL/min/1.73㎡ · 점선은 병기 하한" + if (calc) " · 일부는 크레아티닌으로 계산한 값" else "")
            }
        }
        item {
            Section("Creatinine") {
                LineChart(listOf(Series("Cr", primary, pts("cr"))), decimals = 2)
                Muted("mg/dL")
            }
        }
        item {
            Section("ACR (소변 알부민/크레아티닌 비)") {
                LineChart(listOf(Series("ACR", primary, pts("acr"))), refs = listOf(RefLine(30.0, "A2"), RefLine(300.0, "A3")))
                Muted("mg/g · 30 미만 A1, 30~300 A2, 300 초과 A3")
            }
        }
        item {
            Section("신부전 위험 (KFRE)") {
                val r = evals.filter { it.second.risk != null }
                LineChart(
                    listOf(
                        Series("2년", primary, r.map { Pt(it.first.date.atStartOfDay(), it.second.risk!!.r2) }),
                        Series("5년", Sem.series2, r.map { Pt(it.first.date.atStartOfDay(), it.second.risk!!.r5) }),
                    ),
                    refs = listOf(RefLine(3.0, "5년 3% 의뢰"), RefLine(10.0, "2년 10% 팀진료")),
                    decimals = 2,
                )
                Muted("% · 검사마다 그 시점 수치로 다시 계산")
            }
        }
        val others = recs.flatMap { it.values.keys }.distinct()
            .filter { it !in setOf("egfr", "cr", "acr") }
            .sortedWith(compareBy({ Catalog.order(it) }, { it }))
        if (others.isNotEmpty()) {
            item {
                Section("다른 항목") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        others.forEach { k ->
                            val name = Catalog.name(k, recs.lastOrNull { it.values.containsKey(k) }?.values?.get(k))
                            FilterChip(selected = extra == k, onClick = { extra = if (extra == k) null else k }, label = { Text(name) })
                        }
                    }
                    extra?.takeIf { it in others }?.let { k ->
                        val last = recs.lastOrNull { it.values.containsKey(k) }?.values?.get(k)
                        LineChart(listOf(Series(Catalog.name(k, last), primary, pts(k))), decimals = 2)
                        Muted(Catalog.unit(k, last) + (last?.let { v -> refText(v)?.let { " · 참고치 $it" } } ?: ""))
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("검사 기록", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (recs.isNotEmpty()) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(Report.all(recs, p))); allCopied = true }) {
                        Text(if (allCopied) "모든 기록 복사됨" else "모든 기록 복사")
                    }
                }
            }
            Muted("기록을 누르면 상세 내용과 '전체 내용 복사'가 나옵니다. 복사해서 ChatGPT 등에 붙여넣어 물어볼 수 있습니다.")
        }
        if (recs.isEmpty()) item { Muted("아직 검사 기록이 없습니다.") }
        items(recs.reversed(), key = { it.date.toString() }) { r ->
            val e = evals.find { it.first.date == r.date }?.second
            Column(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(10.dp))
                    .clickable { detail = r }
                    .padding(12.dp),
            ) {
                Row {
                    Text(r.date.fmt(), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    e?.risk?.let { Text("5년 ${fmtNum(it.r5, 2)}%", fontWeight = FontWeight.SemiBold) }
                }
                Muted(
                    listOfNotNull(
                        e?.egfr?.let { "eGFR ${fmtNum(it, 1)}" },
                        r.values["cr"]?.let { "Cr ${fmtNum(it.value, 2)}" },
                        r.values["acr"]?.let { "ACR ${fmtNum(it.value, 1)}" },
                        "${r.values.size}개 항목",
                    ).joinToString(" · "),
                )
            }
        }
    }

    detail?.let { r -> RecordDialog(r, data, onDismiss = { detail = null }) }
}

private fun refText(v: LabValue): String? = when {
    v.lo != null && v.hi != null -> "${fmtNum(v.lo)}~${fmtNum(v.hi)}"
    v.hi != null -> "< ${fmtNum(v.hi)}"
    v.lo != null -> "> ${fmtNum(v.lo)}"
    else -> null
}

@Composable
private fun RecordDialog(r: LabRecord, data: AppData, onDismiss: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val prev = data.labsSorted.lastOrNull { it.date < r.date }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${r.date.fmt()} 검사") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                r.values.entries.sortedWith(compareBy({ Catalog.order(it.key) }, { it.key })).forEach { (k, v) ->
                    val flag = Catalog.flag(k, v)
                    val pv = prev?.values?.get(k)?.value
                    val worse = pv != null && Catalog.worsened(k, pv, v.value)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(Catalog.name(k, v), style = MaterialTheme.typography.bodyMedium)
                            refText(v)?.let { Muted("참고치 $it") }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "${fmtNum(v.value, 3)} ${Catalog.unit(k, v)}${flag?.let { " $it" } ?: ""}",
                                fontWeight = FontWeight.SemiBold,
                                color = if (flag != null) Sem.bad else MaterialTheme.colorScheme.onSurface,
                            )
                            if (pv != null) {
                                val d = v.value - pv
                                Text(
                                    "직전 ${fmtNum(pv, 3)} (${if (d >= 0) "+" else ""}${fmtNum(d, 2)})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (worse) Sem.warn else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(Report.record(r, data.profile, prev)))
                    copied = true
                }) { Text(if (copied) "복사됨" else "전체 내용 복사") }
                TextButton(onClick = onDismiss) { Text("닫기") }
            }
        },
        dismissButton = {
            if (confirmDelete) {
                TextButton(onClick = {
                    Store.update { d -> d.copy(labs = d.labs.filterNot { it.date == r.date }) }
                    onDismiss()
                }) { Text("정말 삭제", color = Sem.bad) }
            } else {
                TextButton(onClick = { confirmDelete = true }) { Text("삭제") }
            }
        },
    )
}

@Composable
private fun EditValueDialog(name: String, initial: Double, onDone: (Double?) -> Unit) {
    var input by remember { mutableStateOf(fmtNum(initial, 4)) }
    val parsed = input.replace(',', '.').toDoubleOrNull()
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(name) },
        text = {
            OutlinedTextField(
                input, { input = it }, singleLine = true, label = { Text("값") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
            )
        },
        confirmButton = { TextButton(enabled = parsed != null, onClick = { onDone(parsed) }) { Text("확인") } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("취소") } },
    )
}

/** 검사결과 붙여넣기 / 공유받기 화면 */
@Composable
fun PasteScreen(initial: String, onClose: () -> Unit) {
    BackHandler { onClose() }
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var text by rememberSaveable { mutableStateOf(initial) }
    val blocks = remember(text) { LabParser.parseMany(text) }
    var dates by remember(blocks) { mutableStateOf(blocks.map { it.date ?: LocalDate.now() }) }
    var excluded by remember(blocks) { mutableStateOf(setOf<Pair<Int, String>>()) }
    var edits by remember(blocks) { mutableStateOf(mapOf<Pair<Int, String>, Double>()) }
    var editing by remember { mutableStateOf<Pair<Int, String>?>(null) }
    fun valuesOf(bi: Int, b: LabParser.Block) = b.values
        .filterKeys { (bi to it) !in excluded }
        .mapValues { (k, v) -> edits[bi to k]?.let { v.copy(value = it) } ?: v }
    val data = Store.data
    val profile = data.profile

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("검사결과 추가") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "닫기") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (initial.isEmpty()) {
                Muted("My 인하 결과는 검사결과조회 화면에서 사람 모양 접근성 버튼을 누르면 자동으로 들어옵니다. 다른 곳의 결과는 '항목  값  단위  참고치' 형식으로 붙여넣을 수 있습니다.")
                FilledTonalButton(onClick = { clipboard.getText()?.text?.let { text = it } }, modifier = Modifier.fillMaxWidth()) {
                    Text("클립보드 붙여넣기")
                }
            }
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                placeholder = { Text("검사일 2026-03-12\nCreatinine  1.38  mg/dL  0.50~1.20\n…") },
            )

            if (blocks.isNotEmpty()) {
                var previewCopied by remember(blocks) { mutableStateOf(false) }
                Button(
                    onClick = {
                        val recs = blocks.mapIndexed { bi, b -> LabRecord(dates.getOrElse(bi) { LocalDate.now() }, valuesOf(bi, b)) }
                            .filter { it.values.isNotEmpty() }
                        clipboard.setText(AnnotatedString(Report.all(recs, profile)))
                        previewCopied = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (previewCopied) "복사했습니다 (${blocks.size}개 날짜)" else "전체 내용 복사 (ChatGPT 등에 붙여넣기)") }
            }
            if (blocks.isNotEmpty()) Muted("수치를 누르면 고칠 수 있습니다. 화면 글자 인식은 소수점을 놓치는 경우가 있으니 주요 수치를 한 번 확인해 주세요.")
            if (text.startsWith("[읽은 원본]")) {
                Muted("화면 형식을 알아보지 못해 읽은 글자를 그대로 넣었습니다. 아래 버튼으로 복사해서 개발자(Claude)에게 보내주면 맞춰 드립니다.")
                FilledTonalButton(onClick = { clipboard.setText(AnnotatedString(text)) }, modifier = Modifier.fillMaxWidth()) {
                    Text("읽은 원본 전체 복사")
                }
            } else if (text.isNotBlank() && blocks.isEmpty()) {
                Muted("읽을 수 있는 검사 수치가 없습니다. 형식을 확인해 주세요.")
            }

            blocks.forEachIndexed { bi, b ->
                val date = dates.getOrElse(bi) { LocalDate.now() }
                val kept = valuesOf(bi, b)
                Section(
                    "${date.fmt()} 검사",
                    action = { TextButton(onClick = { pickDate(ctx, date) { d -> dates = dates.toMutableList().also { it[bi] = d } } }) { Text("날짜 변경") } },
                ) {
                    if (b.date == null) Muted("결과에 날짜가 없어 오늘 날짜로 넣었습니다. 맞지 않으면 바꿔주세요.", Modifier)
                    if (data.labs.any { it.date == date }) Muted("이 날짜에 이미 기록이 있어 값이 합쳐집니다.")
                    b.values.forEach { (k, v) ->
                        val off = (bi to k) in excluded
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                Catalog.name(k, v), modifier = Modifier.weight(1f),
                                color = if (off) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (k.startsWith("x:")) FontWeight.Normal else FontWeight.SemiBold,
                            )
                            val shown = edits[bi to k] ?: v.value
                            Text(
                                "${fmtNum(shown, 3)} ${Catalog.unit(k, v)}",
                                color = if (off) MaterialTheme.colorScheme.outline
                                else if (edits.containsKey(bi to k)) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.clickable(enabled = !off) { editing = bi to k },
                            )
                            Spacer(Modifier.width(4.dp))
                            TextButton(onClick = { excluded = if (off) excluded - (bi to k) else excluded + (bi to k) }) {
                                Text(if (off) "넣기" else "빼기")
                            }
                        }
                    }
                    if (profile != null) {
                        val ev = Kfre.evaluate(LabRecord(date, kept), profile)
                        ev.risk?.let {
                            Text(
                                "신부전 위험 2년 ${fmtNum(it.r2, 2)}% · 5년 ${fmtNum(it.r5, 2)}% (${it.model}변수)",
                                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                            )
                        } ?: Muted("eGFR(또는 Creatinine)과 ACR이 있어야 위험도가 계산됩니다.")
                    }
                }
            }

            Button(
                onClick = {
                    val recs = blocks.mapIndexed { bi, b ->
                        LabRecord(dates.getOrElse(bi) { LocalDate.now() }, valuesOf(bi, b))
                    }.filter { it.values.isNotEmpty() }
                    Store.update { it.withLabs(recs) }
                    onClose()
                },
                enabled = blocks.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (blocks.size > 1) "${blocks.size}건 저장" else "저장") }
        }
    }
    editing?.let { key ->
        val (bi, k) = key
        val v = blocks.getOrNull(bi)?.values?.get(k)
        if (v == null) editing = null
        else EditValueDialog(Catalog.name(k, v), edits[key] ?: v.value) { nv ->
            if (nv != null) edits = edits + (key to nv)
            editing = null
        }
    }
}
