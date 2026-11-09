package kr.songhy.kidney.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

enum class Slot(val label: String) { MORNING("아침"), EVENING("저녁") }

data class Profile(val birth: LocalDate, val male: Boolean)

/** 검사값 하나. unit/lo/hi는 결과지에 있을 때만 채워진다. */
data class LabValue(
    val value: Double,
    val unit: String? = null,
    val lo: Double? = null,
    val hi: Double? = null,
    val name: String? = null,
)

/** 한 날짜의 검사결과. 날짜당 하나로 합쳐 저장한다. */
data class LabRecord(val date: LocalDate, val values: Map<String, LabValue>)

data class Vital(
    val id: String,
    val time: LocalDateTime,
    val sys: Int? = null,
    val dia: Int? = null,
    val pulse: Int? = null,
    val weight: Double? = null,
)

data class Appointment(
    val id: String,
    val time: LocalDateTime,
    val title: String,
    val note: String,
)

data class SlotConfig(
    val slot: Slot,
    val message: String,
    val start: LocalTime,
    val end: LocalTime,
    val intervalMin: Int = 30,
    val enabled: Boolean = true,
)

/** takenAt != null 이면 복용, missed 이면 다음 회차가 시작될 때까지 확인하지 않음. */
data class DoseLog(
    val date: LocalDate,
    val slot: Slot,
    val takenAt: LocalDateTime? = null,
    val missed: Boolean = false,
)

fun defaultSlots() = listOf(
    SlotConfig(Slot.MORNING, "아침약 복용 시간입니다 (텔미사르탄, 처방전약)", LocalTime.of(7, 0), LocalTime.of(10, 0)),
    SlotConfig(Slot.EVENING, "처방전약 복용 시간입니다", LocalTime.of(19, 0), LocalTime.of(22, 0)),
)

data class AppData(
    val profile: Profile? = null,
    val labs: List<LabRecord> = emptyList(),
    val vitals: List<Vital> = emptyList(),
    val appointments: List<Appointment> = emptyList(),
    val slots: List<SlotConfig> = defaultSlots(),
    val doses: List<DoseLog> = emptyList(),
    /** 이 시각 이전에 시작한 복약 회차는 알림·미복용 처리 대상이 아니다 (설치 직후 오탐 방지). */
    val trackingSince: LocalDateTime = LocalDateTime.now(),
    val notifiedAppts: Set<String> = emptySet(),
) {
    fun slot(s: Slot) = slots.first { it.slot == s }
    fun dose(date: LocalDate, s: Slot) = doses.find { it.date == date && it.slot == s }

    fun withDose(log: DoseLog) = copy(
        doses = (doses.filterNot { it.date == log.date && it.slot == log.slot } + log)
            .filter { it.date.isAfter(LocalDate.now().minusYears(3)) },
    )

    fun withSlot(cfg: SlotConfig) = copy(slots = slots.map { if (it.slot == cfg.slot) cfg else it })

    /** 같은 날짜가 있으면 값을 합친다(새 값 우선). */
    fun withLabs(records: List<LabRecord>): AppData {
        val map = labs.associateBy { it.date }.toMutableMap()
        for (r in records) {
            val old = map[r.date]
            map[r.date] = if (old == null) r else LabRecord(r.date, old.values + r.values)
        }
        return copy(labs = map.values.sortedBy { it.date })
    }

    val labsSorted get() = labs.sortedBy { it.date }
}
