package com.gpt.hook.api

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * 授权期间临时拉起的前台服务：只为把进程抬离后台 idle，不碰业务逻辑。
 *
 * 回环回调端口跑在本进程里。MIUI 在切到外部浏览器后约 3 秒就把本应用从 adj 200
 * 降到 adj 700（idle），进程被冻结后 socket 不再 accept，浏览器把授权结果重定向到
 * 127.0.0.1:<port> 就只能堆在 listen backlog 里没人应答 —— 表现是浏览器一直转圈。
 */
class ChatGptLoginWaitService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { stopSelf() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, TIMEOUT_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "ChatGPT 授权", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, com.gpt.hook.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("等待 ChatGPT 授权")
            .setContentText("在浏览器完成授权后会自动返回。")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL = "dsh-chatgpt-login"
        private const val NOTIFICATION_ID = 4202
        private const val TIMEOUT_MS = 16L * 60 * 1000

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, ChatGptLoginWaitService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ChatGptLoginWaitService::class.java)) }
        }
    }
}
