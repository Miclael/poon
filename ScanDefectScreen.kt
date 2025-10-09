// File: app/src/main/java/com/scancode/myapp/ScanDefectScreen.kt
// Purpose: 首頁（掃碼 + 缺點彙整 + 訊息產生）；沿用現有「下拉選單＝AlertDialog+LazyColumn」寫法，強化清單高度/捲動與快速連點穩定性；加入暫存行與 HomeScanMessageStore 同步
// Author: 阿龍哥專案
// Last-Update: 2025-09-11 (Asia/Taipei)

package com.scancode.myapp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ScanDefectScreen(
    viewModel: ScanDefectViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 1) ViewModel 狀態
    val partNumber by viewModel.partNumber.collectAsState()
    val orderNumber by viewModel.orderNumber.collectAsState()
    val defectOptions by viewModel.defectOptions.collectAsState()
    val selectedDefect by viewModel.selectedDefect.collectAsState()
    val singleReport by viewModel.singleReport.collectAsState()
    val batchReport by viewModel.batchReport.collectAsState()
    val defectMessages by viewModel.defectMessages.collectAsState()
    val savedMessages by viewModel.savedMessages.collectAsState()

    // 2) UI 狀態
    var scanTarget by remember { mutableStateOf("") }
    var showAddDefectDialog by remember { mutableStateOf(false) }
    var newDefectText by remember { mutableStateOf("") }
    var showSelectDefectDialog by remember { mutableStateOf(false) }
    var showDeleteDefectDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    // 防快速連點（200ms 節流）
    var lastOpenTs by remember { mutableStateOf(0L) }
    fun canOpenNow(): Boolean {
        val now = System.currentTimeMillis()
        return if (now - lastOpenTs > 200) { lastOpenTs = now; true } else false
    }

    // 初始化缺點清單
    LaunchedEffect(Unit) {
        viewModel.defectOptions.value = loadDefectList(context)
    }

    // ZXing 掃描
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            when (scanTarget) {
                "part" -> viewModel.partNumber.value = result.contents
                "order" -> viewModel.orderNumber.value = result.contents
            }
        }
    }

    // 訊息組裝（含「暫存行」：未按加入也會先顯示）
    val message = buildString {
        if (partNumber.isNotEmpty()) append("料號：$partNumber\n")
        if (orderNumber.isNotEmpty()) {
            val formattedOrder = if (orderNumber.length >= 11)
                orderNumber.substring(0, 10) + "-" + orderNumber.takeLast(1)
            else orderNumber
            append("製令：$formattedOrder\n")
        }
        // 已加入的訊息
        defectMessages.forEach {
            append(it.first)
            if (it.second.isNotEmpty()) append(" 單報 ${it.second}spnl")
            if (it.third.isNotEmpty()) append(" 大報${it.third}片")
            append("\n")
        }
        // 暫存行：選了缺點且輸入數值就先顯示
        if (selectedDefect.isNotEmpty() && (singleReport.isNotEmpty() || batchReport.isNotEmpty())) {
            append(selectedDefect)
            if (singleReport.isNotEmpty()) append(" 單報 ${singleReport}spnl")
            if (batchReport.isNotEmpty()) append(" 大報${batchReport}片")
            append("\n")
        }
        if (defectMessages.isNotEmpty() ||
            (selectedDefect.isNotEmpty() && (singleReport.isNotEmpty() || batchReport.isNotEmpty()))
        ) {
            append("請派員確認\n")
        }
    }.trimEnd()

    // ✅ 同步到 HomeScanMessageStore（懸浮輸入會取用）
    LaunchedEffect(message) {
        scope.launch { HomeScanMessageStore.setLatest(context, message) }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(12.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ===== 料號 =====
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = partNumber,
                onValueChange = { viewModel.partNumber.value = it },
                label = { Text("料號") },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                scanTarget = "part"
                scanLauncher.launch(
                    ScanOptions().apply {
                        setDesiredBarcodeFormats(ScanOptions.ONE_D_CODE_TYPES)
                        setPrompt("請掃描『料號』條碼")
                        setBeepEnabled(true)
                        setOrientationLocked(true)
                        captureActivity = MyCaptureActivity::class.java
                    }
                )
            }) { Text("掃描") }
        }

        Spacer(Modifier.height(8.dp))

        // ===== 製令 =====
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = orderNumber,
                onValueChange = { viewModel.orderNumber.value = it },
                label = { Text("製令") },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                scanTarget = "order"
                scanLauncher.launch(
                    ScanOptions().apply {
                        setDesiredBarcodeFormats(ScanOptions.ONE_D_CODE_TYPES)
                        setPrompt("請掃描『製令』條碼")
                        setBeepEnabled(true)
                        setOrientationLocked(true)
                        captureActivity = MyCaptureActivity::class.java
                    }
                )
            }) { Text("掃描") }
        }

        Spacer(Modifier.height(12.dp))

        // ===== 第一列：新增缺點 / 刪除缺點 =====
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { showAddDefectDialog = true },
                modifier = Modifier.weight(1f)
            ) { Text("新增缺點") }

            Button(
                onClick = { showDeleteDefectDialog = true },
                enabled = selectedDefect.isNotEmpty(),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text("刪除缺點") }
        }

        Spacer(Modifier.height(8.dp))

        // ===== 第二列：選擇缺點（你的下拉作法） / 加入訊息 =====
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = {
                    if (canOpenNow()) showSelectDefectDialog = true
                },
                modifier = Modifier.weight(1f)
            ) { Text(if (selectedDefect.isEmpty()) "請選擇缺點" else selectedDefect) }

            Spacer(Modifier.width(8.dp))

            Button(
                onClick = {
                    if (selectedDefect.isNotEmpty() &&
                        (singleReport.isNotEmpty() || batchReport.isNotEmpty())
                    ) {
                        viewModel.defectMessages.value =
                            defectMessages + Triple(selectedDefect, singleReport, batchReport)
                        viewModel.selectedDefect.value = ""
                        viewModel.singleReport.value = ""
                        viewModel.batchReport.value = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF388E3C)),
                modifier = Modifier.height(44.dp)
            ) { Text("加入訊息") }
        }

        Spacer(Modifier.height(8.dp))

        // ===== 第三列：單報 / 大報 =====
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = singleReport,
                onValueChange = { if (it.all { ch -> ch.isDigit() }) viewModel.singleReport.value = it },
                label = { Text("單報") },
                modifier = Modifier
                    .width(120.dp)
                    .height(70.dp),
                keyboardOptions = KeyboardOptions.Default.copy(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Next
                )
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = batchReport,
                onValueChange = { if (it.all { ch -> ch.isDigit() }) viewModel.batchReport.value = it },
                label = { Text("大報") },
                modifier = Modifier
                    .width(120.dp)
                    .height(70.dp),
                keyboardOptions = KeyboardOptions.Default.copy(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Done
                )
            )
        }

        Spacer(Modifier.height(8.dp))

        // ===== 訊息內容（唯讀） =====
        OutlinedTextField(
            value = message,
            onValueChange = {},
            label = { Text("訊息內容") },
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
            readOnly = true,
            singleLine = false
        )

        Spacer(Modifier.height(8.dp))

        // ===== 儲存 / 清除 / 複製 =====
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = { showSaveDialog = true }, modifier = Modifier.weight(1f)) { Text("儲存") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { showClearDialog = true }, modifier = Modifier.weight(1f)) { Text("清除") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(message)) },
                modifier = Modifier.weight(1f)
            ) { Text("複製") }
        }
    }

    // ===== Dialogs =====

    // 新增缺點
    if (showAddDefectDialog) {
        AlertDialog(
            onDismissRequest = { showAddDefectDialog = false },
            title = { Text("新增缺點") },
            text = {
                OutlinedTextField(
                    value = newDefectText,
                    onValueChange = { newDefectText = it },
                    label = { Text("請輸入缺點") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newDefectText.trim()
                    if (name.isNotEmpty() && !defectOptions.contains(name)) {
                        val newList = defectOptions + name
                        viewModel.defectOptions.value = newList
                        saveDefectList(context, newList)
                        viewModel.selectedDefect.value = name
                    }
                    newDefectText = ""
                    showAddDefectDialog = false
                }) { Text("儲存") }
            },
            dismissButton = { TextButton(onClick = { showAddDefectDialog = false }) { Text("取消") } }
        )
    }

    // 刪除缺點
    if (showDeleteDefectDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDefectDialog = false },
            title = { Text("確認刪除") },
            text = { Text("確定要刪除目前選擇的缺點嗎？") },
            confirmButton = {
                TextButton(onClick = {
                    if (selectedDefect.isNotEmpty() && defectOptions.contains(selectedDefect)) {
                        val newList = defectOptions.filter { it != selectedDefect }
                        viewModel.defectOptions.value = newList
                        saveDefectList(context, newList)
                        viewModel.selectedDefect.value = ""
                    }
                    showDeleteDefectDialog = false
                }) { Text("確定") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDefectDialog = false }) { Text("取消") } }
        )
    }

    // 選擇缺點（你的「下拉面板」：AlertDialog + LazyColumn，有固定最大高度）
    if (showSelectDefectDialog) {
        AlertDialog(
            onDismissRequest = { showSelectDefectDialog = false },
            title = { Text("選擇缺點") },
            text = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                ) {
                    if (defectOptions.isEmpty()) {
                        Text("請先新增缺點", color = Color.Gray)
                    } else {
                        LazyColumn {
                            items(defectOptions) { option ->
                                ListItem(
                                    headlineContent = { Text(option) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.selectedDefect.value = option
                                            showSelectDefectDialog = false
                                        }
                                )
                                Divider()
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showSelectDefectDialog = false }) { Text("取消") } }
        )
    }

    // 儲存訊息（改為 RecordStore）
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("儲存訊息") },
            text = { Text("確定要儲存目前訊息內容嗎？") },
            confirmButton = {
                TextButton(onClick = {
                    if (message.isNotEmpty()) {
                        // ✅ 改用 DataStore 儲存
                        scope.launch { RecordStore.append(context, message) }
                    }
                    showSaveDialog = false
                }) { Text("確定") }
            },
            dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("取消") } }
        )
    }

    // 清除
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清除資料") },
            text = { Text("確定要清除所有欄位嗎？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.partNumber.value = ""
                    viewModel.orderNumber.value = ""
                    viewModel.selectedDefect.value = ""
                    viewModel.singleReport.value = ""
                    viewModel.batchReport.value = ""
                    viewModel.defectMessages.value = emptyList()
                    showClearDialog = false
                }) { Text("確定") }
            },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("取消") } }
        )
    }
}
