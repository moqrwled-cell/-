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
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
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
    private var params: WindowManager.LayoutParams? = null

    private var tvEnglish: TextView? = null
    private var tvArabic: TextView? = null
    private var tvLiveStatus: TextView? = null
    private var btnPauseResume: ImageView? = null

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var audioRecordJob: Job? = null
    private var isPaused = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    private val clearSubtitlesRunnable = Runnable {
        tvArabic?.text = "..."
        tvEnglish?.text = ""
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

        // إذا تم منح إذن التقاط الصوت الداخلي (Android 10+)
        if (resultCode != 0 && resultData != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startInternalAudioCapture(resultCode, resultData)
        } else {
            // بديل تلقائي عبر الميكروفون
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
            .setContentText("جاري التقاط وترجمة صوت الفيديو رقمياً وبدون تأخير ⚡")
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

            tvEnglish = overlayView?.findViewById(R.id.tvEnglish)
            tvArabic = overlayView?.findViewById(R.id.tvArabic)
            tvLiveStatus = overlayView?.findViewById(R.id.tvLiveStatus)
            btnPauseResume = overlayView?.findViewById(R.id.btnPauseResume)

            overlayView?.findViewById<ImageView>(R.id.btnClose)?.setOnClickListener {
                stopSelf()
            }

            btnPauseResume?.setOnClickListener {
                if (isPaused) {
                    isPaused = false
                    btnPauseResume?.setImageResource(android.R.drawable.ic_media_pause)
                    tvLiveStatus?.text = "🟢 مترجم الشاشة مباشر ⚡"
                    tvLiveStatus?.setTextColor(0xFF00E676.toInt())
                } else {
                    isPaused = true
                    btnPauseResume?.setImageResource(android.R.drawable.ic_media_play)
                    tvLiveStatus?.text = "⏸️ متوقف مؤقتاً"
                    tvLiveStatus?.setTextColor(0xFFFFC107.toInt())
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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0
                y = 180
            }

            val dragBar = overlayView?.findViewById<View>(R.id.dragBar)
            dragBar?.setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f

                override fun onTouch(v: View?, event: MotionEvent): Boolean {
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

            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
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

            // التقاط صوت النظام الداخلي مباشرة قبل مضخم الصوت (يعمل حتى لو الصوت مكتوم 100%!)
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
            tvLiveStatus?.text = "🟢 صوت داخلي رقمي (حتى مع كتم الصوت) ⚡"
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
            val buffer = ShortArray(800) // 50ms حزم سريعة جداً
            val speechStream = ByteArrayOutputStream()
            var isSpeaking = false
            var silenceCount = 0
            var speechDurationMs = 0

            val groqKey = AppConfig.getGroqKey(this@FloatingSubtitleService)
            // في الصوت الداخلي الحساسية عالية جداً (0.002) لأن الصوت نقي ورقمي بدون تشويش
            val threshold = if (isInternal) 0.002 else 0.012

            while (isActive && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                if (isPaused) {
                    Thread.sleep(80)
                    continue
                }

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

                        // إرسال سريع كل 1.2 ثانية لتكون الترجمة متطابقة لحظياً مع المتكلم
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

                        // صمت 150ms يكفي لإنهاء الجملة فوراً
                        if (silenceCount >= 3) {
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
        if (pcmData.size < 3200) return // أقل من 100ms

        serviceScope.launch {
            // تفريغ صوتي فائق السرعة عبر Groq Large-V3 Turbo (150ms)
            val enText = GroqTranscriber.transcribe(pcmData, apiKey)

            if (enText.length >= 2) {
                val low = enText.lowercase().trim('.', ' ', '!', '?')
                if (low in listOf("thank you", "thanks for watching", "bye", "you")) return@launch

                // ترجمة فورية إلى العربية (~40ms)
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

                    mainHandler.removeCallbacks(clearSubtitlesRunnable)
                    mainHandler.postDelayed(clearSubtitlesRunnable, 7000)
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
