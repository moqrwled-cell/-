package com.livevideotranslator.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var etGroqKey: EditText
    private lateinit var cbBilingual: CheckBox

    private var isRunning = false

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startServices(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "تم رفض إذن التقاط الصوت الداخلي", Toast.LENGTH_SHORT).show()
            updateUiState(false)
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (checkOverlayPermission()) {
            requestMediaProjection()
        } else {
            Toast.makeText(this, "مطلوب إذن الظهور فوق التطبيقات لعرض الترجمة", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        etGroqKey = findViewById(R.id.etGroqKey)
        cbBilingual = findViewById(R.id.cbBilingual)

        // تحميل الإعدادات الحالية والمفتاح
        etGroqKey.setText(AppConfig.getGroqKey(this))
        cbBilingual.isChecked = AppConfig.isBilingual(this)

        cbBilingual.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.setBilingual(this, isChecked)
        }

        btnStart.setOnClickListener {
            val key = etGroqKey.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "الرجاء إدخال مفتاح Groq API", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AppConfig.setGroqKey(this, key)

            if (!checkOverlayPermission()) {
                requestOverlayPermission()
            } else {
                requestMediaProjection()
            }
        }

        btnStop.setOnClickListener {
            stopServices()
        }
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun requestMediaProjection() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startServices(resultCode: Int, resultData: Intent) {
        // 1. تشغيل شريط الترجمة العائم
        val floatingIntent = Intent(this, FloatingSubtitleService::class.java)
        startService(floatingIntent)

        // 2. تشغيل خدمة التقاط الصوت
        val captureIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_START
            putExtra(AudioCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AudioCaptureService.EXTRA_RESULT_DATA, resultData)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(captureIntent)
        } else {
            startService(captureIntent)
        }

        updateUiState(true)
        Toast.makeText(this, "بدأت الترجمة! شغّل أي فيديو وسيظهر الشريط العائم", Toast.LENGTH_LONG).show()
    }

    private fun stopServices() {
        val captureIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_STOP
        }
        startService(captureIntent)

        val floatingIntent = Intent(this, FloatingSubtitleService::class.java)
        stopService(floatingIntent)

        updateUiState(false)
        Toast.makeText(this, "تم إيقاف الترجمة", Toast.LENGTH_SHORT).show()
    }

    private fun updateUiState(running: Boolean) {
        isRunning = running
        if (running) {
            btnStart.visibility = View.GONE
            btnStop.visibility = View.VISIBLE
            tvStatus.text = "الحالة: شريط الترجمة نشط ويستمع للفيديو ⚡"
            tvStatus.setTextColor(0xFF00E676.toInt())
        } else {
            btnStart.visibility = View.VISIBLE
            btnStop.visibility = View.GONE
            tvStatus.text = "الحالة: متوقف"
            tvStatus.setTextColor(0xFFFFC107.toInt())
        }
    }
}
