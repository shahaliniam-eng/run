package com.example.ethsignal

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import kotlin.math.abs

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private var tv: TextView? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val api = KcexApi()
    private var lastSide = Side.NONE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        ServiceCompat.startForeground(
            this, 1, ongoing(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        if (tv == null) addOverlay()
        if (job == null) job = scope.launch { while (isActive) { refresh(); delay(60_000) } }
        return START_STICKY
    }

    private fun addOverlay() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val v = TextView(this).apply {
            text = "ETH ..."
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(24, 16, 24, 16)
            background = GradientDrawable().apply {
                setColor(0xCC101820.toInt()); cornerRadius = 24f
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 250 }

        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = sx + (e.rawX - tx).toInt(); lp.y = sy + (e.rawY - ty).toInt()
                    wm.updateViewLayout(v, lp)
                }
                MotionEvent.ACTION_UP ->
                    if (abs(e.rawX - tx) < 10 && abs(e.rawY - ty) < 10) scope.launch { refresh() }
            }
            true
        }
        wm.addView(v, lp)
        tv = v
    }

    private suspend fun refresh() {
        try {
            val (_, full) = loadAll(api, "60m")
            tv?.text = formatFull(full)
            val strong = full.grade == "قوی" && full.blockedBy == null
            val key = if (strong) full.signal.side else Side.NONE
            if (key != Side.NONE && key != lastSide) notifySignal(full)
            lastSide = key
        } catch (e: Exception) {
            tv?.text = "ETH: خطا در اتصال"
        }
    }

    private fun formatFull(f: Full): String {
        val s = f.signal
        val head = f.blockedBy?.let { "⚠ $it نزدیکه، وارد نشو\n" } ?: ""
        if (s.side == Side.NONE) return "ETH 1h\n$head" + "بدون سیگنال | قیمت: ${"%.2f".format(s.entry)}\n${f.summary}"
        val t = if (s.side == Side.LONG) "خرید (Long)" else "فروش (Short)"
        return "ETH 1h  $t\n$head" + "کیفیت: ${f.grade} (${f.score})\n" +
            "ورود: ${"%.2f".format(s.entry)}\nحد ضرر: ${"%.2f".format(s.stop)}\n" +
            "هدف۱: ${"%.2f".format(s.tp1)}\nهدف۲: ${"%.2f".format(s.tp2)}\n${f.summary}"
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("run", "در حال اجرا", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("sig", "سیگنال", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun ongoing(): Notification =
        NotificationCompat.Builder(this, "run")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("ETH Signal فعال است")
            .setOngoing(true).build()

    private fun notifySignal(f: Full) {
        val s = f.signal
        val t = if (s.side == Side.LONG) "سیگنال قوی خرید ETH" else "سیگنال قوی فروش ETH"
        val n = NotificationCompat.Builder(this, "sig")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(t)
            .setContentText("امتیاز ${f.score} | ورود ${"%.2f".format(s.entry)} | ضرر ${"%.2f".format(s.stop)}")
            .setAutoCancel(true).build()
        getSystemService(NotificationManager::class.java).notify(2, n)
    }

    override fun onDestroy() {
        scope.cancel()
        tv?.let { runCatching { wm.removeView(it) } }
        super.onDestroy()
    }
}
