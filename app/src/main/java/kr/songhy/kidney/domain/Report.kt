package kr.songhy.kidney.domain

import kr.songhy.kidney.data.LabRecord
import kr.songhy.kidney.data.LabValue
import kr.songhy.kidney.data.Profile
import java.util.Locale
import kotlin.math.abs

/** ChatGPT 등에 붙여넣어 물어보기 좋은 텍스트 */
object Report {
    private fun n(v: Double, dec: Int = 3): String {
        val s = String.format(Locale.US, "%.${dec}f", v)
        return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
    }

    private fun ref(v: LabValue): String? = when {
        v.lo != null && v.hi != null -> "${n(v.lo)}~${n(v.hi)}"
        v.hi != null -> "<${n(v.hi)}"
        v.lo != null -> ">${n(v.lo)}"
        else -> null
    }

    fun record(r: LabRecord, p: Profile?, prev: LabRecord?): String {
        val sb = StringBuilder()
        sb.append("[검사일 ${r.date}]")
        if (p != null) sb.append(" ${Kfre.ageAt(p.birth, r.date)}세 ${if (p.male) "남성" else "여성"}")
        sb.append('\n')
        if (p != null) {
            val e = Kfre.evaluate(r, p)
            e.risk?.let {
                sb.append("신부전 위험(KFRE ${it.model}변수, 비북미 보정): 2년 ${n(it.r2, 2)}%, 5년 ${n(it.r5, 2)}%")
                val acr = r.values["acr"]?.value
                if (e.egfr != null && acr != null) sb.append(" · CKD ${Kfre.gStage(e.egfr)} ${Kfre.aStage(acr)}")
                sb.append('\n')
            }
            if (e.egfrCalc && e.egfr != null) sb.append("eGFR(CKD-EPI 2021, 크레아티닌으로 계산): ${n(e.egfr, 1)}\n")
        }
        r.values.entries.sortedWith(compareBy({ Catalog.order(it.key) }, { it.key })).forEach { (k, v) ->
            sb.append(Catalog.name(k, v)).append(": ").append(n(v.value))
            Catalog.unit(k, v).takeIf { it.isNotEmpty() }?.let { sb.append(' ').append(it) }
            ref(v)?.let { sb.append(" (참고치 ").append(it).append(')') }
            Catalog.flag(k, v)?.let { sb.append(if (it == "H") " ▲높음" else " ▼낮음") }
            prev?.values?.get(k)?.let { pv ->
                val d = v.value - pv.value
                if (abs(d) > 1e-9) sb.append(" [직전 ${prev.date} ${n(pv.value)} → ${if (d > 0) "+" else ""}${n(d, 2)}]")
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    fun all(records: List<LabRecord>, p: Profile?): String {
        val sorted = records.sortedBy { it.date }
        return sorted.mapIndexed { i, r -> record(r, p, sorted.getOrNull(i - 1)) }.joinToString("\n\n")
    }
}
