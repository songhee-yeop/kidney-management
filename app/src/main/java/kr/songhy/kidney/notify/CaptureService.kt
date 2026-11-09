package kr.songhy.kidney.notify

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.view.Display
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import kr.songhy.kidney.MainActivity
import kr.songhy.kidney.data.Store
import kr.songhy.kidney.domain.MyInha
import kotlin.concurrent.thread

/**
 * My 인하 검사결과 자동 읽기.
 * 접근성 버튼을 누른 순간에만 동작한다: 화면 맨 위에 보이는 날짜를 대상으로, 접힌 분류(화학검사 등)를
 * 펼치고 다음 날짜가 나올 때까지 스스로 스크롤하며 글자를 모아 신장 관리 앱의 "검사결과 추가" 화면으로 넘긴다.
 * 읽는 중에 버튼을 다시 누르면 멈춘다. 읽은 글자는 앱 안에서만 쓰인다.
 */
class CaptureService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var cancel = false
    private var ocr: TextRecognizer? = null

    /** 글자 인식. 응답이 없으면 한 번 더 시도한다. */
    private fun recognize(r: TextRecognizer, bmp: Bitmap, log: MutableList<String>): Text? {
        repeat(2) { attempt ->
            try {
                return Tasks.await(r.process(InputImage.fromBitmap(bmp, 0)), 30, TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                log += "OCR 응답 없음 (${attempt + 1}회)"
            }
        }
        return null
    }

    override fun onServiceConnected() {
        accessibilityButtonController.registerAccessibilityButtonCallback(
            object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController) = capture()
            },
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { cancel = true }

    private fun toast(msg: String) = main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun capture() {
        if (running) { cancel = true; toast("읽기를 멈춥니다"); return }
        if (appRoot() == null) {
            toast("My 인하 검사결과 화면에서 눌러주세요"); return
        }
        val dates = screenNodes().map { it.text }.filter { MyInha.isDate(it) }.distinct()
        if (dates.isEmpty()) {
            toast("검사일을 찾지 못해 화면 정보를 앱으로 보냅니다")
            val d = dump()
            main.post { openApp("[읽은 원본]\n" + d) }
            return
        }
        val all = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_MODE, MODE_ALL) == MODE_ALL
        // "새 결과 전부": 앱에 없는 날짜만, "날짜 하나": 화면 맨 위 날짜
        val todo = if (all) {
            Store.init(this)
            val saved = Store.data.labs.map { it.date.toString() }.toSet()
            dates.filter { MyInha.isoDate(it) !in saved }
        } else {
            listOfNotNull(screenNodes().firstOrNull { MyInha.isDate(it.text) && it.node.isVisibleToUser }?.text ?: dates.first())
        }
        if (todo.isEmpty()) { toast("새 검사결과가 없습니다. 조회된 ${dates.size}개 날짜가 모두 저장되어 있습니다"); return }

        running = true
        cancel = false
        toast(if (todo.size > 1) "${todo.size}개 날짜를 읽습니다. 화면을 만지지 마세요" else "${MyInha.isoDate(todo[0])} 읽는 중… 화면을 만지지 마세요")
        thread {
            val log = mutableListOf<String>()
            val texts = mutableListOf<String>()
            try {
                for ((n, target) in todo.withIndex()) {
                    if (cancel) break
                    val iso = MyInha.isoDate(target)
                    if (todo.size > 1) toast("${n + 1}/${todo.size} $iso 읽는 중")
                    log += "── $iso"
                    if (!bringToTop(target)) { log += "날짜 위치로 이동 실패"; continue }
                    val text = readOne(target, log)
                    if (text.lines().size >= 2) texts += text else log += "읽기 실패"
                }
                // 형식을 못 알아보면 원본 글자를 그대로 넘겨서 확인할 수 있게 한다
                val payload = when {
                    texts.isNotEmpty() -> texts.joinToString("\n\n")
                    cancel -> null
                    else -> "[읽은 원본]\n" + log.joinToString("\n", postfix = "\n") + dump()
                }
                if (payload == null) toast("멈췄습니다")
                else {
                    if (cancel) toast("멈췄습니다. 읽은 ${texts.size}개 날짜만 보여드립니다")
                    main.post { openApp(payload) }
                }
            } catch (e: Throwable) {
                // 에러 내용 전체를 앱으로 넘겨 복사해서 보낼 수 있게 한다
                toast("읽지 못했습니다. 오류 내용을 앱으로 보냅니다")
                val trace = e.stackTrace.take(12).joinToString("\n") { "  at $it" }
                val partial = if (texts.isNotEmpty()) "\n[읽은 부분]\n" + texts.joinToString("\n\n") else ""
                val report = "[읽은 원본]\n오류: ${e.javaClass.name}: ${e.message}\n$trace\n" +
                    log.joinToString("\n", postfix = "\n") + partial
                main.post { openApp(report) }
            } finally {
                runCatching { ocr?.close() }
                ocr = null
                running = false
                cancel = false
            }
        }
    }

    /** 날짜 하나: 분류를 펼치고 끝까지 읽는다 */
    private fun readOne(target: String, log: MutableList<String>): String {
        val clicked = mutableSetOf<Int>()
        var expands = 0
        while (!cancel && expands < 20 && expandOne(target, clicked, log)) { expands++; Thread.sleep(650) }
        Thread.sleep(500)
        if (cancel) return ""
        // 화면이 글자를 내주면 그대로 읽고, 비어 있으면(My 인하) 화면 글자 인식으로 읽는다
        val textMode = section(target).any { it.text == "결과" }
        log += if (textMode) "읽기 방식: 화면 글자" else "읽기 방식: 글자 인식(OCR)"
        return if (textMode) readByText(target) else readByOcr(target, log)
    }

    /** 대상 날짜 영역의 노드들 */
    private fun section(target: String): List<Item> {
        val ns = screenNodes()
        val ti = ns.indexOfFirst { it.text == target }
        if (ti < 0) return emptyList()
        val end = (ti + 1 until ns.size).firstOrNull { MyInha.isDate(ns[it].text) } ?: ns.size
        return ns.subList(ti, end)
    }

    /**
     * 대상 날짜 머리글이 화면 위쪽 70% 안에 보이게 한다. 보이면 바로 읽기 시작.
     * 스크롤해도 위치가 거의 안 바뀌면(목록 끝) 그 자리에서 읽는다.
     */
    private fun bringToTop(target: String): Boolean {
        val h = resources.displayMetrics.heightPixels
        val appBar = (h * 0.11).toInt()
        val okBottom = (h * 0.70).toInt()
        fun find() = screenNodes().firstOrNull { it.text == target }
        fun onScreen(it: Item) = it.bounds.height() > 0 && it.bounds.top in 0 until h && it.bounds.bottom <= h
        repeat(15) { attempt ->
            if (cancel) return false
            val item = find() ?: return false
            val top = item.bounds.top
            if (onScreen(item) && top in appBar..okBottom) return true
            if (!onScreen(item) && attempt == 0) {
                item.node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                Thread.sleep(500)
                return@repeat
            }
            val delta = when {
                onScreen(item) -> top - (h * 0.20).toInt() // 위로 가렸으면 음수(아래로), 너무 아래면 양수
                else -> {
                    val ns = screenNodes()
                    val firstVisible = ns.indexOfFirst { onScreen(it) }
                    if (ns.indexOfFirst { it.text == target } > firstVisible) (h * 0.6).toInt() else -(h * 0.6).toInt()
                }
            }
            swipeBy(delta.coerceIn(-(h * 0.7).toInt(), (h * 0.7).toInt()))
            Thread.sleep(450)
            val after = find() ?: return false
            // 거의 안 움직였으면 목록 끝: 보이는 자리에서 읽는다
            if (onScreen(item) && onScreen(after) && kotlin.math.abs(after.bounds.top - top) < 30) return true
        }
        return find()?.let { onScreen(it) } == true
    }

    /** 내용을 px만큼 위로(양수) 또는 아래로(음수) 천천히 옮긴다 */
    private fun swipeBy(px: Int) {
        val h = resources.displayMetrics.heightPixels.toFloat()
        if (px > 0) swipe(0.85f, 0.85f - px / h) else swipe(0.15f, 0.15f - px / h)
    }

    private fun readByText(target: String): String {
        val acc = mutableListOf<String>()
        var idle = 0
        var guard = 0
        while (!cancel && guard++ < 150) {
            val added = merge(acc, screenNodes().map { it.text })
            if (MyInha.reachedEnd(acc, target)) break
            idle = if (added == 0) idle + 1 else 0
            if (idle >= 3) break
            if (!scroll(forward = true)) { Thread.sleep(500); merge(acc, screenNodes().map { it.text }); break }
            Thread.sleep(650)
        }
        return MyInha.toLabText(acc, target)
    }

    /**
     * 화면을 찍어 기기 안에서 글자를 인식한다(사진은 저장·전송하지 않음).
     * 대상 날짜 머리글 아래 ~ 다음 날짜 머리글 위만 읽고, 화면의 45%씩 내려가며 반복한다.
     */
    private fun readByOcr(target: String, log: MutableList<String>): String {
        if (Build.VERSION.SDK_INT < 30) { log += "OCR: Android 11 이상 필요"; return "" }
        val iso = MyInha.isoDate(target) ?: return ""
        val recognizer = ocr ?: TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()).also { ocr = it }
        val byName = LinkedHashMap<String, String>()
        val dm = resources.displayMetrics
        var lastSig = ""
        var shots = 0
        try {
            while (!cancel && shots++ < 60) {
                val ns = screenNodes()
                val header = ns.firstOrNull { it.text == target }?.bounds
                val ti = ns.indexOfFirst { it.text == target }
                val next = ns.withIndex().firstOrNull { (i, it) ->
                    i > ti && MyInha.isDate(it.text) && it.node.isVisibleToUser && it.bounds.top in 1 until dm.heightPixels
                }?.value?.bounds
                val top = maxOf(header?.bottom?.takeIf { it in 0 until dm.heightPixels } ?: 0, (dm.heightPixels * 0.11).toInt())
                val bottom = minOf(next?.top ?: dm.heightPixels, (dm.heightPixels * 0.93).toInt())

                val bmp = screenshot()
                if (bmp == null) { log += "OCR: 화면 캡처 실패"; break }
                val result = recognize(recognizer, bmp, log) ?: run { bmp.recycle(); null }
                if (result == null) break
                bmp.recycle()
                val lines = result.textBlocks.flatMap { it.lines }
                    .filter { it.boundingBox != null }
                    .filter { val c = it.boundingBox!!.centerY(); c in top..bottom }
                val ordered = rows(lines)
                val sig = ordered.joinToString("|")
                if (sig == lastSig) break // 더 내려가지 않음 = 끝
                lastSig = sig
                if (shots == 1) log += "OCR 첫 화면:\n  " + ordered.joinToString("\n  ")
                val chunk = MyInha.toLabText(listOf(target) + MyInha.ocrTokens(ordered), target)
                chunk.lines().drop(1).forEach { line ->
                    val key = byName.keys.firstOrNull { k -> k == line.substringBefore("  ") || MyInha.sameItem(byName[k]!!, line) }
                        ?: line.substringBefore("  ")
                    val prev = byName[key]
                    if (prev == null || line.length > prev.length) byName[key] = line
                }
                if (next != null) break // 다음 날짜가 화면에 들어왔으면 끝
                swipe(0.80f, 0.30f)
                Thread.sleep(500)
            }
        } finally {
            // 엔진은 전체 읽기가 끝난 뒤 한 번만 닫는다 (날짜마다 닫으면 다음 날짜에서 멈춤)
        }
        log += "OCR: 화면 ${shots}장, 항목 ${byName.size}개"
        return if (byName.isEmpty()) "" else "검사일 $iso\n" + byName.values.joinToString("\n")
    }

    /** OCR 줄을 같은 높이끼리 묶어 위→아래, 왼쪽→오른쪽 순서로 */
    private fun rows(lines: List<Text.Line>): List<String> {
        val sorted = lines.sortedBy { it.boundingBox!!.centerY() }
        val out = mutableListOf<String>()
        var row = mutableListOf<Text.Line>()
        var rowY = -1000
        val tol = (14 * resources.displayMetrics.density).toInt()
        fun flush() { row.sortedBy { it.boundingBox!!.left }.forEach { out += it.text }; row = mutableListOf() }
        for (l in sorted) {
            val cy = l.boundingBox!!.centerY()
            if (row.isNotEmpty() && kotlin.math.abs(cy - rowY) > tol) flush()
            if (row.isEmpty()) rowY = cy
            row += l
        }
        if (row.isNotEmpty()) flush()
        return out
    }

    private fun screenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        val latch = CountDownLatch(1)
        var out: Bitmap? = null
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                val hb = r.hardwareBuffer
                out = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                hb.close()
                latch.countDown()
            }
            override fun onFailure(errorCode: Int) { latch.countDown() }
        })
        latch.await(5, TimeUnit.SECONDS)
        return out
    }

    /** 천천히 밀어서 관성 없이 일정하게 내린다 */
    private fun swipe(fromY: Float, toY: Float) {
        val dm = resources.displayMetrics
        val x = dm.widthPixels * 0.5f
        val f = fromY.coerceIn(0.12f, 0.92f)
        val t = toY.coerceIn(0.12f, 0.92f)
        if (kotlin.math.abs(f - t) < 0.02f) return
        val y1 = dm.heightPixels * f
        val y2 = dm.heightPixels * t
        // 1) 손가락으로 밀고  2) 끝에서 잠깐 멈춘 뒤 뗀다 → 관성 스크롤(튕김) 없이 정확히 그만큼만 이동
        val move = GestureDescription.StrokeDescription(Path().apply { moveTo(x, y1); lineTo(x, y2) }, 0, 450, true)
        val hold = move.continueStroke(Path().apply { moveTo(x, y2); lineTo(x + 1, y2) }, 0, 150, false)
        fun send(stroke: GestureDescription.StrokeDescription) {
            val latch = CountDownLatch(1)
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { latch.countDown() }
                override fun onCancelled(d: GestureDescription?) { latch.countDown() }
            }, null)
            latch.await(3, TimeUnit.SECONDS)
        }
        send(move)
        send(hold)
    }

    private fun openApp(text: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    private class Item(val text: String, val node: AccessibilityNodeInfo, val bounds: Rect)

    /** 접근성 버튼 오버레이가 아니라 실제 앱(My 인하) 창을 고른다 */
    private fun appRoot(): AccessibilityNodeInfo? {
        val candidates = runCatching { windows }.getOrNull().orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }
            .filter { it.packageName != packageName }
        val best = candidates.maxByOrNull { r -> Rect().also { r.getBoundsInScreen(it) }.let { it.width() * it.height() } }
        return best ?: rootInActiveWindow?.takeIf { it.packageName != packageName }
    }

    /** 화면에 있는 글자 조각을 위에서 아래 순서로 (입력칸 제외, 글자가 없으면 설명 글자 사용) */
    private fun screenNodes(): List<Item> {
        val root = appRoot() ?: return emptyList()
        val out = mutableListOf<Item>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 60) return
            if (!n.isEditable) {
                // 글자가 빈 문자열("")인 경우가 있어 비어 있으면 설명 글자를 쓴다
                val t = n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: n.contentDescription?.toString()?.trim()
                if (!t.isNullOrEmpty()) { val r = Rect(); n.getBoundsInScreen(r); out += Item(t, n, r) }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }

    /** 진단용: 앱이 본 화면 구조를 그대로 */
    private fun dump(): String {
        val root = appRoot() ?: return "(읽을 수 있는 창 없음, 활성 창: ${rootInActiveWindow?.packageName})"
        val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        val sb = StringBuilder("앱 버전: $ver · 패키지: ${root.packageName}\n")
        var count = 0
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 60 || count > 400) return
            val t = n.text?.toString()?.trim().orEmpty()
            val d = n.contentDescription?.toString()?.trim().orEmpty()
            // 결과 영역 진단을 위해 글자 없는 칸도 기록 (자식 수 표시)
            run {
                count++
                sb.append("  ".repeat(minOf(depth, 8))).append(n.className?.toString()?.substringAfterLast('.'))
                if (t.isNotEmpty()) sb.append(" t=").append(t)
                if (d.isNotEmpty()) sb.append(" d=").append(d)
                if (n.isScrollable) sb.append(" [scroll]")
                if (t.isEmpty() && d.isEmpty()) sb.append(" (").append(n.childCount).append(")")
                sb.append('\n')
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return sb.toString()
    }

    /**
     * 대상 날짜 영역(대상 머리글 ~ 다음 날짜) 안에서 접혀 있는 분류 머리글을 찾아 맨 아래 것 하나를 누른다.
     * 접힘 판단: 펼치기 동작을 지원하면 그것으로, 아니면 바로 다음 글자가 다른 분류/날짜인지로 본다.
     */
    private fun expandOne(target: String, clicked: MutableSet<Int>, log: MutableList<String>): Boolean {
        val ns = screenNodes()
        val ti = ns.indexOfFirst { it.text == target }
        if (ti < 0) return false // 순번이 흔들리지 않도록 대상 날짜가 보일 때만 펼친다
        val end = (ti + 1 until ns.size).firstOrNull { MyInha.isDate(ns[it].text) } ?: ns.size
        val h = resources.displayMetrics.heightPixels
        // 대상 날짜 안의 분류 머리글을 위에서부터 순번(0,1,2…)으로
        val sections = (ti + 1 until end).filter { MyInha.isSection(ns[it].text) }
        val candidates = sections.withIndex().filter { (ord, i) ->
            if (ord in clicked) return@filter false
            val it = ns[i]
            val actions = (clickableOf(it.node) ?: it.node).actionList.map { a -> a.id }
            when (val byText = MyInha.isCollapsed(it.text)) {
                null -> when {
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND.id in actions -> true
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE.id in actions -> false
                    i + 1 >= ns.size || it.bounds.top > h * 0.85 -> false
                    else -> MyInha.isSection(ns[i + 1].text) || MyInha.isDate(ns[i + 1].text)
                }
                else -> byText
            }
        }
        // 아래쪽부터 눌러야 위쪽 버튼 위치가 밀리지 않는다
        val (ord, idx) = candidates.lastOrNull() ?: return false
        clicked += ord
        val how = click(ns[idx])
        log += "펼치기 #$ord ${ns[idx].text} → $how"
        return true
    }

    private fun clickableOf(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth++ < 6) {
            if (cur.isClickable) return cur
            cur = cur.parent
        }
        return null
    }

    /** 화면 안에 보이면 손가락 탭, 아니면 클릭 동작 */
    private fun click(item: Item): String {
        val h = resources.displayMetrics.heightPixels
        val r = item.bounds
        if (r.height() > 0 && r.top >= 0 && r.bottom <= h) {
            val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
            val tap = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build()
            if (dispatchGesture(tap, null, null)) return "탭"
        }
        val ok = clickableOf(item.node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        return if (ok) "클릭동작" else "실패"
    }

    /** 이전에 모은 끝부분과 새 화면의 앞부분이 겹치는 만큼 빼고 붙인다. 새로 붙은 개수를 돌려준다. */
    private fun merge(acc: MutableList<String>, screen: List<String>): Int {
        if (screen.isEmpty()) return 0
        if (acc.isEmpty()) { acc += screen; return screen.size }
        var best = 0
        for (k in minOf(acc.size, screen.size) downTo 1) {
            if (acc.subList(acc.size - k, acc.size) == screen.subList(0, k)) { best = k; break }
        }
        if (best == 0) {
            // 겹치는 부분이 없으면 이미 모은 내용이 화면 안에 통째로 있는지 확인(전체가 한 번에 보이는 화면)
            val idx = indexOfSub(screen, acc.takeLast(minOf(8, acc.size)))
            if (idx >= 0) best = idx + minOf(8, acc.size)
        }
        val add = screen.drop(best)
        acc += add
        return add.size
    }

    private fun indexOfSub(list: List<String>, sub: List<String>): Int {
        if (sub.isEmpty() || sub.size > list.size) return -1
        for (i in 0..list.size - sub.size) if (list.subList(i, i + sub.size) == sub) return i
        return -1
    }

    private fun findScrollable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isScrollable) return n
        for (i in 0 until n.childCount) findScrollable(n.getChild(i))?.let { return it }
        return null
    }

    private fun scroll(forward: Boolean): Boolean {
        val node = findScrollable(appRoot())
        if (node != null) {
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (node.performAction(action)) return true
        }
        // 스크롤 동작을 지원하지 않는 화면이면 손가락으로 미는 동작으로 대신한다
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val (y1, y2) = if (forward) dm.heightPixels * 0.75f to dm.heightPixels * 0.30f else dm.heightPixels * 0.30f to dm.heightPixels * 0.75f
        val path = Path().apply { moveTo(x, y1); lineTo(x, y2) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 350)).build()
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        const val PREFS = "capture"
        const val PREF_MODE = "mode"
        const val MODE_ALL = "all"
        const val MODE_ONE = "one"

        fun isEnabled(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.startsWith(ctx.packageName + "/") }
        }
    }
}
