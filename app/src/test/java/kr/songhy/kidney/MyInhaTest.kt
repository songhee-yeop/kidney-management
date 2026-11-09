package kr.songhy.kidney

import kr.songhy.kidney.data.LabRecord
import kr.songhy.kidney.data.Profile
import kr.songhy.kidney.domain.Kfre
import kr.songhy.kidney.domain.LabParser
import kr.songhy.kidney.domain.MyInha
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** My 인하 화면에서 읽히는 글자 조각 → 입력 형식 변환. 모든 수치는 가짜 데이터. */
class MyInhaTest {
    /** 화면 순서대로 읽힌 글자 조각(위쪽에 접힌 최신 날짜, 아래쪽에 다음 날짜) */
    private val screen = listOf(
        "검사결과조회",
        "2026-06-18 (목)", "화학검사",
        "2026-03-12 (목)", "소변화학검사",
        "Protein(Random Urine)", "검사정보", "결과", "21.4", "그래프", "참고치", "0.0~15.0", "(mg/dl)",
        "Protein/Creatinine(Random Urine)", "검사정보", "결과", "0.22", "그래프",
        "Albumin(micro ,Random)/Creat(Random) Ratio", "검사정보", "결과", "142.6", "그래프", "참고치", "0.0~19.9", "(mg/g)",
        "소변검사",
        "Color", "검사정보", "결과", "Straw",
        "Protein", "검사정보", "결과", "+",
        "RBC", "검사정보", "결과", "0-2",
        "혈액검사",
        "WBC", "검사정보", "결과", "5.88", "그래프", "참고치", "4.00~10.00", "(천/μℓ )",
        "Hb", "검사정보", "결과", "15.1", "그래프", "참고치", "13.1~17.5", "(g/dL)",
        "화학검사",
        "TCO2", "검사정보", "결과", "25.4", "그래프", "참고치", "22.0~29.0", "(mEq/L)",
        "Creatinine", "검사정보", "결과", "1.38", "그래프", "참고치", "0.50~1.20", "(mg/dl)",
        "eGFR(IDMS-MDRD)", "검사정보", "결과", "58.10", "그래프",
        "eGFR(CKDEPI)", "검사정보", "결과", "63.20", "그래프",
        "Albumin", "검사정보", "결과", "4.4", "그래프", "참고치", "3.5~5.2", "(g/dL)",
        "Ca", "검사정보", "결과", "9.3", "그래프", "참고치", "8.6~10.7", "(mg/dl)",
        "P", "검사정보", "결과", "3.21", "그래프", "참고치", "2.5~4.5", "(mg/dL)",
        "2025-12-04 (목)", "화학검사",
        "Creatinine", "검사정보", "결과", "1.30",
    )

    @Test fun convertsScreenTextToLabText() {
        assertTrue(MyInha.atSectionTop(screen))
        assertTrue(MyInha.reachedEnd(screen))
        val text = MyInha.toLabText(screen)
        val b = LabParser.parseMany(text).single()
        assertEquals(LocalDate.of(2026, 3, 12), b.date)
        val v = b.values
        assertEquals(142.6, v["acr"]!!.value, 1e-9)
        assertEquals(19.9, v["acr"]!!.hi!!, 1e-9)
        assertEquals(63.20, v["egfr"]!!.value, 1e-9)
        assertEquals(1.38, v["cr"]!!.value, 1e-9)
        assertEquals(3.21, v["phos"]!!.value, 1e-9)
        assertEquals(9.3, v["ca"]!!.value, 1e-9)
        assertEquals(25.4, v["bic"]!!.value, 1e-9)
        assertEquals(15.1, v["hb"]!!.value, 1e-9)
        assertEquals("천/μℓ", v["x:wbc"]!!.unit)
        assertNull(v["x:color"])
        assertFalse(text.contains("1.30")) // 다음 날짜 내용은 제외
    }

    @Test fun usesExplicitTargetDate() {
        val t = MyInha.toLabText(screen, "2026-03-12 (목)")
        assertEquals(MyInha.toLabText(screen), t)
        assertTrue(MyInha.reachedEnd(screen, "2026-03-12 (목)"))
        assertEquals("검사일 2026-06-18", MyInha.toLabText(screen, "2026-06-18 (목)"))
        assertTrue(MyInha.isSection("화학검사"))
        assertFalse(MyInha.isSection("Creatinine"))
    }

    /** 실제 My 인하(WebView) 접근성 설명 형식 */
    @Test fun webViewFormat() {
        val tokens = listOf(
            "이전으로 돌아가기", "검사결과조회", "1개월", "직접설정", "기간 설정",
            "2026년03월01일(일): 시작 날짜 선택", "2026년03월31일(화): 종료 날짜 선택", "조회",
            "검사일: 2026년03월12일(목)",
            "검사명: 화학검사 내용 접기",
            "Na", "Na 검사정보 새창열기", "결과", "140.9", "Na 그래프 보기", "참고치", "133.0~145.0", "(mEq/L)",
            "eGFR(CKDEPI)", "검사정보", "결과", "63.20", "그래프",
            "Creatinine", "검사정보", "결과", "1.38", "그래프", "참고치", "0.50~1.20", "(mg/dl)",
            "검사명: 소변검사 내용 접기",
            "Albumin(micro ,Random)/Creat(Random) Ratio", "검사정보", "결과", "142.6", "참고치", "0.0~19.9", "(mg/g)",
            "검사일: 2025년12월04일(목)", "검사명: 화학검사 내용 펼치기",
        )
        val target = tokens.first { MyInha.isDate(it) }
        assertEquals("검사일: 2026년03월12일(목)", target)
        assertTrue(MyInha.isSection("검사명: 화학검사 내용 펼치기"))
        assertEquals(true, MyInha.isCollapsed("검사명: 화학검사 내용 펼치기"))
        assertEquals(false, MyInha.isCollapsed("검사명: 화학검사 내용 접기"))
        assertTrue(MyInha.reachedEnd(tokens, target))
        val b = LabParser.parseMany(MyInha.toLabText(tokens, target)).single()
        assertEquals(LocalDate.of(2026, 3, 12), b.date)
        assertEquals(140.9, b.values["na"]!!.value, 1e-9)
        assertEquals(63.20, b.values["egfr"]!!.value, 1e-9)
        assertEquals(1.38, b.values["cr"]!!.value, 1e-9)
        assertEquals(142.6, b.values["acr"]!!.value, 1e-9)
        assertEquals(4, b.values.size)
    }

    /** 글자 인식(OCR) 줄 단위 결과: 한 줄에 붙은 칸, 두 줄로 나뉜 항목명 */
    @Test fun ocrLines() {
        val lines = listOf(
            "Protein(Random Urine) 검사정보",
            "결과 21.4 A 그래프",
            "참고치 0.0~15.0 (mg/dl)",
            "Protein/Creatinine(Random Urine)", "검사정보",
            "결과", "0.22", "그래프",
            "Albumin(micro ,Random)/Creat(Rand", "검사정보",
            "om) Ratio",
            "결과", "142.6 ▲", "그래프",
            "참고치", "0.0~19.9", "(mg/g)",
            "화학검사",
            "P 검사정보",
            "결과 3.21 그래프",
            "참고치 2.5~4.5 (mg/dL)",
        )
        val target = "검사일: 2026년03월12일(목)"
        val b = LabParser.parseMany(MyInha.toLabText(listOf(target) + MyInha.ocrTokens(lines), target)).single()
        assertEquals(LocalDate.of(2026, 3, 12), b.date)
        assertEquals(21.4, b.values["x:protein(randomurine)"]!!.value, 1e-9)
        assertEquals(15.0, b.values["x:protein(randomurine)"]!!.hi!!, 1e-9)
        assertEquals(142.6, b.values["acr"]!!.value, 1e-9)
        assertEquals(19.9, b.values["acr"]!!.hi!!, 1e-9)
        assertEquals(3.21, b.values["phos"]!!.value, 1e-9)
        assertEquals(0.22, b.values["x:protein/creatinine(randomurine)"]!!.value, 1e-9)
    }

    @Test fun ocrCleanups() {
        val t = "검사일: 2026년03월12일(목)"
        val text = MyInha.toLabText(listOf(t) + MyInha.ocrTokens(listOf(
            "Squamous cell 검사정보", "결과 0-1",
            "ALT(GPT) 검사정보", "결과", "참고치", "5~41", "(U/L)",
            "Na 검사정보", "결과 140.9 그래프",
        )), t)
        assertTrue(text.contains("Squamous cell  0-1"))
        assertFalse(text.contains("ALT(GPT)"))
        assertTrue(text.contains("Na  140.9"))
        assertTrue(MyInha.sameItem("eGFR(IDMS-MDRD)  58.10", "eGFR(DMS-MDRD)  58.10"))
        assertTrue(MyInha.sameItem("Cl  104.0", "CI  104.0"))
        assertFalse(MyInha.sameItem("Albumin(micro, Random urine)  14100", "Albumin(micro,Random urine)  141.0"))
        assertFalse(MyInha.sameItem("Na  140.9", "K  4.62"))
    }

    /** 실기기에서 실제로 틀렸던 경우들 */
    @Test fun realRunFixes() {
        val t = "검사일: 2026년03월12일(목)"
        val text = MyInha.toLabText(listOf(t) + MyInha.ocrTokens(listOf(
            "Nucleated RBC 검사정보", "결과", "Neutrophil 검사정보", "결과 55.3 그래프", "참고치 40.0~75.0 (%)",
            "검사성보", "결과 104.0",
            "사징모나 검사정보", "결과 Negative",
            "r", "결과 142.6",
            "P 검사정보", "결과 3.21",
            "Urobilinogen 검사정보", "결과 Negative",
        )), t)
        assertTrue(text, text.contains("Neutrophil  55.3  %  40.0~75.0"))
        assertFalse(text.contains("Nucleated RBC  Neutrophil"))
        assertFalse(text.contains("검사성보"))
        assertFalse(text.contains("사징모나"))
        assertFalse(text.contains("\nr  "))
        assertTrue(text.contains("P  3.21"))
        assertTrue(text.contains("Urobilinogen  Negative"))
        assertFalse(MyInha.sameItem("AST(GOT)  21  U/L  5~40", "ALT(GPT)  21  U/L  5~41"))
        assertFalse(MyInha.sameItem("AST(GOT)  9", "ALT(GPT)  9"))
        assertTrue(MyInha.sameItem("T.Protein  7.2  g/dL  6.4-8.3", "TProtein  7.2  g/dL  6.4~8.3"))
    }

    @Test fun fixesMissingDecimalPoint() {
        assertEquals("47.1", MyInha.fixDecimal("Neutrophil", "471", "", ""))          // 백분율 항목 100 초과
        assertEquals("47.1", MyInha.fixDecimal("Neutrophil", "471", "%", "40.0~75.0"))
        assertEquals("17.7", MyInha.fixDecimal("BUN", "177", "mg/dL", "6.0~20.0"))
        assertEquals("4.48", MyInha.fixDecimal("K", "448", "mEq/L", "3.50~5.50"))
        assertEquals("301.1", MyInha.fixDecimal("ACR", "301.1", "mg/g", "0.0~19.9"))  // 소수점 있으면 그대로
        assertEquals("301", MyInha.fixDecimal("ACR", "301", "mg/g", "0.0~19.9"))      // 참고치 0부터: 보정 안 함
        assertEquals("150", MyInha.fixDecimal("ACR", "150", "mg/g", "0.0~19.9"))
        assertEquals("69", MyInha.fixDecimal("Glucose", "69", "mg/dL", "70~100"))      // 참고치가 정수면 건드리지 않음
        assertEquals("211", MyInha.fixDecimal("Cholesterol", "211", "mg/dL", "123~200"))
        val t = "검사일: 2026년03월12일(목)"
        val text = MyInha.toLabText(listOf(t) + MyInha.ocrTokens(listOf(
            "Neutrophil 검사정보", "결과 553 그래프", "참고치 40.0~75.0 (%)",
        )), t)
        assertTrue(text, text.contains("Neutrophil  55.3  %  40.0~75.0"))
        assertEquals(Triple("Neutrophil", "553", "55.3"), MyInha.corrections.single())
        // 메모 줄(#)은 파서가 무시
        val b = LabParser.parseMany("검사일 2026-03-12\n# 자동 보정: Neutrophil 553 → 55.3\nNeutrophil  55.3  %  40.0~75.0").single()
        assertEquals(1, b.values.size)
        assertEquals(55.3, b.values["x:neutrophil"]!!.value, 1e-9)
    }

    @Test fun midSectionIsNotTop() {
        assertFalse(MyInha.atSectionTop(screen.drop(5)))
    }

    @Test fun parsesScreenTranscription() {
        val b = LabParser.parseMany(javaClass.getResource("/sample_screen.txt")!!.readText()).single()
        val v = b.values
        assertEquals(LocalDate.of(2026, 3, 12), b.date)
        assertEquals(142.6, v["acr"]!!.value, 1e-9)
        assertEquals(63.20, v["egfr"]!!.value, 1e-9)
        assertEquals(58.10, v["x:egfr(idms-mdrd)"]!!.value, 1e-9)
        assertEquals(3.21, v["phos"]!!.value, 1e-9)
        assertEquals(4.62, v["k"]!!.value, 1e-9)
        val e = Kfre.evaluate(LabRecord(b.date!!, v), Profile(LocalDate.of(1985, 5, 20), true))
        assertEquals(8, e.risk!!.model)
        assertEquals(0.18, e.risk!!.r2, 0.01)
        assertEquals(0.80, e.risk!!.r5, 0.01)
    }
}
