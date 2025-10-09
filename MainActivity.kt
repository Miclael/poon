package com.scancode.myapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.navigation.compose.rememberNavController
import com.scancode.myapp.ui.theme.ScancodeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ 第一次啟動時，跳轉至通知存取權限設定
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val hasOpenedBefore = prefs.getBoolean("hasOpenedBefore", false)

        if (!hasOpenedBefore) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            prefs.edit().putBoolean("hasOpenedBefore", true).apply()
        }

        setContent {
            ScancodeTheme {
                Surface {
                    // ✅ 回到主畫面（含底部導覽列）
                    val navController = rememberNavController()
                    AppScaffold(navController = navController)
                }
            }
        }
    }
}
