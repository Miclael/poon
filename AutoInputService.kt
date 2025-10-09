package com.scancode.myapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class AutoInputService : AccessibilityService() {

    // —— 既有參數（標記流程用） ——
    private val DEFAULT_WAIT_CANDIDATES_MS = 900L
    private val DEFAULT_MAX_STEPS = 160
    private val ULTRA_STEP_DELAY_MS = 40L
    private val ULTRA_CHECK_EVERY_STEPS = 2
    private val POST_SELECT_DELAY_MS = 90L

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val mutex = Mutex()
    private var isReady = false
    private var currentGroupName: String? = null

    private val TAG = "AutoMention"

    companion object {
        private var instance: AutoInputService? = null
        fun getInstance(): AutoInputService? = instance
        fun isServiceAvailable(): Boolean = instance?.isReady == true

        /** 單人標記（剪貼簿流程） */
        fun autoMentionUser(name: String): Boolean {
            val s = getInstance() ?: return false
            if (!s.isReady) return false
            s.scope.launch { s.mutex.withLock { s.performSingleMention(name) } }
            return true
        }

        suspend fun autoMentionUserAwait(name: String): Boolean {
            val s = getInstance() ?: return false
            if (!s.isReady) return false
            return s.mutex.withLock { s.scope.async { s.performSingleMention(name) }.await() }
        }

        fun setCurrentGroupName(group: String?) { getInstance()?.currentGroupName = group }

        /** ✅ 首頁訊息輸入（僅用剪貼簿；保留 Mention span；只貼一次） */
        suspend fun pasteHomeMessageAwait(text: String, newlineFirst: Boolean = true): Boolean {
            val s = getInstance() ?: return false
            if (!s.isReady) return false
            return s.mutex.withLock { s.scope.async { s.performPasteHomeMessage(text, newlineFirst) }.await() }
        }
    }

    // ===== Service lifecycle =====
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isReady = true
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
        info.packageNames = arrayOf("jp.naver.line.android")
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 100
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { isReady = false }

    // ======================================================
    // 首頁訊息貼上（剪貼簿；延長保留；不裁剪尾端；僅貼一次）
    // ======================================================
    private suspend fun performPasteHomeMessage(text: String, newlineFirst: Boolean): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        Log.d(TAG, "paste_message_start(newlineFirst=$newlineFirst, len=${text.length})")

        // 只允許 LINE 前景
        if (rootInActiveWindow?.packageName != "jp.naver.line.android") {
            Log.d(TAG, "paste_message_fail(reason=not_line_foreground, phase=guard)")
            return false
        }

        // 找輸入框 + 聚焦 + 游標尾端
        val tVerifyStart = SystemClock.elapsedRealtime()
        val input = findLineInputField() ?: run {
            Log.d(TAG, "paste_message_fail(reason=input_not_found, phase=verify)")
            return false
        }
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        moveCursorToEnd(input)
        val tVerifyOk = SystemClock.elapsedRealtime()
        Log.d(TAG, "verify_input_ok(ms=${tVerifyOk - tVerifyStart})")

        // 準備要貼的字串：去掉正文尾端空白；如需先換行，只補「一個」且僅當現有結尾不是 \n
        val bodyBase = text.trimEnd()
        val existing = input.text?.toString() ?: ""
        val needNL = newlineFirst && (existing.lastOrNull() != '\n')
        val toPaste = (if (needNL) "\n" else "") + bodyBase
        Log.d(TAG, "paste_compose(needNL=$needNL, bodyTrimmed=${bodyBase.length}, pasteLen=${toPaste.length})")

        // —— 只貼一次 ——
        if (toPaste.isNotEmpty()) {
            val ok = appendByPaste(input, toPaste)
            if (!ok) {
                Log.d(TAG, "paste_message_fail(reason=paste_action_failed, phase=body)")
                return false
            }
            // 等 LINE 從剪貼簿讀取完
            delay(500)
            moveCursorToEnd(input)
        }

        val tBody = SystemClock.elapsedRealtime()
        Log.d(TAG, "paste_message_body_ok(ms=${tBody - tVerifyOk})")
        Log.d(TAG, "phaseTimer(paste=${tBody - tVerifyOk}, total=${SystemClock.elapsedRealtime() - t0})")
        return true
    }

    // =========================
    // 既有：單人標記流程（維持只用剪貼簿）
    // =========================
    private suspend fun performSingleMention(userNameRaw: String): Boolean {
        if (rootInActiveWindow?.packageName != "jp.naver.line.android") return false
        delay(200)

        val input = findLineInputField() ?: return false
        moveCursorToEnd(input)
        delay(80)

        val userName = normalizeName(userNameRaw)

        // 防「@@」
        trimTrailingAts(input, keepOne = true)
        val before = input.text?.toString().orEmpty()
        if (!before.endsWith("@")) {
            val ok = appendByPaste(input, "@") // 不用 setText
            if (!ok) return false
        }

        val (timeoutMs, maxRetries, estimatedSteps) = run {
            val group = currentGroupName ?: ""
            try {
                val s = SmartMentionMemory.getAdaptiveSearchStrategy(this, group, userName)
                Triple(s.timeoutMs, s.maxRetries, s.estimatedSteps)
            } catch (_: Throwable) {
                Triple(DEFAULT_WAIT_CANDIDATES_MS, DEFAULT_MAX_STEPS, 0)
            }
        }

        delay(timeoutMs)

        if (selectExactUserFromCandidates(userName)) {
            delay(POST_SELECT_DELAY_MS)
            currentGroupName?.let { g -> SmartMentionMemory.recordScrollSteps(this, g, userName, 0) }
            return true
        }

        val counter = StepsCounter()
        val found = ultraFastScrollFindAndSelect(
            userName = userName,
            inputField = input,
            maxSteps = maxRetries,
            estimatedSteps = estimatedSteps,
            counter = counter
        )
        if (found) {
            delay(POST_SELECT_DELAY_MS)
            currentGroupName?.let { g -> SmartMentionMemory.recordScrollSteps(this, g, userName, counter.value) }
            return true
        }

        trimTrailingAts(input, keepOne = false)
        return false
    }

    private class StepsCounter(var value: Int = 0)

    private suspend fun ultraFastScrollFindAndSelect(
        userName: String,
        inputField: AccessibilityNodeInfo,
        maxSteps: Int,
        estimatedSteps: Int,
        counter: StepsCounter
    ): Boolean {
        val containers = findCandidateContainers(inputField)
        if (containers.isEmpty()) return false

        var pre = 0
        while (pre < estimatedSteps) {
            var moved = false
            for (c in containers) {
                if (!c.isVisibleToUser) continue
                if (c.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                    counter.value += 1
                    pre += 1
                    delay(ULTRA_STEP_DELAY_MS)
                    if (selectExactUserFromCandidates(userName)) return true
                    moved = true
                    break
                }
            }
            if (!moved) break
        }

        var step = 0
        while (step < maxSteps) {
            var scrolled = false
            for (c in containers) {
                if (!c.isVisibleToUser) continue
                if (c.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                    counter.value += 1
                    scrolled = true
                    delay(ULTRA_STEP_DELAY_MS)
                    if (selectExactUserFromCandidates(userName)) return true
                    break
                }
            }
            if (!scrolled) break
            step++
            if (step % ULTRA_CHECK_EVERY_STEPS == 0) {
                if (selectExactUserFromCandidates(userName)) return true
            }
        }
        return selectExactUserFromCandidates(userName)
    }

    private fun selectExactUserFromCandidates(userName: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(userName)
        for (n in nodes) {
            val t = n.text?.toString() ?: continue
            if (normalizeName(t) == userName && n.isVisibleToUser) {
                val clickable = findClickableAncestor(n)
                if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
            }
        }
        return false
    }

    private fun findCandidateContainers(inputField: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = mutableListOf<AccessibilityNodeInfo>()

        val ids = listOf(
            "jp.naver.line.android:id/mentions_list",
            "jp.naver.line.android:id/recycler_view",
            "jp.naver.line.android:id/recyclerview",
            "jp.naver.line.android:id/list",
            "jp.naver.line.android:id/suggestion_list"
        )
        for (id in ids) {
            try {
                root.findAccessibilityNodeInfosByViewId(id).forEach { n ->
                    if (n.isVisibleToUser && (n.isScrollable || looksScrollable(n))) out.add(n)
                }
            } catch (_: Throwable) { }
        }

        val inputRect = Rect().also { inputField.getBoundsInScreen(it) }
        val near = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
        fun dfs(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (n.isVisibleToUser && (n.isScrollable || looksScrollable(n))) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                if (r.bottom <= inputRect.top) {
                    val d = abs(r.bottom - inputRect.top)
                    near += n to d
                }
            }
            for (i in 0 until n.childCount) dfs(n.getChild(i))
        }
        dfs(root)
        near.sortedBy { it.second }.forEach { (n, _) -> if (!out.contains(n)) out.add(n) }
        return out
    }

    private fun looksScrollable(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString()?.lowercase() ?: return false
        return cls.contains("recyclerview") || cls.contains("listview") || cls.contains("scrollview")
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var cur = node; var hop = 0
        while (cur != null && hop < 6) {
            if (cur.isClickable && cur.isVisibleToUser) return cur
            cur = cur.parent; hop++
        }
        return node
    }

    /** 尋找 LINE 的輸入框：先以已知 id，再 DFS 找可見且可編輯的 EditText */
    private fun findLineInputField(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null

        // 1) 已知 id（新版 LINE）
        try {
            root.findAccessibilityNodeInfosByViewId("jp.naver.line.android:id/edittext_input")
                .firstOrNull()?.let { return it }
        } catch (_: Throwable) { /* ignore */ }

        // 2) 兼容舊版/其他變體
        val fallbackIds = listOf(
            "jp.naver.line.android:id/message_edittext",
            "jp.naver.line.android:id/chat_input",
            "jp.naver.line.android:id/input"
        )
        for (id in fallbackIds) {
            try {
                root.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.let { return it }
            } catch (_: Throwable) { /* ignore */ }
        }

        // 3) DFS 找可見且 isEditable 的 EditText
        fun dfs(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.className == "android.widget.EditText" && node.isEditable && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) dfs(node.getChild(i))?.let { return it }
            return null
        }
        return dfs(root)
    }

    /** 以剪貼簿貼文字；延遲恢復剪貼簿，避免 LINE 還沒讀到內容就被清掉。 */
    private fun appendByPaste(node: AccessibilityNodeInfo, text: String): Boolean {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val old = cm.primaryClip
        return try {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("mention_append", text))
            // 等待系統建好剪貼簿
            Thread.sleep(40)
            moveCursorToEnd(node)
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)

            // 加長保留時間：最少 1200ms，依長度放大，最多 3000ms
            val holdMs = min(3000L, max(1200L, text.length * 12L))
            scope.launch {
                try { delay(holdMs); old?.let { cm.setPrimaryClip(it) } } catch (_: Throwable) { }
            }
            ok
        } catch (_: Throwable) {
            scope.launch { try { delay(200); old?.let { cm.setPrimaryClip(it) } } catch (_: Throwable) {} }
            false
        }
    }

    /** 游標移到尾端（不觸發鍵盤事件）。 */
    private fun moveCursorToEnd(node: AccessibilityNodeInfo) {
        val len = node.text?.length ?: 0
        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, len)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
    }

    /** 清理尾端多餘的 @；keepOne=true 僅保留 1 個。 */
    private fun trimTrailingAts(node: AccessibilityNodeInfo, keepOne: Boolean) {
        val txt = node.text?.toString() ?: return
        var count = 0
        for (i in txt.length - 1 downTo 0) if (txt[i] == '@') count++ else break
        val target = if (keepOne) 1 else 0
        val needCut = (count - target).coerceAtLeast(0)
        repeat(needCut) {
            val len = node.text?.length ?: return
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, len - 1)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
            node.performAction(AccessibilityNodeInfo.ACTION_CUT)
        }
        moveCursorToEnd(node)
    }

    private fun normalizeName(s: String): String {
        val nfkc = Normalizer.normalize(s, Normalizer.Form.NFKC)
        return nfkc.trim()
    }
}
