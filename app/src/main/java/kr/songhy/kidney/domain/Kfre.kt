package kr.songhy.kidney.domain

import kr.songhy.kidney.data.LabRecord
import kr.songhy.kidney.data.LabValue
import kr.songhy.kidney.data.Profile
import java.time.LocalDate
import java.time.Period
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Tangri KFRE (비북미 보정) + CKD-EPI 2021. */
object Kfre {
    data class Risk(val model: Int, val r2: Double, val r5: Double)
    data class Eval(val age: Int, val egfr: Double?, val egfrCalc: Boolean, val cacorr: Double?, val risk: Risk?)

    fun ageAt(birth: LocalDate, date: LocalDate) = Period.between(birth, date).years

    fun ckdEpi2021(scr: Double, age: Int, male: Boolean): Double {
        val k = if (male) 0.9 else 0.7
        val a = if (male) -0.302 else -0.241
        return 142 * min(scr / k, 1.0).pow(a) * max(scr / k, 1.0).pow(-1.2) * 0.9938.pow(age) * (if (male) 1.0 else 1.012)
    }

    fun kfre(age: Int, male: Boolean, egfr: Double, acr: Double, alb: Double?, phos: Double?, bic: Double?, cacorr: Double?): Risk {
        val m = if (male) 1.0 else 0.0
        return if (alb != null && phos != null && bic != null && cacorr != null) {
            val b = doubleArrayOf(-0.1992, 0.1602, -0.4919, 0.3364, -0.2201, 0.2604, -0.3441, -0.07354)
            val mu = doubleArrayOf(7.036, 0.5642, 7.222, 5.137, 9.355, 3.916, 3.997, 25.57)
            val x = doubleArrayOf(age / 10.0, m, egfr / 5, ln(acr), cacorr, phos, alb, bic)
            val lp = x.indices.sumOf { b[it] * (x[it] - mu[it]) }
            Risk(8, (1 - 0.9827.pow(exp(lp))) * 100, (1 - 0.9245.pow(exp(lp))) * 100)
        } else {
            val b = doubleArrayOf(-0.2201, 0.2467, -0.5567, 0.4510)
            val mu = doubleArrayOf(7.036, 0.5642, 7.222, 5.137)
            val x = doubleArrayOf(age / 10.0, m, egfr / 5, ln(acr))
            val lp = x.indices.sumOf { b[it] * (x[it] - mu[it]) }
            Risk(4, (1 - 0.9832.pow(exp(lp))) * 100, (1 - 0.9365.pow(exp(lp))) * 100)
        }
    }

    fun evaluate(rec: LabRecord, p: Profile): Eval {
        val age = ageAt(p.birth, rec.date)
        val v = rec.values
        var egfr = v["egfr"]?.value
        var calc = false
        val cr = v["cr"]?.value
        if (egfr == null && cr != null && age > 0) { egfr = ckdEpi2021(cr, age, p.male); calc = true }
        val alb = v["alb"]?.value
        val ca = v["ca"]?.value
        val cacorr = v["cacorr"]?.value ?: if (ca != null && alb != null) ca + 0.8 * (4.0 - alb) else null
        val acr = v["acr"]?.value
        val risk = if (egfr != null && acr != null && acr > 0 && age > 0)
            kfre(age, p.male, egfr, acr, alb, v["phos"]?.value, v["bic"]?.value, cacorr) else null
        return Eval(age, egfr, calc, cacorr, risk)
    }

    fun gStage(e: Double) = when {
        e >= 90 -> "G1"; e >= 60 -> "G2"; e >= 45 -> "G3a"; e >= 30 -> "G3b"; e >= 15 -> "G4"; else -> "G5"
    }
    fun gLabel(e: Double) = when {
        e >= 90 -> "정상 또는 높음"; e >= 60 -> "경도 감소"; e >= 45 -> "경도~중등도 감소"
        e >= 30 -> "중등도~고도 감소"; e >= 15 -> "고도 감소"; else -> "신부전"
    }
    fun aStage(a: Double) = if (a < 30) "A1" else if (a <= 300) "A2" else "A3"

    enum class Level { OK, WARN, BAD }
    fun verdict(r: Risk): Pair<Level, String> = when {
        r.r2 >= 20 -> Level.BAD to "2년 위험 20% 이상 · 투석·이식 준비를 논의하는 구간"
        r.r2 >= 10 -> Level.BAD to "2년 위험 10% 이상 · 신장내과 팀 진료 대상 구간"
        r.r5 >= 3 -> Level.WARN to "5년 위험 3% 이상 · 신장내과 진료가 권고되는 구간"
        else -> Level.OK to "5년 위험 3% 미만 · 일반적인 의뢰 기준보다 낮음"
    }
}

/** 알려진 항목의 표시 이름·단위·악화 방향. 그 밖의 항목은 결과지 이름을 그대로 쓴다. */
object Catalog {
    /** worse: +1 높을수록 나쁨, -1 낮을수록 나쁨, 0 범위 밖이면 나쁨 */
    data class Item(val key: String, val name: String, val unit: String, val worse: Int, val lo: Double? = null, val hi: Double? = null)

    val items = listOf(
        Item("egfr", "eGFR", "mL/min/1.73㎡", -1, lo = 60.0),
        Item("cr", "Creatinine", "mg/dL", 1),
        Item("acr", "ACR (소변 알부민/Cr)", "mg/g", 1, hi = 30.0),
        Item("bun", "BUN", "mg/dL", 1),
        Item("k", "칼륨 (K)", "mmol/L", 0),
        Item("na", "나트륨 (Na)", "mmol/L", 0),
        Item("alb", "알부민", "g/dL", -1),
        Item("phos", "인 (P)", "mg/dL", 0),
        Item("ca", "칼슘 (Ca)", "mg/dL", 0),
        Item("cacorr", "보정 칼슘", "mg/dL", 0),
        Item("bic", "중탄산 (Total CO2)", "mEq/L", -1),
        Item("uric", "요산", "mg/dL", 1),
        Item("hb", "헤모글로빈", "g/dL", -1),
    )

    fun item(key: String) = items.find { it.key == key }
    fun name(key: String, v: LabValue?) = item(key)?.name ?: v?.name ?: key.removePrefix("x:")
    fun unit(key: String, v: LabValue?) = v?.unit ?: item(key)?.unit ?: ""
    fun order(key: String) = items.indexOfFirst { it.key == key }.let { if (it < 0) 999 else it }

    /** "H" / "L" / null */
    fun flag(key: String, v: LabValue): String? {
        val lo = v.lo ?: item(key)?.lo
        val hi = v.hi ?: item(key)?.hi
        return when {
            hi != null && v.value > hi -> "H"
            lo != null && v.value < lo -> "L"
            else -> null
        }
    }

    /** 직전 값 대비 나빠졌는지 (변화가 3% 미만이면 무시) */
    fun worsened(key: String, prev: Double, cur: Double): Boolean {
        val w = item(key)?.worse ?: return false
        if (prev == 0.0 || kotlin.math.abs(cur - prev) / kotlin.math.abs(prev) < 0.03) return false
        return (w > 0 && cur > prev) || (w < 0 && cur < prev)
    }
}
