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
 * 浏览器授权期间临时拉起的前台服务：只负责把进程抬离「后台 idle」，不碰任何业务逻辑。
 *
 * 为什么必须有它：SIWC 登录的回环 socket 跑在本进程里。MIUI 在切到外部浏览器后
 * 约 3 秒就把本应用从 adj 200 降到 adj 700（idle），进程被冻结后监听 socket 不再
 * accept，浏览器把授权结果重定向到 127.0.0.1:<port> 只能停在 listen backlog 里没人
 * 应答 —— 表现就是浏览器一直转圈或直接 ERR_CONNECTION_REFUSED。
 *
 * 这与「等待协程被取消」「超时太短」是两个独立问题：
 * - 协程取消 → 已改为进程级 ChatGptPlanLoginRegistry；
 * - 超时太短 → 已改为 15 分钟；
 * - **进程被冻结 → 由本服务解决**（保持可见级 adj）。
 *
 * 生命周期：登录发起时启动，[TIMEOUT_MS] 后自行退出，不常驻。用户可见一条低优先级
 * 通知，点通知可回到主界面（浏览器本身没有能力把后台应用拉回前台）。
 */
class ChatGptPlanLoginWaitService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { stopSelf() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知必须先发：startForegroundService 拉起的服务若不在数秒内 startForeground，
        // 系统会判定超时并直接崩溃（ForegroundServiceDidNotStartInTime）。
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
            .setContentText("在浏览器完成授权后会自动返回；若未返回，点此回到应用。")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL = "dsh-chatgpt-login"
        private const val NOTIFICATION_ID = 4202

        /** 比回环回调窗口（15 分钟）多留一分钟，服务自行退出。 */
        private const val TIMEOUT_MS = 16L * 60 * 1000

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, ChatGptPlanLoginWaitService::class.java)
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ChatGptPlanLoginWaitService::class.java)) }
        }
    }
}
