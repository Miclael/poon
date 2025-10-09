package com.scancode.myapp

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun RecordScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // UI 狀態
    var search by rememberSaveable { mutableStateOf("") }
    var showDetail by rememberSaveable { mutableStateOf(false) }
    var selectedIndex by rememberSaveable { mutableStateOf(-1) }

    var showDelete by rememberSaveable { mutableStateOf(false) }
    var showClearAll by rememberSaveable { mutableStateOf(false) }

    // 資料：全面走 DataStore
    var records by remember { mutableStateOf<List<Record>>(emptyList()) }

    // 第一次進畫面：舊資料（SharedPreferences）搬到 DataStore 並載入
    LaunchedEffect(Unit) {
        RecordStore.importLegacyIfEmpty(context)
        records = RecordStore.load(context)
    }

    // 搜尋
    val filtered by remember(records, search) {
        derivedStateOf {
            if (search.isBlank()) records
            else records.filter { it.text.contains(search, ignoreCase = true) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) }
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(12.dp)
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("關鍵字搜尋") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = { showClearAll = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("清空全部") }
            }

            Spacer(Modifier.height(8.dp))

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("沒有紀錄", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(
                        items = filtered,
                        key = { _, rec -> rec.hashCode() }
                    ) { _, rec ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedIndex = records.indexOf(rec)
                                    showDetail = true
                                }
                                .padding(vertical = 6.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    rec.timeStr(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Text(
                                    rec.text,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            IconButton(
                                onClick = {
                                    selectedIndex = records.indexOf(rec)
                                    showDelete = true
                                }
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "刪除",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        Divider()
                    }
                }
            }
        }
    }

    // 詳細 Dialog
    if (showDetail && selectedIndex in records.indices) {
        val rec = records[selectedIndex]
        AlertDialog(
            onDismissRequest = { showDetail = false },
            title = { Text("紀錄詳情") },
            text = {
                Column {
                    Text("時間：${rec.timeStr()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(rec.text)
                }
            },
            confirmButton = {
                Row {
                    Button(onClick = {
                        clipboard.setText(AnnotatedString(rec.text))
                        // 這裡不能用 LaunchedEffect，改用 coroutine scope
                        scope.launch { snackbar.showSnackbar("已複製到剪貼簿") }
                    }) { Text("複製") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            putExtra(Intent.EXTRA_TEXT, rec.text)
                            type = "text/plain"
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(Intent.createChooser(send, "分享訊息"))
                    }) { Text("分享") }
                }
            },
            dismissButton = { TextButton(onClick = { showDetail = false }) { Text("關閉") } }
        )
    }

    // 單筆刪除
    if (showDelete && selectedIndex in records.indices) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("刪除紀錄") },
            text = { Text("確定要刪除此筆紀錄嗎？") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        RecordStore.deleteAt(context, selectedIndex)
                        records = RecordStore.load(context)
                    }
                    showDelete = false
                }) { Text("確定") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } }
        )
    }

    // 清空全部
    if (showClearAll) {
        AlertDialog(
            onDismissRequest = { showClearAll = false },
            title = { Text("清空全部") },
            text = { Text("確定要清空所有紀錄嗎？此動作無法復原。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        RecordStore.clear(context)
                        records = emptyList()
                    }
                    showClearAll = false
                }) { Text("確定") }
            },
            dismissButton = { TextButton(onClick = { showClearAll = false }) { Text("取消") } }
        )
    }
}
