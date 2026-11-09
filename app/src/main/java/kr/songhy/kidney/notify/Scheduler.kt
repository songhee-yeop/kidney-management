package kr.songhy.kidney.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kr.songhy.kidney.data.AppData
import kr.songhy.kidney.data.DoseLog
import kr.songhy.kidney.data.Slot
import kr.songhy.kidney.data.SlotConfig
import kr.songhy.kidney.data.Store
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 알림 상태를 데이터에 맞춰 맞춘다. 앱 실행·알람·복용 확인·재부팅 때마다 sync()를 부르면 된다.
 *
 * 규칙
 * - 회차(아침/저녁)마다 시작~끝 사이 30분 간격으로 알람을 건다. 확인 전까지 알림을 다시 띄운다.
 * - 끝 시각이 지나도 확인하지 않았으면 알림은 그대로 남는다.
 * - 다른 회차가 시작되면 이전 회차 알림을 지우고 미복용으로 기록한다.
 * - 진료 일정은 전날 오전 10시에 알린다.
 */
object Scheduler {
    private const val REQ_TICK = 1

    fun lastStart(cfg: SlotConfig, now: LocalDateTime): LocalDateTime {
        val today = now.toLocalDate().atTime(cfg.start)
        return if (!today.isAfter(now)) today else today.minusDays(1)
    }

    /** 가장 최근에 시작한 회차 */
    fun currentSlot(d: AppData, now: LocalDateTime): Slot? =
        d.slots.filter { it.enabled }.maxByOrNull { lastStart(it, now) }?.slot

    data class MedStatus(val date: LocalDate, val takenAt: LocalDateTime?, val missed: Boolean, val active: Boolean)

    /** 홈 화면 카드용: 지금 확인해야 할 날짜와 상태 */
    fun status(cfg: SlotConfig, d: AppData, now: LocalDateTime): MedStatus {
        val today = now.toLocalDate()
        val ls = lastStart(cfg, now)
        val pendingYesterday = ls.toLocalDate() != today && currentSlot(d, now) == cfg.slot &&
            d.dose(ls.toLocalDate(), cfg.slot) == null && !ls.isBefore(d.trackingSince)
        val date = if (pendingYesterday) ls.toLocalDate() else today
        val dose = d.dose(date, cfg.slot)
        val active = dose == null && !date.atTime(cfg.start).isAfter(now) && !date.atTime(cfg.start).isBefore(d.trackingSince)
        return MedStatus(date, dose?.takenAt, dose?.missed == true, active)
    }

    fun confirm(ctx: Context, slot: Slot, date: LocalDate) {
        Store.update { it.withDose(DoseLog(date, slot, takenAt = LocalDateTime.now())) }
        Notifier.cancelDose(ctx, slot)
        sync(ctx)
    }

    fun undo(ctx: Context, slot: Slot, date: LocalDate) {
        Store.update { d -> d.copy(doses = d.doses.filterNot { it.date == date && it.slot == slot }) }
        sync(ctx)
    }

    @Synchronized
    fun sync(ctx: Context) {
        val now = LocalDateTime.now()
        var d = Store.data

        // 1) 복약 알림 표시/정리
        val enabled = d.slots.filter { it.enabled }
        d.slots.filterNot { it.enabled }.forEach { Notifier.cancelDose(ctx, it.slot) }
        val current = currentSlot(d, now)
        for (cfg in enabled) {
            val start = lastStart(cfg, now)
            val date = start.toLocalDate()
            val tracked = !start.isBefore(d.trackingSince)
            val dose = d.dose(date, cfg.slot)
            if (cfg.slot == current) {
                if (dose == null && tracked) Notifier.postDose(ctx, cfg, date) else Notifier.cancelDose(ctx, cfg.slot)
            } else {
                Notifier.cancelDose(ctx, cfg.slot)
                if (dose == null && tracked) {
                    Store.update { it.withDose(DoseLog(date, cfg.slot, missed = true)) }
                    d = Store.data
                }
            }
        }

        // 2) 다음 복약 알람
        val next = enabled.mapNotNull { nextFire(it, d, now) }.minOrNull()
        setAlarm(ctx, REQ_TICK, next)

        // 3) 진료 일정
        for (a in d.appointments) {
            val remindAt = a.time.toLocalDate().minusDays(1).atTime(10, 0)
            val req = apptReq(a.id)
            when {
                a.id in d.notifiedAppts || !a.time.isAfter(now) -> setAlarm(ctx, req, null)
                remindAt.isAfter(now) -> setAlarm(ctx, req, remindAt)
                else -> {
                    Notifier.postAppointment(ctx, a)
                    Store.update { it.copy(notifiedAppts = it.notifiedAppts + a.id) }
                    setAlarm(ctx, req, null)
                }
            }
        }
        // 지난 일정의 알림 기록 정리
        val live = Store.data.appointments.map { it.id }.toSet()
        if (Store.data.notifiedAppts.any { it !in live }) Store.update { it.copy(notifiedAppts = it.notifiedAppts intersect live) }
    }

    /** 일정을 고치거나 지울 때: 알림 기록과 알람을 초기화 */
    fun resetAppointment(ctx: Context, id: String) {
        Store.update { it.copy(notifiedAppts = it.notifiedAppts - id) }
        setAlarm(ctx, apptReq(id), null)
        Notifier.cancelAppointment(ctx, id)
    }

    private fun nextFire(cfg: SlotConfig, d: AppData, now: LocalDateTime): LocalDateTime? {
        for (offset in 0L..2L) {
            val date = now.toLocalDate().plusDays(offset)
            if (d.dose(date, cfg.slot) != null) continue
            var t = date.atTime(cfg.start)
            val end = date.atTime(cfg.end)
            while (!t.isAfter(end)) {
                if (t.isAfter(now)) return t
                t = t.plusMinutes(cfg.intervalMin.toLong().coerceAtLeast(5))
            }
        }
        return null
    }

    private fun apptReq(id: String) = 5000 + (id.hashCode() and 0xFFFF)

    private fun setAlarm(ctx: Context, req: Int, at: LocalDateTime?) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, req, Intent(ctx, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (at == null) { am.cancel(pi); return }
        val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_TICK = "kr.songhy.kidney.TICK"
        const val ACTION_CONFIRM = "kr.songhy.kidney.CONFIRM"
        const val ACTION_DISMISSED = "kr.songhy.kidney.DISMISSED"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        when (intent.action) {
            ACTION_CONFIRM -> {
                val slot = intent.getStringExtra("slot")?.let { Slot.valueOf(it) } ?: return
                val date = intent.getStringExtra("date")?.let { LocalDate.parse(it) } ?: return
                Scheduler.confirm(ctx, slot, date)
            }
            // 알림을 밀어서 지워도 확인 전이면 다시 띄운다
            else -> Scheduler.sync(ctx)
        }
    }
}

class SystemReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        Scheduler.sync(ctx)
    }
}
