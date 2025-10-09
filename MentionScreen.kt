// 檔名：MentionScreen.kt
// 功能：群組清單頁，啟動懸浮標記；含新增/刪除群組、權限提示與快速設定。

package com.scancode.myapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MentionScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    var groupList by remember { mutableStateOf(listOf<String>()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var groupToDelete by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { groupList = MentionGroupStore.getAllGroups(context) }

    fun tryLaunchLine() {
        runCatching {
            val lineIntent = context.packageManager
                .getLaunchIntentForPackage("jp.naver.line.android")
                ?: error("找不到 LINE")
            lineIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(lineIntent)
            Toast.makeText(context, "已啟動 LINE", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "找不到 LINE，請手動開啟", Toast.LENGTH_LONG).show()
        }
    }

    fun startFloating() {
        runCatching {
            val serviceIntent = Intent(context, FloatingMentionService::class.java)
                .putExtra("mode", "group_selection")
            context.startService(serviceIntent)
            tryLaunchLine()
        }.onFailure {
            Toast.makeText(context, "懸浮視窗啟動失敗：${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(16.dp)
                .padding(bottom = 80.dp)
        ) {
            Text("群組清單", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            PermissionStatusCard(context)
            Spacer(Modifier.height(8.dp))
            AccessibilityQuickSetupButton(context)

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { showAddDialog = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(24.dp)
            ) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("新增群組")
            }

            Spacer(Modifier.height(20.dp))
            groupList.forEach { g ->
                GroupDisplayItem(
                    groupName = g,
                    onEdit = { navController.navigate("editGroup/$g") },
                    onDelete = { groupToDelete = g; showDeleteDialog = true }
                )
                Spacer(Modifier.height(10.dp))
            }
        }

        Box(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(16.dp)
        ) {
            Button(
                onClick = {
                    if (groupList.isEmpty()) Toast.makeText(context, "請先新增群組", Toast.LENGTH_SHORT).show()
                    else startFloating()
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (groupList.isNotEmpty())
                        MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                ),
                enabled = groupList.isNotEmpty()
            ) {
                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp))
                Text(if (groupList.isNotEmpty()) "開始標記（${groupList.size} 個群組）" else "請先新增群組")
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    val name = newGroupName.trim()
                    if (name.isNotEmpty() && !groupList.contains(name)) {
                        scope.launch {
                            MentionGroupStore.addGroup(context, name)
                            groupList = MentionGroupStore.getAllGroups(context)
                            newGroupName = ""; showAddDialog = false
                        }
                    }
                }) { Text("新增") }
            },
            dismissButton = { TextButton({ showAddDialog = false }) { Text("取消") } },
            title = { Text("新增群組") },
            text = {
                OutlinedTextField(
                    value = newGroupName, onValueChange = { newGroupName = it },
                    label = { Text("群組名稱") }, singleLine = true
                )
            }
        )
    }

    if (showDeleteDialog && groupToDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false; groupToDelete = null },
            confirmButton = {
                TextButton(onClick = {
                    val target = groupToDelete ?: return@TextButton
                    scope.launch {
                        MentionGroupStore.removeGroup(context, target)
                        groupList = MentionGroupStore.getAllGroups(context)
                        showDeleteDialog = false; groupToDelete = null
                    }
                }) { Text("確認刪除") }
            },
            dismissButton = { TextButton({ showDeleteDialog = false; groupToDelete = null }) { Text("取消") } },
            title = { Text("刪除群組") },
            text = { Text("確定要刪除群組「${groupToDelete}」嗎？此動作會同時刪除該群組所有成員。") }
        )
    }
}

@Composable
fun PermissionStatusCard(context: Context) {
    var hasA11y by remember { mutableStateOf(false) }
    var hasOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    LaunchedEffect(Unit) {
        repeat(10) {
            hasA11y = AccessibilityPermissionHelper.isAccessibilityServiceEnabled(context)
            hasOverlay = Settings.canDrawOverlays(context)
            delay(800)
        }
        hasA11y = AccessibilityPermissionHelper.isAccessibilityServiceEnabled(context)
        hasOverlay = Settings.canDrawOverlays(context)
    }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("權限狀態", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("無障礙服務")
                Text(if (hasA11y) "已開啟" else "未開啟",
                    color = if (hasA11y) Color(0xFF4CAF50) else Color(0xFFD32F2F))
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("懸浮視窗（覆蓋權限）")
                Text(if (hasOverlay) "已允許" else "未允許",
                    color = if (hasOverlay) Color(0xFF4CAF50) else Color(0xFFD32F2F))
            }
        }
    }
}

@Composable
fun AccessibilityQuickSetupButton(context: Context) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }, modifier = Modifier.weight(1f)
        ) { Text("開啟無障礙") }

        OutlinedButton(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }, modifier = Modifier.weight(1f)
        ) { Text("允許懸浮窗") }
    }
}

@Composable
fun GroupDisplayItem(groupName: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onEdit() }, shape = RoundedCornerShape(12.dp)) {
        Row(
            Modifier.fillMaxWidth().background(Color.White).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(groupName, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "編輯", tint = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "刪除", tint = MaterialTheme.colorScheme.error) }
        }
    }
}
