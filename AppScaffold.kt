// 檔名：AppScaffold.kt
// 功能：底部導覽與各頁路由（含「標記」頁與群組編輯頁）

package com.scancode.myapp

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable

@Composable
fun AppScaffold(navController: NavHostController) {
    val items = listOf("home", "record", "photoCompressor", "lineMonitor", "mention")
    var selectedItem by remember { mutableStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                items.forEachIndexed { index, screen ->
                    NavigationBarItem(
                        icon = {
                            when (screen) {
                                "home" -> Icon(Icons.Default.Home, contentDescription = "首頁")
                                "record" -> Icon(Icons.Default.List, contentDescription = "記錄")
                                "photoCompressor" -> Icon(Icons.Default.Send, contentDescription = "傳照")
                                "lineMonitor" -> Icon(Icons.Default.Hearing, contentDescription = "監聽")
                                "mention" -> Icon(Icons.Default.Person, contentDescription = "標記")
                                else -> Icon(Icons.Default.Home, contentDescription = "")
                            }
                        },
                        label = {
                            when (screen) {
                                "home" -> Text("首頁")
                                "record" -> Text("記錄")
                                "photoCompressor" -> Text("傳照")
                                "lineMonitor" -> Text("監聽")
                                "mention" -> Text("標記")
                                else -> Text("")
                            }
                        },
                        selected = selectedItem == index,
                        onClick = {
                            selectedItem = index
                            navController.navigate(screen) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("home") { ScanDefectScreen() }
            composable("record") { RecordScreen() }
            composable("photoCompressor") { PhotoCompressorScreen() }
            composable("lineMonitor") { LineMonitorScreen() }
            composable("mention") { MentionScreen(navController) }
            composable("editGroup/{groupName}") { backStackEntry ->
                val groupName = backStackEntry.arguments?.getString("groupName") ?: ""
                GroupEditorScreen(groupName)
            }
        }
    }
}
