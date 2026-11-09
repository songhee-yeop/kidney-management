package kr.songhy.kidney.domain

/**
 * My 인하 "검사결과조회" 화면에서 읽은 글자 조각 → 앱 입력 형식("항목  값  단위  참고치").
 *
 * 화면 구조 (2026-11 확인):
 *   2026-03-12 (목)
 *   [소변검사]                ← 분류 머리글
 *   Protein(Random Urine)  검사정보
 *   결과  21.4 ▲  그래프
 *   참고치  0.0~15.0  (mg/dl)
 *   ...
 *   2025-12-04 (목)           ← 다음 날짜(접혀 있음)
 */
object MyInha {
    // "2026-03-12 (목)" 또는 접근성 설명 "검사일: 2026년03월12일(목)"
    private val DATE = Regex("""^\s*(?:검사일\s*:?\s*)?(20\d{2})\s*[-.년]\s*(\d{1,2})\s*[-.월]\s*(\d{1,2})""")
    private val NOISE_PARTS = listOf("검사정보", "그래프", "검사결과조회", "결과조회", "새창", "펼치기", "접기")
    // "화학검사" 또는 "검사명: 화학검사 내용 펼치기"
    private val SECTION = Regex("""^(검사명\s*:.*|[가-힣\s]{1,8}검사)$""")

    /** 기간 선택 버튼("…: 시작 날짜 선택")은 검사일이 아니다 */
    fun isDate(s: String) = DATE.containsMatchIn(s) && !s.contains("선택")
    fun isSection(s: String) = SECTION.matches(s.trim())
    /** null = 알 수 없음 */
    fun isCollapsed(s: String): Boolean? = when {
        s.contains("펼치기") -> true
        s.contains("접기") -> false
        else -> null
    }
    private fun isNoise(s: String) = NOISE_PARTS.any { s.contains(it) }

    /** 대상 날짜 머리글부터 다음 날짜 전까지. 대상이 없으면 "결과" 바로 앞 날짜를 쓴다. */
    fun section(tokens: List<String>, target: String? = null): List<String> {
        val start = target?.let { t -> tokens.indexOfFirst { it.trim() == t.trim() }.takeIf { it >= 0 } } ?: run {
            val firstResult = tokens.indexOf("결과")
            if (firstResult < 0) return tokens
            (firstResult downTo 0).firstOrNull { isDate(tokens[it]) } ?: 0
        }
        val next = (start + 1 until tokens.size).firstOrNull { isDate(tokens[it]) } ?: tokens.size
        return tokens.subList(start, next)
    }

    /** 대상 날짜 다음 날짜 머리글까지 읽었는지 */
    fun reachedEnd(tokens: List<String>, target: String? = null): Boolean {
        val start = target?.let { t -> tokens.indexOfFirst { it.trim() == t.trim() }.takeIf { it >= 0 } } ?: run {
            val firstResult = tokens.indexOf("결과")
            if (firstResult < 0) return false
            (firstResult downTo 0).firstOrNull { isDate(tokens[it]) } ?: return false
        }
        return (start + 1 until tokens.size).any { isDate(tokens[it]) }
    }

    /** 위로 스크롤을 멈춰도 되는지: 첫 "결과" 위에 날짜 머리글이 보인다 */
    fun atSectionTop(tokens: List<String>): Boolean {
        val firstResult = tokens.indexOf("결과")
        return firstResult > 0 && (0 until firstResult).any { isDate(tokens[it]) }
    }

    private val NUM_PREFIX = Regex("""^[<>]?\d+(?:\.\d+)?""")

    /** 줄바꿈된 항목명 잇기: "…/Creat(Rand" + "om) Ratio" → "…/Creat(Random) Ratio" */
    private fun joinName(parts: List<String>): String {
        if (parts.isEmpty()) return ""
        // 마지막 두 줄이 이어진 이름인지: 앞줄이 괄호를 닫지 않았으면 이어진 것
        if (parts.size == 2 && parts[0].count { it == '(' } > parts[0].count { it == ')' }) {
            val glue = if (parts[1].firstOrNull()?.isLowerCase() == true) "" else " "
            return parts[0] + glue + parts[1]
        }
        return parts.last()
    }

    /** OCR이 같은 항목을 화면마다 조금 다르게 읽은 것(I/l, 공백, 점)을 같은 항목으로 본다 */
    fun sameItem(a: String, b: String): Boolean {
        fun norm(x: String) = x.lowercase().replace('i', 'l').filter { it.isLetterOrDigit() }
        fun rest(x: String) = x.split("  ").drop(1).joinToString(" ").replace('~', '-').replace(" ", "")
        val na = norm(a.substringBefore("  ")); val nb = norm(b.substringBefore("  "))
        val va = a.split("  ").getOrNull(1); val vb = b.split("  ").getOrNull(1)
        if (va != vb) return false
        // 참고치가 둘 다 있으면 같아야 한다 (AST 5~40 / ALT 5~41 같은 경우 구분)
        val ra = rest(a); val rb = rest(b)
        if (ra.length > (va?.length ?: 0) && rb.length > (vb?.length ?: 0) && ra != rb) return false
        val limit = if (minOf(na.length, nb.length) < 8) 1 else 2
        return levenshtein(na, nb) <= limit
    }

    private fun levenshtein(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }

    private val HANGUL = Regex("[가-힣ㄱ-ㅎㅏ-ㅣ]")
    private val TEXT_VALUES = setOf("negative", "positive", "not found", "straw", "clear", "yellow", "turbid", "trace")

    /** My 인하 항목명은 영문: 한글이 섞였거나 소문자 한 글자면 글자 인식 잡음으로 본다 (P, K 같은 대문자 한 글자는 허용) */
    fun validName(n: String): Boolean {
        if (n.isBlank() || HANGUL.containsMatchIn(n)) return false
        val letters = n.count { it in 'A'..'Z' || it in 'a'..'z' }
        if (letters == 0) return false
        if (letters == 1 && n.trim().length == 1) return n.trim()[0].isUpperCase()
        return true
    }

    private fun looksLikeName(v: String) =
        v.isNotEmpty() && !v.first().isDigit() && validName(v) && v.lowercase() !in TEXT_VALUES && !v.startsWith("+")

    fun isoDate(s: String): String? = DATE.find(s)?.let { m ->
        "${m.groupValues[1]}-${m.groupValues[2].padStart(2, '0')}-${m.groupValues[3].padStart(2, '0')}"
    }

    /**
     * OCR 줄(위→아래) → 토큰. 한 줄에 붙어 나온 "결과 21.4 ▲ 그래프", "참고치 0.0~15.0 (mg/dl)",
     * "Protein(Random Urine) 검사정보"를 나눈다.
     */
    fun ocrTokens(lines: List<String>): List<String> {
        val out = mutableListOf<String>()
        val range = Regex("""\d[\d.]*\s*[~∼\-–]\s*\d[\d.]*""")
        val unit = Regex("""\([^)]*\)""")
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val compact = line.replace(" ", "")
            when {
                compact.startsWith("결과") && !compact.startsWith("결과조회") -> {
                    out += "결과"
                    val rest = line.substringAfter("과").replace("그래프", "").replace(Regex("[→⊕Θ©]"), "").trim()
                    if (rest.isNotEmpty()) out += rest
                }
                compact.startsWith("참고치") -> {
                    out += "참고치"
                    val rest = line.substringAfter("치")
                    range.find(rest)?.let { out += it.value.replace(" ", "") }
                    unit.find(rest)?.let { out += it.value.replace(" ", "") }
                }
                line.contains("검사정보") -> line.substringBefore("검사정보").trim().takeIf { it.isNotEmpty() }?.let { out += it }
                compact == "그래프" || compact.startsWith("그래프") -> {}
                else -> out += line
            }
        }
        return out
    }

    fun toLabText(tokens: List<String>, target: String? = null): String {
        val sec = section(tokens.map { it.trim() }.filter { it.isNotEmpty() }, target)
        val out = StringBuilder()
        sec.firstOrNull()?.let { DATE.find(it) }?.let { m ->
            out.append("검사일 ${m.groupValues[1]}-${m.groupValues[2].padStart(2, '0')}-${m.groupValues[3].padStart(2, '0')}\n")
        }
        val parts = mutableListOf<String>()
        var i = 0
        while (i < sec.size) {
            val t = sec[i]
            when {
                t == "결과" -> {
                    val raw = sec.getOrNull(i + 1)?.replace(Regex("[▲▼↑↓△▽]"), "")?.trim().orEmpty()
                    // OCR이 ▲를 글자로 읽는 경우가 있어 숫자로 시작하면 숫자 부분만
                    // "0-1", "11-15" 같은 범위 값은 그대로 두고, "21.4 A"처럼 뒤에 붙은 잡음만 뗀다
                    val value = when {
                        Regex("""^\d+\s*[-~]\s*\d+""").containsMatchIn(raw) -> raw
                        raw.firstOrNull()?.isDigit() == true -> NUM_PREFIX.find(raw)?.value ?: raw
                        else -> raw
                    }
                    var unit = ""
                    var ref = ""
                    var k = i + 2
                    while (k < sec.size) {
                        val s = sec[k]
                        when {
                            s.contains("그래프") -> k++
                            s == "참고치" -> { ref = sec.getOrNull(k + 1).orEmpty(); k += 2 }
                            s.startsWith("(") && s.endsWith(")") -> { unit = s.trim('(', ')', ' '); k++ }
                            else -> break
                        }
                    }
                    val name = joinName(parts)
                    parts.clear()
                    // 값을 못 읽어서 다음 항목 이름이 값 자리에 온 경우: 그 글자를 다음 항목 이름으로 돌려놓는다
                    if (sec.getOrNull(i + 2) == "결과" && looksLikeName(raw)) {
                        parts += raw
                        i += 2
                        continue
                    }
                    // 값 자리에 다른 칸의 글자(참고치/그래프 등)가 들어온 경우는 버린다
                    val bogus = value in setOf("참고치", "그래프", "결과", "검사정보") || (value.isNotEmpty() && value == sec.getOrNull(k)?.trim())
                    if (validName(name) && value.isNotEmpty() && !bogus) {
                        out.append(listOf(name, value, unit, ref).filter { it.isNotEmpty() }.joinToString("  ")).append('\n')
                    }
                    i = k
                    continue
                }
                isNoise(t) || t == "참고치" -> {}
                isDate(t) || SECTION.matches(t) -> parts.clear()
                else -> { parts += t; while (parts.size > 2) parts.removeAt(0) }
            }
            i++
        }
        return out.toString().trimEnd()
    }
}
