// 檔名：GroupEditorScreen.kt
// 功能：編輯特定群組的成員名單，可新增 / 刪除成員，含刪除前確認

package com.scancode.myapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

@Composable
fun GroupEditorScreen(groupName: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var memberList by remember { mutableStateOf(listOf<String>()) }
    var newMember by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(null as String?) }

    // 初始載入群組成員
    LaunchedEffect(Unit) {
        memberList = MentionGroupStore.loadMembers(context, groupName)
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("👥 編輯群組：$groupName", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newMember,
                onValueChange = { newMember = it },
                label = { Text("新增成員名稱") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    val name = newMember.trim()
                    if (name.isNotEmpty() && !memberList.contains(name)) {
                        scope.launch {
                            MentionGroupStore.addMember(context, groupName, name)
                            memberList = MentionGroupStore.loadMembers(context, groupName)
                            newMember = ""
                        }
                    }
                },
                enabled = newMember.trim().isNotEmpty() && !memberList.contains(newMember.trim())
            ) {
                Icon(Icons.Default.Add, contentDescription = "新增")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(memberList) { member ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(member, style = MaterialTheme.typography.bodyLarge)
                    IconButton(onClick = {
                        showDeleteDialog = member
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "刪除")
                    }
                }
            }
        }
    }

    // 刪除確認對話框
    if (showDeleteDialog != null) {
        val name = showDeleteDialog!!
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("刪除成員") },
            text = { Text("確定要刪除成員「$name」嗎？") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        MentionGroupStore.removeMember(context, groupName, name)
                        memberList = MentionGroupStore.loadMembers(context, groupName)
                        showDeleteDialog = null
                    }
                }) {
                    Text("確認刪除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("取消")
                }
            }
        )
    }
}
