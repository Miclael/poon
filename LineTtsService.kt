package com.scancode.myapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.Locale

class LineTtsService : Service(), TextToSpeech.OnInitListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var tts: TextToSpeech

    private val queue = Channel<String>(capacity = Channel.UNLIMITED)

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        startInForeground()
        scope.launch {
            for (msg in queue) {
                speak(msg)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val msg = intent?.getStringExtra(EXTRA_MSG)
        if (!msg.isNullOrBlank()) {
            scope.launch { queue.send(msg) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { tts.stop(); tts.shutdown() }
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.TAIWAN
            tts.setSpeechRate(1.0f)
        }
    }

    private fun speak(text: String) {
        // 可在此做內容過濾/縮短
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, System.currentTimeMillis().toString())
    }

    private fun startInForeground() {
        val channelId = "line_monitor_tts"
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "LINE 監聽播報", NotificationManager.IMPORTANCE_LOW)
            mgr.createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val noti: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("LINE 監聽中")
            .setContentText("語音播報服務已啟動")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        startForeground(1001, noti)
    }

    companion object {
        const val EXTRA_MSG = "msg"

        fun speakNow(context: Context, msg: String) {
            val i = Intent(context, LineTtsService::class.java).apply { putExtra(EXTRA_MSG, msg) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LineTtsService::class.java))
        }
    }
}
