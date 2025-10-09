// File: app/src/main/java/com/scancode/myapp/LineMonitorScreen.kt
// Purpose: 監聽 UI（群組/開關/訊息清單，可滑動）＋ 單一消費者 TTS 佇列
// Note: 最新訊息顯示在「最下方」，並在清單中加上顯示時間（時:分），自動捲到底部
// Author: 老大專案規格
// Last-Update: 2025-10-02 (Asia/Taipei)
@file:Suppress("SpellCheckingInspection")

package com.scancode.myapp

import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.preferences.core.edit
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG_UI = "LineMonitor"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineMonitorScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gson = remember { Gson() }

    // ===== SSOT：DataStore 狀態（直接從 Flow 收集） =====
    val groups by context.lineMonitorDataStore.data
        .map { it[KEY_GROUPS]?.toList().orEmpty() }
        .collectAsState(initial = emptyList())

    val enabledGroups by context.lineMonitorDataStore.data
        .map { it[KEY_ENABLED].orEmpty() }
        .collectAsState(initial = emptySet())

    // 預設為 false（未監聽）
    val isMonitoring by context.lineMonitorDataStore.data
        .map { it[KEY_MONITOR_RUNNING] ?: false }
        .collectAsState(initial = false)

    // ===== 訊息列表（顯示 & 永續化） =====
    val groupListState = rememberLazyListState()
    val messageListState = rememberLazyListState()

    var messages by remember { mutableStateOf<List<String>>(emptyList()) }
    val messageBufferSize = 200

    // ===== TTS：單一消費者／可重建的 Channel =====
    val tts = remember { TextToSpeech(context) { status -> Log.d(TAG_UI, "TTS init status=$status") } }
    var ttsReady by remember { mutableStateOf(false) }
    var speakChan by remember { mutableStateOf(Channel<String>(Channel.UNLIMITED)) }
    var speakJob by remember { mutableStateOf<Job?>(null) }

    // 初始化：語系 + 載入歷史訊息
    LaunchedEffect(Unit) {
        try {
            val r = tts.setLanguage(Locale.TRADITIONAL_CHINESE)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.getDefault())
            }
            ttsReady = true
        } catch (e: Exception) {
            Log.e(TAG_UI, "TTS lang set failed: ${e.message}")
            ttsReady = false
        }

        runCatching {
            val prefs = context.lineMonitorDataStore.data.first()
            val json = prefs[KEY_MESSAGES_JSON]
            messages = if (json.isNullOrBlank()) emptyList()
            else gson.fromJson(json, Array<String>::class.java).toList().take(messageBufferSize)
            Log.d(TAG_UI, "Loaded history messages=${messages.size}")
        }.onFailure { Log.e(TAG_UI, "Load history failed: ${it.message}") }
    }

    // 根據 isMonitoring 切換：重建 Channel / 啟動或關閉消費者
    LaunchedEffect(isMonitoring, ttsReady) {
        speakJob?.cancel()
        if (!isMonitoring || !ttsReady) {
            // 停止：清空佇列、停止 TTS、3 秒靜默
            speakChan = Channel(Channel.UNLIMITED)
            withContext(Dispatchers.Default) {
                try { tts.stop() } catch (_: Throwable) {}
                delay(3000)
            }
            return@LaunchedEffect
        }

        // 開始：重建新的 Channel 並啟動單一消費者
        speakChan = Channel(Channel.UNLIMITED)
        speakJob = launch(Dispatchers.Default) {
            for (txt in speakChan) {
                try {
                    Log.d(TAG_UI, "speak_start")
                    withContext(Dispatchers.Main) {
                        tts.speak(txt, TextToSpeech.QUEUE_FLUSH, null, "line_tts")
                    }
                    while (isActive && tts.isSpeaking) delay(80)
                    Log.d(TAG_UI, "speak_done")
                    delay(100)
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG_UI, "speak_error: ${e.message}")
                }
            }
        }

        // 自測播報，驗證佇列與 TTS
        kotlin.runCatching { speakChan.trySend("開始監聽") }
    }

    // 收 Bus：將新訊息「加到最下方」、加上時間 [HH:mm] 顯示、持久化、並送入 TTS（短訊息也播）
    LaunchedEffect(Unit) {
        val timeFmt = SimpleDateFormat("HH:mm", Locale.TAIWAN)
        LineNotificationBus.messageFlow.collectLatest { cleaned ->
            if (!isMonitoring) return@collectLatest
            if (cleaned.isBlank()) return@collectLatest

            val ts = timeFmt.format(Date())
            val display = "[$ts] $cleaned"

            messages = (messages + display).takeLast(messageBufferSize)

            scope.launch {
                context.lineMonitorDataStore.edit { it[KEY_MESSAGES_JSON] = gson.toJson(messages) }
            }

            // 播報仍使用不含時間的純內容（避免把 [08:54] 讀出來）
            kotlin.runCatching { speakChan.trySend(cleaned) }
                .onFailure { Log.w(TAG_UI, "enqueue_failed: ${it.message}") }
        }
    }

    // 新訊息來時自動捲到底部（顯示最新在最下方）
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            messageListState.scrollToItem(messages.lastIndex)
        }
    }

    // 釋放
    DisposableEffect(Unit) {
        onDispose {
            try { speakJob?.cancel(); tts.stop(); tts.shutdown() } catch (_: Throwable) {}
        }
    }

    // ===== 畫面 =====
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // 權限狀態每次重組即時計算（不使用 remember）
        val hasNotif =
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)

        if (!hasNotif) {
            AssistCard("尚未開啟『通知存取權』", "前往開啟") {
                runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            }
            Spacer(Modifier.height(8.dp))
        }

        // 群組列（勾選啟用）
        Text("📡 監聽群組", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))

        var showAdd by remember { mutableStateOf(false) }
        var newName by remember { mutableStateOf("") }
        var confirmDeleteFor by remember { mutableStateOf<String?>(null) } // 刪除確認對話框狀態
        var editTarget by remember { mutableStateOf<String?>(null) } // 編輯對話框目標群組
        var editName by remember { mutableStateOf("") } // 編輯中的新名稱
        var editError by remember { mutableStateOf<String?>(null) } // 驗證錯誤訊息

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (groups.isEmpty()) "尚未新增群組" else "已建立 ${groups.size} 個群組")
            TextButton(onClick = { showAdd = true }) { Text("➕ 新增群組") }
        }

        if (showAdd) {
            AlertDialog(
                onDismissRequest = { showAdd = false; newName = "" },
                confirmButton = {
                    TextButton(onClick = {
                        val nm = newName.trim()
                        if (nm.isNotEmpty() && !groups.contains(nm)) {
                            scope.launch {
                                val newList = (groups + nm).toSet()
                                context.lineMonitorDataStore.edit { it[KEY_GROUPS] = newList }
                            }
                        }
                        showAdd = false; newName = ""
                    }) { Text("新增") }
                },
                dismissButton = { TextButton({ showAdd = false; newName = "" }) { Text("取消") } },
                title = { Text("新增監聽群組") },
                text = {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("群組名稱") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            )
        }

        if (groups.isNotEmpty()) {
            LazyColumn(
                state = groupListState,
                modifier = Modifier.heightIn(max = 140.dp)
            ) {
                itemsIndexed(groups) { _, g ->
                    Row(
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = enabledGroups.contains(g),
                                onCheckedChange = { checked ->
                                    val next = if (checked) enabledGroups + g else enabledGroups - g
                                    scope.launch { context.lineMonitorDataStore.edit { it[KEY_ENABLED] = next } }
                                }
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(g)
                        }
                        Row {
                            // ✏️ 編輯按鈕（在刪除旁）
                            IconButton(onClick = {
                                editTarget = g
                                editName = g
                                editError = null
                            }) {
                                Icon(Icons.Default.Edit, contentDescription = "編輯")
                            }
                            // 🗑️ 刪除按鈕
                            IconButton(onClick = {
                                confirmDeleteFor = g
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "刪除")
                            }
                        }
                    }
                }
            }
        }

        // 刪除確認對話框
        if (confirmDeleteFor != null) {
            val target = confirmDeleteFor!!
            AlertDialog(
                onDismissRequest = { confirmDeleteFor = null },
                title = { Text("刪除群組") },
                text = { Text("確定要刪除『$target』嗎？") },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            val newList = groups.filter { it != target }.toSet()
                            val newEnabled = enabledGroups - target
                            context.lineMonitorDataStore.edit {
                                it[KEY_GROUPS] = newList
                                it[KEY_ENABLED] = newEnabled
                            }
                        }
                        confirmDeleteFor = null
                    }) { Text("刪除") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDeleteFor = null }) { Text("取消") }
                }
            )
        }

        // ✏️ 編輯對話框
        if (editTarget != null) {
            val target = editTarget!!
            val canConfirm = editName.trim().isNotEmpty() && (editName.trim() == target || !groups.contains(editName.trim()))
            AlertDialog(
                onDismissRequest = { editTarget = null; editName = ""; editError = null },
                title = { Text("編輯群組名稱") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = editName,
                            onValueChange = { name ->
                                editName = name
                                editError = when {
                                    name.trim().isEmpty() -> "名稱不可為空"
                                    name.trim() != target && groups.contains(name.trim()) -> "名稱已存在"
                                    else -> null
                                }
                            },
                            label = { Text("新名稱") },
                            singleLine = true,
                            isError = editError != null,
                            supportingText = { if (editError != null) Text(editError!!, color = MaterialTheme.colorScheme.error) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(enabled = canConfirm, onClick = {
                        val nm = editName.trim()
                        scope.launch {
                            val replaced = groups.map { if (it == target) nm else it }.toSet()
                            val newEnabled = if (enabledGroups.contains(target)) (enabledGroups - target) + nm else enabledGroups
                            context.lineMonitorDataStore.edit {
                                it[KEY_GROUPS] = replaced
                                it[KEY_ENABLED] = newEnabled
                            }
                        }
                        editTarget = null; editName = ""; editError = null
                    }) { Text("儲存") }
                },
                dismissButton = { TextButton(onClick = { editTarget = null; editName = ""; editError = null }) { Text("取消") } }
            )
        }

        Spacer(Modifier.height(8.dp))

        // 訊息列表（最新在最下方，可滑動，帶 [HH:mm]）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("📨 訊息（${messages.size}/$messageBufferSize）", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = {
                scope.launch {
                    messages = emptyList()
                    context.lineMonitorDataStore.edit { it[KEY_MESSAGES_JSON] = Gson().toJson(messages) }
                }
            }) { Text("清除列表") }
        }

        Card(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                state = messageListState,
                modifier = Modifier.fillMaxSize().padding(8.dp)
            ) {
                if (messages.isEmpty()) {
                    item { Text("尚無監聽訊息", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    itemsIndexed(messages) { _, msg ->
                        Text("• $msg", modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 監聽切換（只改 DataStore）
        Button(
            onClick = {
                scope.launch {
                    if (isMonitoring) {
                        context.lineMonitorDataStore.edit { it[KEY_MONITOR_RUNNING] = false }
                        Log.d(TAG_UI, "monitor set -> false")
                    } else {
                        context.lineMonitorDataStore.edit { it[KEY_MONITOR_RUNNING] = true }
                        Log.d(TAG_UI, "monitor set -> true")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isMonitoring) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(if (isMonitoring) Icons.Default.MicOff else Icons.Default.Mic, null)
            Spacer(Modifier.width(8.dp))
            Text(if (isMonitoring) "停止監聽" else "開始監聽")
        }
    }
}

@Composable
private fun AssistCard(title: String, action: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title)
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}
