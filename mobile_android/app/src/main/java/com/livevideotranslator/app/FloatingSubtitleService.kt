package com.livevideotranslator.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat

class FloatingSubtitleService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var tvEnglish: TextView? = null
    private var tvArabic: TextView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val clearRunnable = Runnable {
        tvArabic?.text = ""
        tvEnglish?.text = ""
    }

    companion object {
        var instance: FloatingSubtitleService? = null

        fun update(enText: String, arText: String) {
            instance?.showSubtitles(enText, arText)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundServiceNotification()
        setupOverlayView()
    }

    private fun startForegroundServiceNotification() {
        val channelId = "floating_subtitle_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "شريط الترجمة العائم",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("مترجم الفيديوهات الفوري")
            .setContentText("شريط الترجمة العائم قيد التشغيل")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        startForeground(1001, notification)
    }

    private fun setupOverlayView() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.layout_floating_subtitle, null)

        tvEnglish = overlayView?.findViewById(R.id.tvEnglish)
        tvArabic = overlayView?.findViewById(R.id.tvArabic)

        overlayView?.findViewById<ImageView>(R.id.btnClose)?.setOnClickListener {
            stopSelf()
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 120 // مسافة كافية فوق أزرار التنقل السفلية للهاتف
        }

        // إتاحة سحب وتحريك شريط الترجمة لأي مكان على شاشة الهاتف باللمس
        overlayView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialY = 0
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialY = params?.y ?: 0
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params?.y = initialY - (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(overlayView, params)
                        return true
                    }
                }
                return false
            }
        })

        windowManager?.addView(overlayView, params)
    }

    fun showSubtitles(enText: String, arText: String) {
        mainHandler.post {
            var finalAr = arText
            var finalEn = enText

            // التحقق من أن النص المعروض باللون الأصفر هو العربي دائماً
            if (!ArabicTranslator.containsArabic(finalAr) && ArabicTranslator.containsArabic(finalEn)) {
                finalAr = enText
                finalEn = arText
            }

            if (finalAr.isNotEmpty()) {
                tvArabic?.text = finalAr
            }

            if (AppConfig.isBilingual(this) && finalEn.isNotEmpty()) {
                tvEnglish?.text = finalEn
                tvEnglish?.visibility = View.VISIBLE
            } else {
                tvEnglish?.visibility = View.GONE
            }

            mainHandler.removeCallbacks(clearRunnable)
            mainHandler.postDelayed(clearRunnable, 6000)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        if (overlayView != null && windowManager != null) {
            windowManager?.removeView(overlayView)
        }
    }
}
