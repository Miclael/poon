// 檔名：FloatingMentionService.kt
// 功能：懸浮窗（群組選擇、依序標記、輸入首頁訊息）

package com.scancode.myapp

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.*
import android.widget.*
import kotlinx.coroutines.*

class FloatingMentionService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatView: View
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private var currentMode: String = "group_selection"
    private var allGroups: List<String> = emptyList()

    private val TAG = "AutoMention"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentMode = intent?.getStringExtra("mode") ?: "group_selection"
        scope.launch {
            when (currentMode) {
                "single_group" -> {
                    val g = intent?.getStringExtra("groupName") ?: return@launch
                    val members = MentionGroupStore.loadMembers(this@FloatingMentionService, g)
                    showMemberActionWindow(g, members)
                }
                else -> {
                    allGroups = MentionGroupStore.getAllGroups(this@FloatingMentionService)
                    showGroupSelectionWindow()
                }
            }
        }
        return START_STICKY
    }

    // —— 群組清單視窗 ——
    private fun showGroupSelectionWindow() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.WHITE)
        }
        container.addView(TextView(this).apply {
            text = "選擇群組"
            textSize = 18f
            setTextColor(Color.BLACK)
        })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        allGroups.forEach { g ->
            list.addView(Button(this).apply {
                text = g
                setOnClickListener {
                    scope.launch {
                        val members = MentionGroupStore.loadMembers(this@FloatingMentionService, g)
                        try { windowManager.removeView(floatView) } catch (_: Throwable) {}
                        showMemberActionWindow(g, members)
                    }
                }
            })
        }

        val scroll = ScrollView(this).apply { addView(list) }
        container.addView(scroll)

        createFloatingView(container)
    }

    // —— 群組操作視窗（含「輸入訊息（首頁）」） ——
    private fun showMemberActionWindow(group: String, members: List<String>) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.WHITE)
        }
        container.addView(TextView(this).apply {
            text = "群組：$group"
            textSize = 18f
            setTextColor(Color.BLACK)
        })

        // 依序標記
        container.addView(Button(this).apply {
            text = "依序標記所有成員（${members.size}）"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (!AccessibilityPermissionHelper.isAccessibilityServiceEnabled(this@FloatingMentionService)) {
                    AccessibilityPermissionHelper.openAccessibilitySettings(this@FloatingMentionService)
                    return@setOnClickListener
                }
                isEnabled = false; text = "標記中…"
                startSequentialMention(group, members) {
                    isEnabled = true; text = "依序標記所有成員（${members.size}）"
                }
            }
        })

        // ✅ 新按鈕：輸入訊息（首頁）
        container.addView(Button(this).apply {
            text = "輸入訊息（首頁）"
            setBackgroundColor(Color.parseColor("#1976D2"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                scope.launch {
                    Log.d(TAG, "float_ui_click_paste_home")
                    val latest = runCatching { HomeScanMessageStore.getLatest(applicationContext) }.getOrElse { "" }
                    Log.d(TAG, "latest=$latest") // ✅ 新增這行方便檢查
                    if (latest.isBlank()) {
                        Toast.makeText(this@FloatingMentionService, "首頁掃碼訊息為空", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val ok = runCatching { AutoInputService.pasteHomeMessageAwait(latest, newlineFirst = true) }
                        .getOrElse { false }
                    if (ok) Toast.makeText(this@FloatingMentionService, "已貼上首頁訊息", Toast.LENGTH_SHORT).show()
                    else Toast.makeText(this@FloatingMentionService, "找不到輸入框或貼上失敗", Toast.LENGTH_SHORT).show()
                }
            }
        })

        // 成員清單（資訊展示）
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        members.forEach { name ->
            list.addView(TextView(this).apply {
                text = "👤 $name"
                setTextColor(Color.BLACK)
                setBackgroundColor(Color.parseColor("#EEEEEE"))
                setPadding(20, 10, 20, 10)
            })
        }
        container.addView(ScrollView(this).apply { addView(list) })

        container.addView(Button(this).apply {
            text = "關閉"
            setBackgroundColor(Color.RED)
            setTextColor(Color.WHITE)
            setOnClickListener { stopSelf() }
        })

        createFloatingView(container)
    }

    private fun startSequentialMention(group: String, members: List<String>, onDone: () -> Unit) {
        if (members.isEmpty()) { Toast.makeText(this, "沒有成員可標記", Toast.LENGTH_SHORT).show(); onDone(); return }
        scope.launch {
            SmartMentionMemory.recordGroupUsed(this@FloatingMentionService, group)
            AutoInputService.setCurrentGroupName(group)
            var ok = 0; var fail = 0
            for (m in members) {
                val success = AutoInputService.autoMentionUserAwait(m)
                SmartMentionMemory.recordMentionResult(this@FloatingMentionService, group, m, success)
                if (success) ok++ else fail++
                delay(60L)
            }
            AutoInputService.setCurrentGroupName(null)
            Toast.makeText(this@FloatingMentionService, "完成！成功:$ok 失敗:$fail", Toast.LENGTH_LONG).show()
            onDone()
        }
    }

    private fun createFloatingView(content: View) {
        floatView = content
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100; params.y = 300

        floatView.setOnTouchListener(object : View.OnTouchListener {
            private var ix = 0; private var iy = 0; private var tx = 0f; private var ty = 0f
            override fun onTouch(v: View?, e: MotionEvent): Boolean = when (e.action) {
                MotionEvent.ACTION_DOWN -> { ix = params.x; iy = params.y; tx = e.rawX; ty = e.rawY; true }
                MotionEvent.ACTION_MOVE -> { params.x = ix + (e.rawX - tx).toInt(); params.y = iy + (e.rawY - ty).toInt(); windowManager.updateViewLayout(floatView, params); true }
                else -> false
            }
        })
        windowManager.addView(floatView, params)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { if (::floatView.isInitialized) windowManager.removeView(floatView) } catch (_: Exception) {}
        scope.cancel()
    }
}
