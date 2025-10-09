// 檔名：MentionLauncher.kt
// 功能：啟動工具與「舊版備援服務」(已改名，避免與新版撞名)。

package com.scancode.myapp

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.*
import kotlinx.coroutines.*

object MentionLauncher {
    fun startGroupSelection(context: Context) {
        context.startService(Intent(context, FloatingMentionService::class.java).putExtra("mode", "group_selection"))
    }
}

/** 舊版備援：如需測試才啟用；預設不使用。 */
class FloatingMentionBackupService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatView: View
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val tv = TextView(this).apply { text = "Backup Service"; textSize = 18f; setBackgroundColor(Color.WHITE) }
        createFloatingView(tv); return START_NOT_STICKY
    }
    private fun createFloatingView(content: View) {
        floatView = content
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START; p.x = 200; p.y = 400
        windowManager.addView(floatView, p)
    }
    override fun onDestroy() { super.onDestroy(); try { if (::floatView.isInitialized) windowManager.removeView(floatView) } catch (_: Exception) {}; scope.cancel() }
}
