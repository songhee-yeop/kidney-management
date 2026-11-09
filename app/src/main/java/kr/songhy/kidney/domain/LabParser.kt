package kr.songhy.kidney.domain

import kr.songhy.kidney.data.LabValue
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.round

/**
 * 검사결과 텍스트 → 날짜별 수치.
 * 1) 한 줄 "항목명 값 단위 참고치" 형식(ChatGPT 변환 결과)을 우선 읽고, 모르는 항목도 그대로 저장한다.
 * 2) 값이 다음 줄에 있는 형식(앱 화면 복사)은 주요 항목만 따로 찾아 읽는다.
 */
object LabParser {
    class Field(val key: String, pattern: String, val min: Double, val max: Double, skip: String? = null) {
        val re = Regex(pattern, RegexOption.IGNORE_CASE)
        val skip = skip?.let { Regex(it, RegexOption.IGNORE_CASE) }
    }

    // 한 줄이 여러 이름에 걸리면 위에 있는 항목이 우선
    val FIELDS = listOf(
        Field("acr", """(u(?:rine)?[\s.\-]*(?:micro)?alb(?:umin)?\s*/\s*cr(?:eatinine)?(?:\s*ratio)?|micro\s*alb(?:umin)?\s*/\s*cr\w*|alb(?:umin)?\s*/\s*cr(?:eatinine)?\s*ratio|alb\w*\s*\([^)]*\)\s*/\s*creat|\bu?acr\b|(?:요|소변)?\s*(?:미세)?알부민\s*/\s*크레아티닌\s*비?)""", 0.1, 20000.0),
        // eGFR은 CKD-EPI만 사용. MDRD 등 다른 식은 별도 항목으로 저장된다.
        Field("egfr", """(e[\s\-]*gfr|사구체\s*여과율)""", 1.0, 200.0, """mdrd|cockcroft|schwartz|cystatin|시스타틴"""),
        Field("cacorr", """(corrected\s*ca(?:lcium)?|ca(?:lcium)?\s*\(?\s*corrected|보정\s*칼슘)""", 4.0, 16.0),
        Field("bic", """(total\s*co2|t\.?\s*co2|hco3-?|bicarbonate|중탄산염?|총\s*이산화탄소|\bco2\b)""", 5.0, 50.0),
        Field("uric", """(uric\s*acid|요산)""", 0.5, 20.0, """urine|소변"""),
        Field("bun", """(\bbun\b|urea\s*nitrogen|요소\s*질소)""", 1.0, 200.0, """ratio|/\s*cr"""),
        Field("phos", """(^\s*p(?=\s|$)|phosph\w*|inorganic\s*p(?:hos)?\b|\bi\.?p\b|\bphos\b|무기\s*인|인\s*\(\s*p\s*\))""", 0.5, 15.0, """urine|소변"""),
        Field("ca", """(calcium|\bca\b|칼슘)""", 4.0, 16.0, """ion|이온|corrected|보정|urine|소변"""),
        Field("k", """(potassium|\bk\+?(?![A-Za-z])|칼륨)""", 1.5, 9.0, """urine|소변"""),
        Field("na", """(sodium|\bna\+?(?![A-Za-z])|나트륨)""", 100.0, 180.0, """urine|소변"""),
        Field("hb", """(hemoglobin|haemoglobin|\bhgb\b|\bhb\b|혈색소)""", 3.0, 22.0, """a1c|당화"""),
        Field("alb", """(albumin|\balb\b|알부민)""", 1.0, 6.5, """urine|소변|micro|미세|ratio|/\s*cr|\bu[\s.\-]|globulin|a/g"""),
        Field("cr", """(creatinine|\bcr\b|크레아티닌)""", 0.1, 20.0, """urine|소변|ratio|\bu[\s.\-]|clearance|/\s*cr|alb|bun|protein|단백"""),
    )

    private val NUM = Regex("""(?<![A-Za-z\d.])(\d+(?:\.\d+)?)""")
    private val DATE = Regex("""(20\d{2})\s*[.\-/년]\s*(\d{1,2})\s*[.\-/월]\s*(\d{1,2})""")
    private val NUMTOK = Regex("""^[<>≤≥]?(\d+(?:\.\d+)?)[HLhl↑↓▲▼*]?$""")
    private val HAS_LETTER = Regex("[A-Za-z가-힣]")
    private val RANGE = Regex("""(\d+(?:\.\d+)?)\s*[~∼\-–]\s*(\d+(?:\.\d+)?)""")
    private val UPPER = Regex("""[<≤]\s*(\d+(?:\.\d+)?)""")
    private val LOWER = Regex("""[>≥]\s*(\d+(?:\.\d+)?)""")
    private val FLAGS = setOf("H", "L", "h", "l", "↑", "↓", "▲", "▼", "*")
    private val PARENS = Regex("""\([^)]*\)|\[[^\]]*]""")
    private val SPACES = Regex("""\s+""")

    data class Block(val date: LocalDate?, val values: Map<String, LabValue>)
    private data class Row(val name: String, val value: Double, val unit: String?, val lo: Double?, val hi: Double?)

    /** ChatGPT 답변 복사 시 섞이는 마크다운 이스케이프(\~ \*)·굵게 표시 제거 */
    private fun clean(text: String) = text
        .replace(Regex("""\\([~*_\-#|.\[\]()<>+])"""), "$1")
        .replace("**", "")
        .replace("µ", "μ")

    fun parseMany(text: String): List<Block> {
        val chunks = mutableListOf<String>()
        var cur = mutableListOf<String>()
        for (line in clean(text).lines()) {
            if (DATE.containsMatchIn(line) && cur.any { isLabLine(it) }) {
                chunks += cur.joinToString("\n"); cur = mutableListOf()
            }
            cur += line
        }
        if (cur.isNotEmpty()) chunks += cur.joinToString("\n")
        return chunks.map { parseBlock(it) }.filter { it.values.isNotEmpty() }
    }

    fun parseBlock(raw: String): Block {
        val text = clean(raw)
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val date = DATE.find(text)?.let { m ->
            runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
        }
        val out = LinkedHashMap<String, LabValue>()

        // 1) 한 줄 형식
        for (line in lines) {
            val r = row(line) ?: continue
            val f = FIELDS.firstOrNull { it.re.containsMatchIn(r.name) && !(it.skip?.containsMatchIn(line) ?: false) }
            if (f != null) {
                if (out.containsKey(f.key)) continue
                val v = unitFix(f.key, r.value, line)
                if (v in f.min..f.max) {
                    val converted = abs(v - r.value) > 1e-9
                    out[f.key] = if (converted) LabValue(round3(v), name = r.name)
                    else LabValue(round3(v), r.unit, r.lo, r.hi, r.name)
                }
            } else {
                val key = "x:" + r.name.lowercase().replace(SPACES, "")
                if (!out.containsKey(key)) out[key] = LabValue(r.value, r.unit, r.lo, r.hi, r.name)
            }
        }

        // 2) 값이 다음 줄에 있는 형식: 주요 항목만
        for (i in lines.indices) {
            val (f, m) = matchLine(lines[i]) ?: continue
            if (out.containsKey(f.key)) continue
            val pool = mutableListOf(lines[i].substring(m.range.last + 1))
            for (j in i + 1 until minOf(lines.size, i + 4)) {
                if (matchLine(lines[j]) != null || DATE.containsMatchIn(lines[j])) break
                pool += lines[j]
            }
            val ctx = pool.joinToString(" ")
            val v = pool.asSequence()
                .flatMap { seg -> NUM.findAll(seg.replace(PARENS, " ")).map { it.groupValues[1].toDouble() } }
                .map { unitFix(f.key, it, ctx) }
                .firstOrNull { it in f.min..f.max }
            if (v != null) out[f.key] = LabValue(round3(v))
        }
        return Block(date, out)
    }

    private fun isLabLine(line: String) = !line.trimStart().startsWith("#") && (row(line) != null || matchLine(line) != null)

    private fun matchLine(line: String): Pair<Field, MatchResult>? {
        if (DATE.containsMatchIn(line) || line.trimStart().startsWith("#")) return null
        for (f in FIELDS) {
            val m = f.re.find(line) ?: continue
            if (f.skip?.containsMatchIn(line) == true) continue
            return f to m
        }
        return null
    }

    private fun row(line: String): Row? {
        if (DATE.containsMatchIn(line) || line.trimStart().startsWith("#")) return null
        val toks = line.trim().split(SPACES)
        val idx = toks.indexOfFirst { NUMTOK.matches(it) }
        if (idx < 1) return null
        val name = toks.subList(0, idx).joinToString(" ").trim(':', '-', '·', ' ')
        if (!HAS_LETTER.containsMatchIn(name)) return null
        val value = NUMTOK.find(toks[idx])!!.groupValues[1].toDouble()
        val rest = toks.drop(idx + 1).filterNot { it in FLAGS }
        val unit = rest.firstOrNull { HAS_LETTER.containsMatchIn(it) || it.contains('%') || it.contains('/') }
        val refText = rest.filter { it != unit }.joinToString(" ")
        var lo: Double? = null
        var hi: Double? = null
        val range = RANGE.find(refText)
        if (range != null) {
            lo = range.groupValues[1].toDouble(); hi = range.groupValues[2].toDouble()
        } else {
            UPPER.find(refText)?.let { hi = it.groupValues[1].toDouble() }
            LOWER.find(refText)?.let { lo = it.groupValues[1].toDouble() }
        }
        return Row(name, value, unit, lo, hi)
    }

    private fun unitFix(key: String, v: Double, ctx: String): Double {
        val c = ctx.lowercase()
        return when {
            key == "acr" && Regex("""mg\s*/\s*mmol""").containsMatchIn(c) -> v * 8.84
            (key == "ca" || key == "cacorr") && c.contains("mmol") && v < 4 -> v * 4.008
            key == "phos" && c.contains("mmol") && v < 3 -> v * 3.097
            key == "alb" && Regex("""g\s*/\s*l\b""").containsMatchIn(c) && v > 10 -> v / 10
            key == "cr" && Regex("""(µ|u|μ)mol""").containsMatchIn(c) && v > 20 -> v / 88.4
            else -> v
        }
    }

    private fun round3(v: Double) = round(v * 1000) / 1000
}
