package kr.songhy.kidney.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 모든 데이터는 앱 내부 저장소의 JSON 파일 하나에 저장한다. 서버 전송 없음. */
object Store {
    private var file: File? = null
    private val state = MutableStateFlow(AppData())
    val flow: StateFlow<AppData> = state
    val data: AppData get() = state.value

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "data.json")
        file = f
        if (f.exists()) {
            runCatching { state.value = Json.decode(f.readText()) }
                .onFailure { f.copyTo(File(ctx.filesDir, "data.corrupt.json"), overwrite = true) }
        } else {
            save()
        }
    }

    @Synchronized
    fun update(fn: (AppData) -> AppData) {
        state.value = fn(state.value)
        save()
    }

    private fun save() {
        val f = file ?: return
        val tmp = File(f.path + ".tmp")
        tmp.writeText(Json.encode(state.value))
        if (!tmp.renameTo(f)) {
            f.writeText(tmp.readText()); tmp.delete()
        }
    }

    fun exportText(): String = Json.encode(data)

    /** 실패하면 예외를 던진다. 기존 데이터는 건드리지 않는다. */
    fun importText(text: String) {
        val d = Json.decode(text)
        update { d }
    }
}

object Json {
    fun encode(d: AppData): String = JSONObject().apply {
        put("version", 1)
        d.profile?.let { put("profile", JSONObject().put("birth", it.birth.toString()).put("male", it.male)) }
        put("labs", JSONArray(d.labs.map { r ->
            val vo = JSONObject()
            r.values.forEach { (k, v) ->
                vo.put(k, JSONObject().apply {
                    put("v", v.value)
                    v.unit?.let { put("u", it) }
                    v.lo?.let { put("lo", it) }
                    v.hi?.let { put("hi", it) }
                    v.name?.let { put("n", it) }
                })
            }
            JSONObject().put("date", r.date.toString()).put("values", vo)
        }))
        put("vitals", JSONArray(d.vitals.map { v ->
            JSONObject().apply {
                put("id", v.id); put("t", v.time.toString())
                v.sys?.let { put("sys", it) }
                v.dia?.let { put("dia", it) }
                v.pulse?.let { put("pulse", it) }
                v.weight?.let { put("w", it) }
            }
        }))
        put("appts", JSONArray(d.appointments.map {
            JSONObject().put("id", it.id).put("t", it.time.toString()).put("title", it.title).put("note", it.note)
        }))
        put("slots", JSONArray(d.slots.map {
            JSONObject().put("slot", it.slot.name).put("msg", it.message).put("start", it.start.toString())
                .put("end", it.end.toString()).put("iv", it.intervalMin).put("on", it.enabled)
        }))
        put("doses", JSONArray(d.doses.map { l ->
            JSONObject().apply {
                put("d", l.date.toString()); put("s", l.slot.name); put("missed", l.missed)
                l.takenAt?.let { put("at", it.toString()) }
            }
        }))
        put("since", d.trackingSince.toString())
        put("notified", JSONArray(d.notifiedAppts.toList()))
    }.toString(1)

    fun decode(s: String): AppData {
        val o = JSONObject(s)
        val profile = o.optJSONObject("profile")?.let { Profile(LocalDate.parse(it.getString("birth")), it.getBoolean("male")) }
        val labs = o.optJSONArray("labs").objs().map { r ->
            val vo = r.getJSONObject("values")
            val values = LinkedHashMap<String, LabValue>()
            vo.keys().forEach { k ->
                val x = vo.getJSONObject(k)
                values[k] = LabValue(x.getDouble("v"), x.str("u"), x.dbl("lo"), x.dbl("hi"), x.str("n"))
            }
            LabRecord(LocalDate.parse(r.getString("date")), values)
        }
        val vitals = o.optJSONArray("vitals").objs().map {
            Vital(it.getString("id"), LocalDateTime.parse(it.getString("t")), it.int("sys"), it.int("dia"), it.int("pulse"), it.dbl("w"))
        }
        val appts = o.optJSONArray("appts").objs().map {
            Appointment(it.getString("id"), LocalDateTime.parse(it.getString("t")), it.optString("title"), it.optString("note"))
        }
        val savedSlots = o.optJSONArray("slots").objs().map {
            SlotConfig(
                Slot.valueOf(it.getString("slot")), it.getString("msg"), LocalTime.parse(it.getString("start")),
                LocalTime.parse(it.getString("end")), it.optInt("iv", 30), it.optBoolean("on", true),
            )
        }
        val slots = defaultSlots().map { def -> savedSlots.find { it.slot == def.slot } ?: def }
        val doses = o.optJSONArray("doses").objs().map {
            DoseLog(LocalDate.parse(it.getString("d")), Slot.valueOf(it.getString("s")), it.str("at")?.let(LocalDateTime::parse), it.optBoolean("missed"))
        }
        val since = o.str("since")?.let(LocalDateTime::parse) ?: LocalDateTime.now()
        val notified = o.optJSONArray("notified")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
        return AppData(profile, labs, vitals, appts, slots, doses, since, notified)
    }

    private fun JSONArray?.objs(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    private fun JSONObject.str(k: String) = if (has(k) && !isNull(k)) getString(k) else null
    private fun JSONObject.dbl(k: String) = if (has(k) && !isNull(k)) getDouble(k) else null
    private fun JSONObject.int(k: String) = if (has(k) && !isNull(k)) getInt(k) else null
}
