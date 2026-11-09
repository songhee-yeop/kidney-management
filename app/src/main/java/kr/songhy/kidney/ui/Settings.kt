@file:OptIn(ExperimentalLayoutApi::class)

package kr.songhy.kidney.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import kr.songhy.kidney.UiBus
import kr.songhy.kidney.data.AppData
import kr.songhy.kidney.data.Profile
import kr.songhy.kidney.data.SlotConfig
import kr.songhy.kidney.data.Store
import kr.songhy.kidney.domain.Kfre
import kr.songhy.kidney.notify.CaptureService
import kr.songhy.kidney.notify.Scheduler
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// ---------- 권한 ----------

object Perms {
    fun notifications(ctx: Context) = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    fun exactAlarm(ctx: Context) = Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    fun battery(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    fun allOk(ctx: Context) = notifications(ctx) && exactAlarm(ctx) && battery(ctx)

    fun openNotificationSettings(ctx: Context) = ctx.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
    )
    fun openExactAlarm(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))) }
        }
    }
    @android.annotation.SuppressLint("BatteryLife")
    fun requestBattery(ctx: Context) {
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
        }.onFailure { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

/** 하나라도 빠졌으면 홈 화면 맨 위에 표시 */
@Composable
fun PermissionCard() {
    val ctx = LocalContext.current
    UiBus.resumeTick.intValue // 설정에서 돌아오면 다시 확인
    if (Perms.allOk(ctx)) return
    Section("알림이 제때 오도록 설정이 필요합니다") {
        PermissionRows()
    }
}

@Composable
private fun PermissionRows() {
    val ctx = LocalContext.current
    UiBus.resumeTick.intValue
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        UiBus.resumeTick.intValue++
        Scheduler.sync(ctx)
    }
    PermRow("알림 표시", Perms.notifications(ctx)) {
        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else Perms.openNotificationSettings(ctx)
    }
    PermRow("정확한 시간 알람", Perms.exactAlarm(ctx)) { Perms.openExactAlarm(ctx) }
    PermRow("배터리 최적화 예외", Perms.battery(ctx)) { Perms.requestBattery(ctx) }
    Muted("삼성 폰은 배터리 최적화 예외가 없으면 30분 간격 알림이 늦거나 빠질 수 있습니다. 설정 → 배터리 → 백그라운드 사용 한도에서 '사용하지 않는 앱을 절전 상태로 전환'에 이 앱이 들어가 있지 않은지도 확인해 주세요.")
}

@Composable
private fun PermRow(label: String, ok: Boolean, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        if (ok) Text("허용됨", color = Sem.good, fontWeight = FontWeight.SemiBold)
        else OutlinedButton(onClick = onFix) { Text("설정하기") }
    }
}

// ---------- 처음 실행 ----------

@Composable
fun OnboardingScreen() {
    val ctx = LocalContext.current
    var birth by remember { mutableStateOf<LocalDate?>(null) }
    var male by remember { mutableStateOf<Boolean?>(null) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Scheduler.sync(ctx) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("신장 관리", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("신부전 위험도(KFRE)와 eGFR 계산에 나이와 성별이 필요합니다. 한 번만 입력하면 됩니다.")
        OutlinedButton(onClick = { pickDate(ctx, birth ?: LocalDate.of(1990, 1, 1)) { birth = it } }, modifier = Modifier.fillMaxWidth()) {
            Text(birth?.let { "생년월일 ${it.fmt()}" } ?: "생년월일 선택")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = male == true, onClick = { male = true }, label = { Text("남성") })
            FilterChip(selected = male == false, onClick = { male = false }, label = { Text("여성") })
        }
        Button(
            enabled = birth != null && male != null,
            onClick = {
                Store.update { it.copy(profile = Profile(birth!!, male!!), trackingSince = LocalDateTime.now()) }
                if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                Scheduler.sync(ctx)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("시작하기") }
        Muted("모든 기록은 이 휴대폰 안에만 저장되고 어디로도 전송되지 않습니다.")
    }
}

// ---------- 설정 ----------

@Composable
fun SettingsScreen(data: AppData) {
    val ctx = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        message = runCatching {
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(Store.exportText().toByteArray()) }
            "백업 파일을 저장했습니다."
        }.getOrElse { "저장하지 못했습니다: ${it.message}" }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        message = runCatching {
            val text = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            Store.importText(text)
            Scheduler.sync(ctx)
            "백업을 불러왔습니다."
        }.getOrElse { "백업 파일을 읽지 못했습니다. 이 앱에서 내보낸 파일인지 확인해 주세요." }
    }

    LazyColumn(
        Modifier.statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("설정", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }

        item {
            val p = data.profile!!
            Section("내 정보") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("생년월일", modifier = Modifier.weight(1f))
                    TextButton(onClick = { pickDate(ctx, p.birth) { b -> Store.update { it.copy(profile = p.copy(birth = b)) } } }) {
                        Text("${p.birth.fmt()} (만 ${Kfre.ageAt(p.birth, LocalDate.now())}세)")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("성별", modifier = Modifier.weight(1f))
                    FilterChip(selected = p.male, onClick = { Store.update { it.copy(profile = p.copy(male = true)) } }, label = { Text("남성") })
                    FilterChip(selected = !p.male, onClick = { Store.update { it.copy(profile = p.copy(male = false)) } }, label = { Text("여성") })
                }
            }
        }

        data.slots.forEach { cfg -> item(key = cfg.slot.name) { SlotEditor(cfg) } }

        item { Section("알림 권한") { PermissionRows() } }

        item { CaptureSection() }

        item {
            Section("백업 · 복원") {
                Muted("기록은 이 휴대폰에만 있습니다. 휴대폰을 바꾸기 전에 백업 파일을 내보내 두세요. 불러오면 지금 기록이 백업 내용으로 바뀝니다.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        exportLauncher.launch("신장관리_백업_${LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)}.json")
                    }, modifier = Modifier.weight(1f)) { Text("내보내기") }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, modifier = Modifier.weight(1f)) {
                        Text("불러오기")
                    }
                }
            }
        }

        message?.let { m -> item { Text(m, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) } }

        item {
            Section("계산 방식") {
                Muted(
                    "신부전 위험: Tangri KFRE (비북미 보정). 알부민·인·중탄산·칼슘이 모두 있으면 8변수, 없으면 4변수 모델.\n" +
                        "eGFR이 결과에 없으면 Creatinine으로 CKD-EPI 2021 식을 써서 계산합니다.\n" +
                        "보정 칼슘 = 칼슘 + 0.8 × (4.0 − 알부민).\n" +
                        "참고용이며 진료 판단은 주치의와 상의하세요.",
                )
            }
        }
    }
}

@Composable
private fun CaptureSection() {
    val ctx = LocalContext.current
    UiBus.resumeTick.intValue
    val on = CaptureService.isEnabled(ctx)
    Section(
        "My 인하 자동 읽기",
        action = { Text(if (on) "켜짐" else "꺼짐", color = if (on) Sem.good else Sem.warn, fontWeight = FontWeight.SemiBold) },
    ) {
        val prefs = remember { ctx.getSharedPreferences(CaptureService.PREFS, android.content.Context.MODE_PRIVATE) }
        var mode by remember { mutableStateOf(prefs.getString(CaptureService.PREF_MODE, CaptureService.MODE_ALL)!!) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(CaptureService.MODE_ALL to "새 결과 전부", CaptureService.MODE_ONE to "날짜 하나").forEach { (m, label) ->
                FilterChip(selected = mode == m, onClick = { mode = m; prefs.edit().putString(CaptureService.PREF_MODE, m).apply() }, label = { Text(label) })
            }
        }
        Muted(
            if (mode == CaptureService.MODE_ALL)
                "1. My 인하 → 검사결과조회에서 기간(예: 1년)을 정하고 조회합니다.\n" +
                    "2. 사람 모양 접근성 버튼을 누릅니다.\n" +
                    "3. 목록의 날짜 중 앱에 아직 없는 날짜만 차례로 펼쳐 읽고 미리보기를 엽니다. 확인 후 저장하면 끝.\n" +
                    "날짜 하나에 20~40초 걸립니다. 읽는 중에 버튼을 다시 누르면 멈추고, 그때까지 읽은 것만 보여줍니다."
            else
                "1. My 인하 → 검사결과조회에서 가져올 날짜가 화면 위쪽에 보이게 합니다.\n" +
                    "2. 사람 모양 접근성 버튼을 누릅니다.\n" +
                    "3. 그 날짜만 펼쳐 읽고 미리보기를 엽니다. 이미 저장된 날짜면 값이 합쳐집니다.",
        )
        if (!on) {
            Muted("처음 한 번 설정 → 접근성 → 설치된 앱 → '신장 관리 · 검사결과 읽기'를 켜주세요. '제한된 설정' 안내가 나오면 설정 → 애플리케이션 → 신장 관리 → 오른쪽 위 ⋮ → '제한된 설정 허용' 후 다시 켜면 됩니다.")
        }
        OutlinedButton(
            onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (on) "접근성 설정 열기" else "접근성 설정에서 켜기") }
        Muted("버튼을 누를 때만 화면을 읽고, 읽은 내용은 휴대폰 밖으로 나가지 않습니다.")
    }
}

@Composable
private fun SlotEditor(cfg: SlotConfig) {
    val ctx = LocalContext.current
    var msg by remember(cfg.message) { mutableStateOf(cfg.message) }
    fun save(c: SlotConfig) { Store.update { it.withSlot(c) }; Scheduler.sync(ctx) }

    Section("${cfg.slot.label}약 알림", action = { Switch(checked = cfg.enabled, onCheckedChange = { save(cfg.copy(enabled = it)) }) }) {
        OutlinedTextField(
            msg, { msg = it }, label = { Text("알림 문구") }, modifier = Modifier.fillMaxWidth(),
            trailingIcon = { if (msg != cfg.message) TextButton(onClick = { save(cfg.copy(message = msg.trim())) }) { Text("저장") } },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("시간", modifier = Modifier.weight(1f))
            TextButton(onClick = { pickTime(ctx, cfg.start) { save(cfg.copy(start = it)) } }) { Text(cfg.start.fmt()) }
            Text("~")
            TextButton(onClick = { pickTime(ctx, cfg.end) { save(cfg.copy(end = it)) } }) { Text(cfg.end.fmt()) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("간격", modifier = Modifier.weight(1f))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(15, 30, 60).forEach { m ->
                    FilterChip(selected = cfg.intervalMin == m, onClick = { save(cfg.copy(intervalMin = m)) }, label = { Text("${m}분") })
                }
            }
        }
        HorizontalDivider()
        Muted("소리·진동 없이 상태바에 표시되고, '모두 복용 확인'을 누를 때까지 사라지지 않습니다.")
    }
}
