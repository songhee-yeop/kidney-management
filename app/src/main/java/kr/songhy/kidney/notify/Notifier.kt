package kr.songhy.kidney.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kr.songhy.kidney.MainActivity
import kr.songhy.kidney.R
import kr.songhy.kidney.data.Appointment
import kr.songhy.kidney.data.Slot
import kr.songhy.kidney.data.SlotConfig
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 모든 알림은 소리·진동 없이 상태바에만 표시된다 (중요도 LOW 채널). */
object Notifier {
    const val CH_MEDS = "meds"
    const val CH_APPT = "appointments"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        listOf(CH_MEDS to "복약 알림", CH_APPT to "진료 일정").forEach { (id, name) ->
            val ch = NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(true)
            }
            nm.createNotificationChannel(ch)
        }
    }

    fun doseId(slot: Slot) = 100 + slot.ordinal
    fun apptId(id: String) = 2000 + (id.hashCode() and 0xFFFF)

    private fun canPost(ctx: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun receiver(ctx: Context, action: String, req: Int, extras: Intent.() -> Unit) = PendingIntent.getBroadcast(
        ctx, req, Intent(ctx, AlarmReceiver::class.java).setAction(action).apply(extras),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** 지울 수 없는 알림. "모두 복용 확인"을 눌러야만 사라진다. */
    fun postDose(ctx: Context, cfg: SlotConfig, date: LocalDate) {
        if (!canPost(ctx)) return
        val extras: Intent.() -> Unit = { putExtra("slot", cfg.slot.name); putExtra("date", date.toString()) }
        val confirm = receiver(ctx, AlarmReceiver.ACTION_CONFIRM, 10 + cfg.slot.ordinal, extras)
        val dismissed = receiver(ctx, AlarmReceiver.ACTION_DISMISSED, 20 + cfg.slot.ordinal, extras)
        val sub = if (date != LocalDate.now()) "어제 ${cfg.slot.label} 복용을 아직 확인하지 않았습니다"
        else "다 드셨으면 '모두 복용 확인'을 눌러주세요"
        val n = NotificationCompat.Builder(ctx, CH_MEDS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(cfg.message)
            .setContentText(sub)
            .setStyle(NotificationCompat.BigTextStyle().bigText(sub))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(true)
            .setContentIntent(openApp(ctx))
            .setDeleteIntent(dismissed)
            .addAction(0, "모두 복용 확인", confirm)
            .build()
        n.flags = n.flags or android.app.Notification.FLAG_NO_CLEAR
        runCatching { NotificationManagerCompat.from(ctx).notify(doseId(cfg.slot), n) }
    }

    fun cancelDose(ctx: Context, slot: Slot) = NotificationManagerCompat.from(ctx).cancel(doseId(slot))

    fun postAppointment(ctx: Context, a: Appointment) {
        if (!canPost(ctx)) return
        val day = when (a.time.toLocalDate()) {
            LocalDate.now() -> "오늘"
            LocalDate.now().plusDays(1) -> "내일"
            else -> a.time.format(DateTimeFormatter.ofPattern("M월 d일"))
        }
        val title = "$day ${a.time.format(DateTimeFormatter.ofPattern("HH:mm"))} ${a.title}입니다"
        val body = a.note.ifBlank { "메모 없음" }
        val n = NotificationCompat.Builder(ctx, CH_APPT)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(apptId(a.id), n) }
    }

    fun cancelAppointment(ctx: Context, id: String) = NotificationManagerCompat.from(ctx).cancel(apptId(id))
}
