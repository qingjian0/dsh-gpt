package com.gpt.hook.api

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.gpt.hook.DshApplication

/** 承载本地 API 网关的前台服务。 */
class ApiService : Service() {

    private var server: LocalApiServer? = null
    private lateinit var conversations: ConversationRepository

    override fun onCreate() {
        super.onCreate()
        conversations = (application as? DshApplication)?.conversationRepository ?: ConversationRepository(this)
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "本地 API 网关", NotificationManager.IMPORTANCE_LOW)
            )
        }
        startForeground(NOTIFICATION_ID, buildNotification("本地 API 未启动"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        restart()
        return START_STICKY
    }

    fun restart() {
        server?.stop()
        val cfg = RelayConfig.load(this)
        if (!cfg.ready) {
            notify("缺少 access_token / oai-did")
            return
        }
        val s = LocalApiServer(this, cfg, conversations)
        server = s
        notify(if (s.start()) "本地 API http://${if (cfg.lan) "0.0.0.0" else "127.0.0.1"}:${cfg.port}/v1" else "端口 ${cfg.port} 绑定失败")
    }

    private fun notify(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("DSH GPT")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "dsh-local-api"
        private const val NOTIFICATION_ID = 4101

        fun start(ctx: Context) {
            val i = Intent(ctx, ApiService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ApiService::class.java))
        }
    }
}
