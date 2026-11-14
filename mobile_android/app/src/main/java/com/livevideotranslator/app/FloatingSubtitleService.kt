package com.livevideotranslator.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var audioRecordJob: Job? = null
    private var isPaused = false

    private val clearSubtitlesRunnable = Runnable {
        tvArabic?.text = "..."
        tvEnglish?.text = ""
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        setupOverlayView()
        startAudioRecordingLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
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
            .setContentText("شريط الترجمة يعمل فوق الشاشة ويترجم الفيديوهات مباشرة")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
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
                y = 200 // يظهر في موضع علوي واضح ومرئي للعين مباشرة
            }

            // سحب الشريط وتحريكه في أي مكان على الشاشة باللمس
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

    private fun startAudioRecordingLoop() {
        audioRecordJob = serviceScope.launch {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = maxOf(minBufferSize, 3200)

            var recorder: AudioRecord? = null
            try {
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
                recorder.startRecording()
            } catch (e: Exception) {
                e.printStackTrace()
                return@launch
            }

            val buffer = ShortArray(1600) // 100ms
            val speechStream = ByteArrayOutputStream()
            var isSpeaking = false
            var silenceCount = 0
            var speechDurationMs = 0

            val groqKey = AppConfig.getGroqKey(this@FloatingSubtitleService)

            while (isActive && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                if (isPaused) {
                    Thread.sleep(100)
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

                    // عتبة التقاط الصوت (RMS > 0.012)
                    if (rms > 0.012) {
                        if (!isSpeaking) {
                            isSpeaking = true
                            speechStream.reset()
                            speechDurationMs = 0
                        }
                        speechStream.write(byteChunk)
                        speechDurationMs += 100
                        silenceCount = 0

                        // إرسال سريع كل 1.5 ثانية لتصفير التأخير
                        if (speechDurationMs >= 1500) {
                            val audioBytes = speechStream.toByteArray()
                            processSpeechChunk(audioBytes, groqKey)
                            speechStream.reset()
                            speechDurationMs = 0
                        }
                    } else if (isSpeaking) {
                        speechStream.write(byteChunk)
                        silenceCount++
                        speechDurationMs += 100

                        if (silenceCount >= 3) { // هدوء 300ms يعني نهاية جملة
                            if (speechDurationMs >= 350) {
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

            try {
                recorder.stop()
                recorder.release()
            } catch (e: Exception) {}
        }
    }

    private fun processSpeechChunk(pcmData: ByteArray, apiKey: String) {
        if (pcmData.size < 4000) return

        serviceScope.launch {
            // 1. تفريغ الصوت الإنجليزي في ~150ms بمحرك Groq Turbo
            val enText = GroqTranscriber.transcribe(pcmData, apiKey)

            if (enText.length >= 2) {
                val low = enText.lowercase().trim('.', ' ', '!', '?')
                if (low in listOf("thank you", "thanks for watching", "bye", "you")) return@launch

                // 2. ترجمة فورية إلى العربية
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

        if (overlayView != null && windowManager != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) {}
        }
    }
}
