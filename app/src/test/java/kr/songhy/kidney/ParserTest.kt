package kr.songhy.kidney

import kr.songhy.kidney.data.LabRecord
import kr.songhy.kidney.data.Profile
import kr.songhy.kidney.domain.Kfre
import kr.songhy.kidney.domain.LabParser
import kr.songhy.kidney.domain.Report
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 모든 수치는 테스트용 가짜 데이터 */
class ParserTest {
    private val profile = Profile(LocalDate.of(1985, 5, 20), true)
    private fun res(name: String) = javaClass.getResource("/$name")!!.readText()

    @Test fun parsesChatStyleTextWithEscapes() {
        val b = LabParser.parseMany(res("sample_gpt.txt")).single()
        val v = b.values
        assertEquals(LocalDate.of(2026, 6, 18), b.date)
        assertEquals(1.47, v["cr"]!!.value, 1e-9)
        assertEquals(1.20, v["cr"]!!.hi!!, 1e-9)
        assertEquals(58.90, v["egfr"]!!.value, 1e-9)                 // CKD-EPI 우선
        assertEquals(54.30, v["x:egfr(idms-mdrd)"]!!.value, 1e-9)    // MDRD는 별도 항목
        assertEquals(188.3, v["acr"]!!.value, 1e-9)
        assertEquals(19.9, v["acr"]!!.hi!!, 1e-9)
        assertEquals(4.3, v["alb"]!!.value, 1e-9)
        assertEquals(3.35, v["phos"]!!.value, 1e-9)
        assertEquals(9.4, v["ca"]!!.value, 1e-9)
        assertEquals(24.8, v["bic"]!!.value, 1e-9)
        assertEquals(4.71, v["k"]!!.value, 1e-9)
        assertEquals(140.2, v["na"]!!.value, 1e-9)
        assertEquals(19.6, v["bun"]!!.value, 1e-9)
        assertEquals(6.4, v["uric"]!!.value, 1e-9)
        assertEquals(15.0, v["hb"]!!.value, 1e-9)
        assertEquals(1.005, v["x:s.g"]!!.lo!!, 1e-9)                 // \~ 이스케이프 처리
        assertEquals(0.31, v["x:urineprotein/creatinineratio"]!!.value, 1e-9)
        assertEquals(92.0, v["x:glucose"]!!.value, 1e-9)              // 소변 Glucose(Negative)는 숫자가 아니라 건너뜀
        assertEquals(6.02, v["x:wbc"]!!.value, 1e-9)                  // 소변 WBC(0-2)는 건너뜀
    }

    @Test fun parsesMultipleDatesAndValueOnNextLine() {
        val text = res("sample_gpt.txt") + "\n\n검사일 2026-03-12\n크레아티닌\n1.38\nmg/dL\n요알부민/크레아티닌비\n142.6\nmg/g"
        val bs = LabParser.parseMany(text)
        assertEquals(2, bs.size)
        assertEquals(LocalDate.of(2026, 3, 12), bs[1].date)
        assertEquals(1.38, bs[1].values["cr"]!!.value, 1e-9)
        assertEquals(142.6, bs[1].values["acr"]!!.value, 1e-9)
        assertNull(bs[1].values["egfr"])
    }

    @Test fun prefersCkdEpiOverMdrd() {
        val v = LabParser.parseBlock("eGFR(IDMS-MDRD)  54.30\n\neGFR(CKD-EPI)  58.90  mL/min/1.73m²").values
        assertEquals(58.90, v["egfr"]!!.value, 1e-9)
        assertNull(LabParser.parseBlock("eGFR(IDMS-MDRD)  54.30").values["egfr"])
    }

    /**
     * 8변수 KFRE(비북미 보정) 공식 확인. 기대값은 Tangri 계수로 따로 계산한 값.
     * (개발 중 같은 구현을 kidneyfailurerisk.com 계산 결과와 대조해 일치를 확인함)
     */
    @Test fun kfreEightVariable() {
        val r = Kfre.kfre(55, true, 40.0, 500.0, 3.9, 4.1, 22.0, 9.2)
        assertEquals(8, r.model)
        assertEquals(3.57, r.r2, 0.01)
        assertEquals(15.08, r.r5, 0.01)
    }

    @Test fun evaluateWithCorrectedCalcium() {
        val b = LabParser.parseMany(res("sample_gpt.txt")).single()
        val e = Kfre.evaluate(LabRecord(b.date!!, b.values), profile)
        assertEquals(41, e.age)
        assertEquals(9.16, e.cacorr!!, 1e-6)          // 9.4 + 0.8 × (4.0 − 4.3)
        assertEquals(8, e.risk!!.model)
        assertEquals(0.32, e.risk!!.r2, 0.01)
        assertEquals(1.42, e.risk!!.r5, 0.01)
    }

    @Test fun evaluateFallsBackToCreatinine() {
        val rec = LabRecord(LocalDate.of(2026, 6, 18), LabParser.parseBlock("Creatinine 1.47\nUrine Albumin/Creatinine ratio 188.3 mg/g").values)
        val e = Kfre.evaluate(rec, profile)
        assertTrue(e.egfrCalc)
        assertEquals(61.07, e.egfr!!, 0.05)            // CKD-EPI 2021
        assertEquals(4, e.risk!!.model)
    }

    @Test fun reportTextForChat() {
        val a = LabParser.parseMany(res("sample_screen.txt")).single()
        val b = LabParser.parseMany(res("sample_gpt.txt")).single()
        val recs = listOf(LabRecord(a.date!!, a.values), LabRecord(b.date!!, b.values))
        val out = Report.record(recs[1], profile, recs[0])
        assertTrue(out, out.startsWith("[검사일 2026-06-18] 41세 남성"))
        assertTrue(out, out.contains("2년 0.32%, 5년 1.42% · CKD G3a A2"))
        assertTrue(out, out.contains("eGFR: 58.9 mL/min/1.73m² ▼낮음 [직전 2026-03-12 63.2 → -4.3]"))
        assertTrue(out, out.contains("Creatinine: 1.47 mg/dl (참고치 0.5~1.2) ▲높음 [직전 2026-03-12 1.38 → +0.09]"))
        assertTrue(Report.all(recs, profile).contains("[검사일 2026-03-12]"))
    }
}
