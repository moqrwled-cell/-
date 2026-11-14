package com.livevideotranslator.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

class FloatingSubtitleService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayCard: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var tvEnglish: TextView? = null
    private var tvArabic: TextView? = null

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var audioRecordJob: Job? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    // أحجام الخط للتبديل السريع عند النقر المزدوج (صغير / متوسط / كبير)
    private val fontSizes = floatArrayOf(14f, 17f, 21f)
    private var fontIndex = 1

    // مستويات الشفافية لرؤية الفيديو بوضوح تام (شبه شفاف / عالي الشفافية / داكن)
    private val opacityLevels = floatArrayOf(0.75f, 0.40f, 0.95f)
    private var opacityIndex = 0

    private val autoShrinkRunnable = Runnable {
        tvArabic?.text = "🟢"
        tvEnglish?.visibility = View.GONE
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        setupOverlayView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()

        val resultCode = intent?.getIntExtra("EXTRA_RESULT_CODE", 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("EXTRA_RESULT_DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("EXTRA_RESULT_DATA")
        }

        if (resultCode != 0 && resultData != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startInternalAudioCapture(resultCode, resultData)
        } else {
            startMicrophoneCapture()
        }

        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "floating_subtitle_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "شريط الترجمة الفورية للشاشة",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("مترجم الفيديوهات الفوري")
            .setContentText("شريط الترجمة يعمل فوق الشاشة بنقاء رقمي ⚡")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1001, notification)
        }
    }

    private fun setupOverlayView() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return
        }

        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            overlayView = LayoutInflater.from(this).inflate(R.layout.layout_floating_subtitle, null)

            overlayCard = overlayView?.findViewById(R.id.overlayCard)
            tvEnglish = overlayView?.findViewById(R.id.tvEnglish)
            tvArabic = overlayView?.findViewById(R.id.tvArabic)

            // ضبط الحجم والشفافية الافتراضية
            tvArabic?.textSize = fontSizes[fontIndex]
            overlayCard?.alpha = opacityLevels[opacityIndex]

            overlayView?.findViewById<ImageView>(R.id.btnClose)?.setOnClickListener {
                stopSelf()
            }

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }

            // العرض والارتفاع WRAP_CONTENT ليكون حجم الشريط "عقد الكلام تماماً"!
            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0
                y = 160 // يظهر في الثلث العلوي ويمكن سحبه لأي مكان
            }

            setupGestureAndDrag()
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupGestureAndDrag() {
        // كاشف الإيماءات: نقر مزدوج لتغيير الحجم، ضغط مطول لتغيير الشفافية لرؤية الفيديو بوضوح
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                fontIndex = (fontIndex + 1) % fontSizes.size
                tvArabic?.textSize = fontSizes[fontIndex]
                Toast.makeText(this@FloatingSubtitleService, "تغيير حجم الخط: ${fontSizes[fontIndex].toInt()}sp", Toast.LENGTH_SHORT).show()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                opacityIndex = (opacityIndex + 1) % opacityLevels.size
                overlayCard?.alpha = opacityLevels[opacityIndex]
                val pct = (opacityLevels[opacityIndex] * 100).toInt()
                Toast.makeText(this@FloatingSubtitleService, "شفافية الخلفية: $pct%", Toast.LENGTH_SHORT).show()
            }
        })

        // سحب الشريط وتحريكه في أي مكان على الشاشة باللمس
        overlayView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                gestureDetector.onTouchEvent(event)

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params?.x ?: 0
                        initialY = params?.y ?: 0
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params?.x = initialX + (event.rawX - initialTouchX).toInt()
                        params?.y = initialY + (event.rawY - initialTouchY).toInt()
                        try {
                            windowManager?.updateViewLayout(overlayView, params)
                        } catch (e: Exception) {}
                        return true
                    }
                }
                return false
            }
        })
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startInternalAudioCapture(resultCode: Int, resultData: Intent) {
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    mediaProjection = null
                }
            }, Handler(Looper.getMainLooper()))

            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, 3200))
                .build()

            audioRecord?.startRecording()
            startAudioProcessingLoop(isInternal = true)
        } catch (e: Exception) {
            e.printStackTrace()
            startMicrophoneCapture()
        }
    }

    private fun startMicrophoneCapture() {
        try {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                maxOf(minBuf, 3200)
            )
            audioRecord?.startRecording()
            startAudioProcessingLoop(isInternal = false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startAudioProcessingLoop(isInternal: Boolean) {
        audioRecordJob?.cancel()
        audioRecordJob = serviceScope.launch {
            val recorder = audioRecord ?: return@launch
            val buffer = ShortArray(800) // 50ms
            val speechStream = ByteArrayOutputStream()
            var isSpeaking = false
            var silenceCount = 0
            var speechDurationMs = 0

            val groqKey = AppConfig.getGroqKey(this@FloatingSubtitleService)
            val threshold = if (isInternal) 0.002 else 0.012

            while (isActive && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    var sum = 0.0
                    for (i in 0 until read) {
                        sum += buffer[i] * buffer[i]
                    }
                    val rms = sqrt(sum / read) / 32768.0

                    val byteChunk = ByteArray(read * 2)
                    for (i in 0 until read) {
                        byteChunk[i * 2] = (buffer[i].toInt() and 0xff).toByte()
                        byteChunk[i * 2 + 1] = ((buffer[i].toInt() shr 8) and 0xff).toByte()
                    }

                    if (rms > threshold) {
                        if (!isSpeaking) {
                            isSpeaking = true
                            speechStream.reset()
                            speechDurationMs = 0
                        }
                        speechStream.write(byteChunk)
                        speechDurationMs += 50
                        silenceCount = 0

                        // إرسال سريع كل 1.2 ثانية لتكون الترجمة سريعة جداً
                        if (speechDurationMs >= 1200) {
                            val audioBytes = speechStream.toByteArray()
                            processSpeechChunk(audioBytes, groqKey)
                            speechStream.reset()
                            speechDurationMs = 0
                        }
                    } else if (isSpeaking) {
                        speechStream.write(byteChunk)
                        silenceCount++
                        speechDurationMs += 50

                        if (silenceCount >= 3) { // صمت 150ms ينهي الجملة فوراً
                            if (speechDurationMs >= 300) {
                                val audioBytes = speechStream.toByteArray()
                                processSpeechChunk(audioBytes, groqKey)
                            }
                            isSpeaking = false
                            speechStream.reset()
                            speechDurationMs = 0
                            silenceCount = 0
                        }
                    }
                }
            }
        }
    }

    private fun processSpeechChunk(pcmData: ByteArray, apiKey: String) {
        if (pcmData.size < 3200) return

        serviceScope.launch {
            val enText = GroqTranscriber.transcribe(pcmData, apiKey)

            if (enText.length >= 2) {
                val low = enText.lowercase().trim('.', ' ', '!', '?')
                if (low in listOf("thank you", "thanks for watching", "bye", "you")) return@launch

                val arText = ArabicTranslator.translate(enText)

                mainHandler.post {
                    if (arText.isNotEmpty() && ArabicTranslator.containsArabic(arText)) {
                        tvArabic?.text = arText
                    } else if (enText.isNotEmpty()) {
                        tvArabic?.text = enText
                    }

                    if (AppConfig.isBilingual(this@FloatingSubtitleService) && enText.isNotEmpty()) {
                        tvEnglish?.text = enText
                        tvEnglish?.visibility = View.VISIBLE
                    } else {
                        tvEnglish?.visibility = View.GONE
                    }

                    // إعادة ضبط مؤقت التقليص التلقائي عند انتهاء الكلام
                    mainHandler.removeCallbacks(autoShrinkRunnable)
                    mainHandler.postDelayed(autoShrinkRunnable, 5500)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioRecordJob?.cancel()
        mainHandler.removeCallbacksAndMessages(null)

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {}
        audioRecord = null

        try {
            mediaProjection?.stop()
        } catch (e: Exception) {}
        mediaProjection = null

        if (overlayView != null && windowManager != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) {}
        }
    }
}
