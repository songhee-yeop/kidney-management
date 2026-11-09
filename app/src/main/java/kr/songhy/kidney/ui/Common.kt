package kr.songhy.kidney.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kr.songhy.kidney.domain.Kfre
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val Light = lightColorScheme(
    primary = Color(0xFF1F6F78), onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE8EB), onPrimaryContainer = Color(0xFF0B3A40),
    secondary = Color(0xFF52676B), tertiary = Color(0xFF8A5A2B),
    background = Color(0xFFF3F6F6), surface = Color(0xFFF3F6F6),
    surfaceContainerLow = Color(0xFFFFFFFF), surfaceContainer = Color(0xFFEAF0F0),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF7FCBD3), onPrimary = Color(0xFF00363C),
    primaryContainer = Color(0xFF1B4F55), onPrimaryContainer = Color(0xFFCDE8EB),
    secondary = Color(0xFFB1CBCF), tertiary = Color(0xFFE2B98C),
    background = Color(0xFF0F1415), surface = Color(0xFF0F1415),
    surfaceContainerLow = Color(0xFF172022), surfaceContainer = Color(0xFF1D2729),
)

@Composable
fun KidneyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

/** 상태 색: 좋음/주의/나쁨 — 두 테마에서 모두 읽히는 중간 톤 */
object Sem {
    val good = Color(0xFF3E9E6A)
    val warn = Color(0xFFD08A1E)
    val bad = Color(0xFFD5483B)
    val series2 = Color(0xFFC0703A)
    fun of(level: Kfre.Level) = when (level) { Kfre.Level.OK -> good; Kfre.Level.WARN -> warn; Kfre.Level.BAD -> bad }
}

val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd")
val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yy.MM.dd")
val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun LocalDate.fmt(): String = format(DATE_FMT)
fun LocalDate.fmtDow(): String = "${format(DATE_FMT)} (${dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})"
fun LocalTime.fmt(): String = format(TIME_FMT)
fun DayOfWeek.ko(): String = getDisplayName(TextStyle.SHORT, Locale.KOREAN)

fun fmtNum(v: Double, dec: Int = 2): String {
    val s = String.format(Locale.US, "%.${dec}f", v)
    return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
}

fun pickDate(ctx: Context, initial: LocalDate, onPick: (LocalDate) -> Unit) {
    DatePickerDialog(ctx, { _, y, m, d -> onPick(LocalDate.of(y, m + 1, d)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
}

fun pickTime(ctx: Context, initial: LocalTime, onPick: (LocalTime) -> Unit) {
    TimePickerDialog(ctx, { _, h, m -> onPick(LocalTime.of(h, m)) }, initial.hour, initial.minute, true).show()
}

@Composable
fun Section(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                action?.invoke()
            }
            content()
        }
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
