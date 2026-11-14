package com.livevideotranslator.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class FloatingSubtitleService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var tvEnglish: TextView? = null
    private var tvArabic: TextView? = null
    private var tvLiveStatus: TextView? = null
    private var btnPauseResume: ImageView? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListeningPaused = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var translationJob: Job? = null

    private val clearSubtitlesRunnable = Runnable {
        tvArabic?.text = "..."
        tvEnglish?.text = ""
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        setupOverlayView()
        initSpeechRecognizer()
    }

    private fun startForegroundNotification() {
        val channelId = "floating_subtitle_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "شريط الترجمة الفورية للشاشة",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("مترجم الفيديوهات الفوري")
            .setContentText("شريط الترجمة نشط ويترجم أي فيديو فوق كافة التطبيقات")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1001, notification)
        }
    }

    private fun setupOverlayView() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.layout_floating_subtitle, null)

        tvEnglish = overlayView?.findViewById(R.id.tvEnglish)
        tvArabic = overlayView?.findViewById(R.id.tvArabic)
        tvLiveStatus = overlayView?.findViewById(R.id.tvLiveStatus)
        btnPauseResume = overlayView?.findViewById(R.id.btnPauseResume)

        // زر إغلاق شريط الترجمة وإنهاء الخدمة
        overlayView?.findViewById<ImageView>(R.id.btnClose)?.setOnClickListener {
            stopSelf()
        }

        // زر إيقاف واستئناف الاستماع مؤقتاً
        btnPauseResume?.setOnClickListener {
            if (isListeningPaused) {
                isListeningPaused = false
                btnPauseResume?.setImageResource(android.R.drawable.ic_media_pause)
                tvLiveStatus?.text = "🟢 مترجم الشاشة مباشر"
                tvLiveStatus?.setTextColor(0xFF00E676.toInt())
                startListening()
            } else {
                isListeningPaused = true
                btnPauseResume?.setImageResource(android.R.drawable.ic_media_play)
                tvLiveStatus?.text = "⏸️ متوقف مؤقتاً"
                tvLiveStatus?.setTextColor(0xFFFFC107.toInt())
                speechRecognizer?.stopListening()
            }
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
            y = 150
        }

        // سحب وتحريك شريط الترجمة باللمس لأي مكان فوق شاشة الهاتف
        val dragBar = overlayView?.findViewById<View>(R.id.dragBar)
        dragBar?.setOnTouchListener(object : View.OnTouchListener {
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

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            tvArabic?.text = "محرك الصوت غير متوفر في هذا الجهاز"
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                // إعادة بدء الاستماع فوراً عند أي خطأ أو هدوء صوتي لمواصلة الترجمة
                if (!isListeningPaused) {
                    mainHandler.postDelayed({
                        startListening()
                    }, 250)
                }
            }

            override fun onResults(results: Bundle?) {
                handleSpeechResults(results)
                if (!isListeningPaused) {
                    startListening()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                handleSpeechResults(partialResults)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        startListening()
    }

    private fun startListening() {
        if (isListeningPaused) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleSpeechResults(bundle: Bundle?) {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (matches != null && matches.isNotEmpty()) {
            val spokenText = matches[0].trim()
            if (spokenText.length >= 2) {
                processAndDisplaySubtitles(spokenText)
            }
        }
    }

    private fun processAndDisplaySubtitles(enText: String) {
        translationJob?.cancel()
        translationJob = serviceScope.launch {
            // ترجمة مجانية فائقة السرعة بدون أي مفاتيح API
            val arText = ArabicTranslator.translate(enText)

            mainHandler.post {
                if (arText.isNotEmpty()) {
                    tvArabic?.text = arText
                }

                if (AppConfig.isBilingual(this@FloatingSubtitleService) && enText.isNotEmpty()) {
                    tvEnglish?.text = enText
                    tvEnglish?.visibility = View.VISIBLE
                } else {
                    tvEnglish?.visibility = View.GONE
                }

                mainHandler.removeCallbacks(clearSubtitlesRunnable)
                mainHandler.postDelayed(clearSubtitlesRunnable, 6000)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isListeningPaused = true
        mainHandler.removeCallbacksAndMessages(null)
        translationJob?.cancel()

        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        speechRecognizer = null

        if (overlayView != null && windowManager != null) {
            windowManager?.removeView(overlayView)
        }
    }
}
