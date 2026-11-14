package com.livevideotranslator.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

class AudioCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundNotification()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

                if (resultCode != 0 && resultData != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
                    startAudioCapture()
                }
            }
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "audio_capture_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "التقاط صوت النظام للترجمة",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("مترجم الفيديوهات الفوري")
            .setContentText("جاري التقاط صوت الفيديو وترجمته تلقائياً")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()

        startForeground(1002, notification)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startAudioCapture() {
        val projection = mediaProjection ?: return

        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBufferSize, 3200)

        audioRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()

        audioRecord?.startRecording()

        captureJob = serviceScope.launch {
            val buffer = ShortArray(1600) // 100ms chunks
            val speechStream = ByteArrayOutputStream()
            var isSpeaking = false
            var silenceCount = 0
            var speechDurationMs = 0

            val groqKey = AppConfig.getGroqKey(this@AudioCaptureService)

            while (isActive && audioRecord != null) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    // حساب مستوى طاقة الصوت RMS لكشف الكلام
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

                    if (rms > 0.015) { // كشف وجود صوت
                        if (!isSpeaking) {
                            isSpeaking = true
                            speechStream.reset()
                            speechDurationMs = 0
                        }
                        speechStream.write(byteChunk)
                        speechDurationMs += 100
                        silenceCount = 0

                        // قطع وإرسال فوري كل 1.8 ثانية لمنع التأخير
                        if (speechDurationMs >= 1800) {
                            val audioBytes = speechStream.toByteArray()
                            processSpeechSegment(audioBytes, groqKey)
                            speechStream.reset()
                            speechDurationMs = 0
                        }
                    } else if (isSpeaking) {
                        speechStream.write(byteChunk)
                        silenceCount++
                        speechDurationMs += 100

                        // وقفة أو صمت لمدة 300ms تعني نهاية الجملة
                        if (silenceCount >= 3) {
                            if (speechDurationMs >= 400) {
                                val audioBytes = speechStream.toByteArray()
                                processSpeechSegment(audioBytes, groqKey)
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

    private fun processSpeechSegment(pcmAudio: ByteArray, groqKey: String) {
        if (pcmAudio.size < 4800) return // أقل من 150ms

        serviceScope.launch {
            // 1. تفريغ الصوت الإنجليزي بسرعة 150ms عبر Groq Turbo
            val enText = GroqTranscriber.transcribe(pcmAudio, groqKey)

            if (enText.length >= 2) {
                val low = enText.lowercase()
                if (low in listOf("thank you", "thanks for watching", "bye")) return@launch

                // 2. ترجمة فورية إلى العربية
                val arText = ArabicTranslator.translate(enText)

                // 3. إرسال الترجمة للشريط العائم فوق الفيديو
                if (arText.isNotEmpty()) {
                    FloatingSubtitleService.update(enText, arText)
                }
            }
        }
    }

    private fun stopCapture() {
        captureJob?.cancel()
        captureJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioRecord = null

        mediaProjection?.stop()
        mediaProjection = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
    }
}
