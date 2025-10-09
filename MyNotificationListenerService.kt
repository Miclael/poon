// File: app/src/main/java/com/scancode/myapp/MyNotificationListenerService.kt
@file:Suppress("SpellCheckingInspection")

package com.scancode.myapp

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val TAG_SVC = "LineNLService"
private const val LINE_PKG = "jp.naver.line.android"
private const val EMOJI_SUMMARY = "傳送了表情符號"

class MyNotificationListenerService : NotificationListenerService() {
    // ===== 協程域 =====
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ===== 去重緩存（最近 N 筆）=====
    private val recent: ArrayDeque<String> = ArrayDeque()
    private val recentSet = HashSet<String>()
    private val maxRecent = 200

    // ===== 合併視窗 & 併句 Pending =====
    private val mergeWindowMs = 8_000L
    /** key = "$group|$sender" */
    private val pendingByKey = ConcurrentHashMap<String, Pending>()

    /** 上一次已 emit 的群組（用於省略群組名） */
    @Volatile private var lastEmittedGroup: String? = null

    // ===== 型別 =====
    private sealed class Segment {
        data class Text(val text: String) : Segment()
        data class Photo(var count: Int) : Segment()
    }

    private data class Pending(
        val group: String,
        val sender: String,
        val segments: MutableList<Segment> = mutableListOf(),
        var lastTs: Long = System.currentTimeMillis(),
        var flushJob: Job? = null
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG_SVC, "listener_connected")
        clearAllCaches()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { cancelAllFlushJobs() } catch (_: Throwable) {}
        scope.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName != LINE_PKG) return
        scope.launch {
            try {
                val prefs = applicationContext.lineMonitorDataStore.data.first()
                val running = prefs[KEY_MONITOR_RUNNING] ?: false
                if (!running) { Log.d(TAG_SVC, "ignore: monitor=false"); return@launch }

                val n = sbn.notification ?: return@launch
                if (sbn.isOngoing || (n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return@launch

                val parsed = parseLineMessage(n) ?: run { Log.d(TAG_SVC, "parse=null"); return@launch }
                val (group, sender, rawText, postedAt) = parsed

                val enabled = prefs[KEY_ENABLED].orEmpty()
                if (group !in enabled) { Log.d(TAG_SVC, "skip group=$group (not enabled)"); return@launch }

                val cleaned = cleanText(rawText)
                val noDupName = stripDupSenderPrefix(sender, cleaned)
                val (normalized, isEmojiOnly) = normalizeEmojiInText(noDupName)
                if (normalized.isBlank()) { Log.d(TAG_SVC, "cleaned blank"); return@launch }

                val id = md5("$group|$sender|$normalized|$postedAt")
                if (seen(id)) { Log.d(TAG_SVC, "dup id=$id"); return@launch }

                flushAllExcept("$group|$sender")

                val key = "$group|$sender"
                val now = System.currentTimeMillis()
                val p = pendingByKey.getOrPut(key) { Pending(group, sender) }

                if (isPhotoMessage(normalized)) {
                    val last = p.segments.lastOrNull()
                    if (last is Segment.Photo) last.count += 1 else p.segments += Segment.Photo(1)
                } else {
                    p.segments += Segment.Text(normalized)
                }
                p.lastTs = now

                p.flushJob?.cancel()
                p.flushJob = scope.launch {
                    delay(mergeWindowMs)
                    flushOnce(key)
                }
            } catch (e: CancellationException) {
                // ignore
            } catch (e: Exception) {
                Log.e(TAG_SVC, "post error: ${e.message}", e)
            }
        }
    }

    // ===== 解析（優先 MessagingStyle）=====
    private fun parseLineMessage(n: Notification): Quadruple<String, String, String, Long>? {
        val extras = n.extras ?: return null
        val postedAt = n.`when`
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style != null) {
            val convTitle = style.conversationTitle?.toString().orEmpty()
            val msgs = style.messages
            if (convTitle.isNotBlank() && !msgs.isNullOrEmpty()) {
                val last = msgs.last()
                val sender = last.person?.name?.toString().orEmpty().ifBlank { "（無名）" }
                val text = last.text?.toString().orEmpty()
                if (text.isNotBlank()) return Quadruple(convTitle, sender, text, postedAt)
            }
        }

        val group = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()).orEmpty()

        val rawText = when {
            !TextUtils.isEmpty(extras.getCharSequence(Notification.EXTRA_TEXT)) ->
                extras.getCharSequence(Notification.EXTRA_TEXT).toString()
            !TextUtils.isEmpty(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)) ->
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
            else -> ""
        }

        var sender = ""
        var text = rawText
        val idx = rawText.indexOf('：')
        if (idx in 1 until rawText.length - 1) {
            sender = rawText.substring(0, idx)
            text = rawText.substring(idx + 1)
        }
        if (group.isBlank() || text.isBlank()) return null
        if (sender.isBlank()) sender = "（無名）"
        return Quadruple(group, sender, text, postedAt)
    }

    private fun cleanText(src: String): String {
        var s = src
        s = s.replace(Regex("^\\s*\\[(\\d{1,2}):(\\d{2})]\\s*"), "")
        s = s.replace(Regex("@\\S+"), "")
        s = s.replace(Regex("(?<![A-Za-z0-9-])(\\d{1,2}):(\\d{2})(?![A-Za-z0-9-])")) {
            "${it.groupValues[1]}點${it.groupValues[2]}分"
        }
        s = s.replace(Regex("(?<!\\d)(\\d{1,2})/(\\d{1,2})(?!\\d)")) {
            "${it.groupValues[1]}月${it.groupValues[2]}日"
        }
        s = s.replace(Regex("\\s{2,}"), " " ).trim()
        return s
    }

    private fun stripDupSenderPrefix(sender: String, text: String): String {
        val s = sender.trim()
        if (s.isEmpty() || text.isEmpty()) return text
        val esc = Regex.escape(s)
        val m = Regex("^\\s*(?:$esc)[:：]?\\s*(.*)$").find(text)
        if (m != null) {
            val rest = m.groupValues[1].trimStart()
            val looksLikeAction = Regex("^(傳送|傳|發送|上傳|分享|送出|貼了|傳了|送了)").containsMatchIn(rest)
            if (looksLikeAction) return rest.trim()
        }
        return text
    }

    private fun normalizeEmojiInText(input: String): Pair<String, Boolean> {
        if (input.isEmpty()) return "" to false
        val sb = StringBuilder()
        var emojiCount = 0
        var i = 0
        while (i < input.length) {
            val cp = Character.codePointAt(input, i)
            if (isEmojiCodePoint(cp)) { emojiCount++ }
            else { if (cp != 0x200D && cp != 0xFE0F) { sb.appendCodePoint(cp) } }
            i += Character.charCount(cp)
        }
        val noEmoji = sb.toString().trim()
        return if (noEmoji.isEmpty() && emojiCount > 0) { EMOJI_SUMMARY to true } else { noEmoji to false }
    }

    private fun isEmojiCodePoint(cp: Int): Boolean {
        if (cp == 0x200D || cp == 0xFE0F) return true
        if (cp in 0x1F000..0x1FAFF) return true
        if (cp in 0x2600..0x27FF) return true
        if (cp in 0x2B00..0x2BFF) return true
        if (cp in 0x2900..0x297F) return true
        if (cp in 0x3030..0x303D) return true
        if (cp in 0x3297..0x3299) return true
        if (cp in 0x24C2..0x24C2) return true
        return false
    }

    private fun isPhotoMessage(text: String): Boolean {
        val t = text.lowercase(Locale.TAIWAN)
        return t.contains("傳送了照片") || t.contains("傳送了圖片") || t.contains("傳送了相片") ||
                t == "照片" || t == "圖片" || t == "相片" || t.endsWith("了照片") || t.endsWith("了圖片") || t.endsWith("了相片")
    }

    private fun flushAllExcept(currentKey: String) { pendingByKey.keys.toList().forEach { k -> if (k != currentKey) flushOnce(k) } }

    @Synchronized
    private fun flushOnce(key: String) {
        val p = pendingByKey[key] ?: return
        if (p.segments.isEmpty()) { pendingByKey.remove(key)?.flushJob?.cancel(); return }

        val content = buildString {
            p.segments.forEachIndexed { idx, seg ->
                if (idx > 0) append("，")
                when (seg) {
                    is Segment.Text -> append(seg.text)
                    is Segment.Photo -> append(if (seg.count <= 1) "傳送了照片" else "傳送了 ${seg.count} 張照片")
                }
            }
        }.trim()

        if (content.isNotEmpty()) {
            val includeGroup = (lastEmittedGroup != p.group)
            val display = if (includeGroup) { "${p.group}：${p.sender}：$content" } else { "${p.sender}：$content" }

            Log.d(TAG_SVC, "emit: $display")
            LineNotificationBus.messageFlow.tryEmit(display)
            lastEmittedGroup = p.group

            // ✅ 直接持久化（即使 UI 沒有收集也不會漏）
            runCatching {
                val gson = com.google.gson.Gson()
                val ds = applicationContext.lineMonitorDataStore
                val messageBufferSize = 200
                // 用單次讀寫避免競態（此處頻率不高）
                runBlocking(Dispatchers.IO) {
                    val prefs = ds.data.first()
                    val oldJson = prefs[KEY_MESSAGES_JSON]
                    val list = if (oldJson.isNullOrBlank()) mutableListOf<String>()
                               else gson.fromJson(oldJson, Array<String>::class.java).toMutableList()
                    // 加上當下時間 [HH:mm] 展示（UI 仍可再加工）
                    val ts = java.text.SimpleDateFormat("HH:mm", java.util.Locale.TAIWAN).format(java.util.Date())
                    val textForTts = display
                    list.add("[$ts] $display")
                    if (list.size > messageBufferSize) list.removeAt(0)
                    ds.edit { it[KEY_MESSAGES_JSON] = gson.toJson(list) }

                    // 🔊 背景 TTS：透過前景服務播報
                    LineTtsService.speakNow(applicationContext, textForTts)
                }
            }
        }

        p.flushJob?.cancel()
        pendingByKey.remove(key)
    }

    private fun seen(id: String): Boolean {
        if (recentSet.contains(id)) return true
        recent.addLast(id); recentSet.add(id)
        if (recent.size > maxRecent) recent.removeFirst().let { recentSet.remove(it) }
        return false
    }

    private fun cancelAllFlushJobs() { pendingByKey.values.forEach { it.flushJob?.cancel() }; pendingByKey.clear() }
    private fun clearAllCaches() { recent.clear(); recentSet.clear(); cancelAllFlushJobs(); lastEmittedGroup = null }

    private fun md5(s: String): String = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
