// *** THIS FILE IS MERGED. EDIT ORIGINAL SOURCES INSTEAD. ***
package com.scancode.myapp.core

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.util.Log
import android.widget.Toast

@file:Suppress("unused", "MemberVisibilityCanBePrivate", "SpellCheckingInspection")

// ====== BEGIN MERGED CONTENT ======


// ======================================================================
// File: AutoInputService.kt
// ======================================================================
// 檔名：AutoMentionLogger.kt
// 功能：自動標記Log骨架

object AutoMentionLogger {
    private const val TAG = "AutoMention"

    data class Fields(
        val phase: String,
        val action: String,
        val result: String,
        val msg: String? = null
    )

    fun d(f: Fields) {
        Log.d(TAG, "[${f.phase}] ${f.action}=${f.result} ${f.msg ?: ""}")
    }

    fun fmsState(state: String, msg: String? = null) {
        d(Fields(phase = state, action = "ui_state", result = "enter", msg = msg))
    }
}


// ======================================================================
// File: AutoMentionLogger.kt
// ======================================================================
// ----------------------------
// app/src/main/java/com/scancode/myapp/core/AutoMentionLogger.kt
// ----------------------------

object L {
    const val TAG = "AutoMention"
    private var seq: Int = 0


    private fun nextSeq(): Int = synchronized(this) { ++seq }


    fun i(key: String, msg: String, withSeq: Boolean = true) {
        val s = if (withSeq) nextSeq() else 0
        val text = if (withSeq) "[seq=$s] $msg" else msg
        Log.i(TAG, "[$key] $text")
    }


    fun e(key: String, msg: String, tr: Throwable? = null, withSeq: Boolean = true) {
        val s = if (withSeq) nextSeq() else 0
        val text = if (withSeq) "[seq=$s] $msg" else msg
        Log.e(TAG, "[$key] $text", tr)
    }
}


// ======================================================================
// File: FloatingMentionService.kt
// ======================================================================
// 檔名：FloatingMentionService.kt
// 功能：懸浮視窗狀態機骨架




class FloatingMentionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AutoMentionLogger.fmsState("Waiting", "懸浮視窗啟動")
        Toast.makeText(this, "FloatingMention 啟動", Toast.LENGTH_SHORT).show()
        return START_STICKY
    }
}


// ======================================================================
// File: MentionLauncher.kt
// ======================================================================
// 檔名：MentionLauncher.kt
// 功能：自動標記啟動骨架



class MentionLauncher private constructor() {
    companion object {
        fun start(
            context: Context,
            chatGroupName: String,
            mentionGroupName: String,
            messageText: String,
            imageUris: List<Uri>
        ) {
            AutoMentionLogger.d(
                AutoMentionLogger.Fields(
                    phase = "LAUNCH",
                    action = "start",
                    result = "begin",
                    msg = "chat=$chatGroupName group=$mentionGroupName"
                )
            )

            // 啟動懸浮視窗骨架
            val svc = Intent(context, FloatingMentionService::class.java).apply {
                putExtra("mode", "skeleton")
            }
            context.startService(svc)
        }
    }
}

// ====== END MERGED CONTENT ======