package com.livevideotranslator.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var etGroqKey: EditText
    private lateinit var cbBilingual: CheckBox

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (audioGranted) {
            checkOverlayAndStart()
        } else {
            Toast.makeText(this, "مطلوب إذن الميكروفون للاستماع لصوت الفيديو", Toast.LENGTH_LONG).show()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (checkOverlayPermission()) {
            startTranslationService()
        } else {
            Toast.makeText(this, "مطلوب إذن الظهور فوق التطبيقات لعرض شريط الترجمة", Toast.LENGTH_LONG).show()
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

        etGroqKey.setText(AppConfig.getGroqKey(this))
        cbBilingual.isChecked = AppConfig.isBilingual(this)

        cbBilingual.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.setBilingual(this, isChecked)
        }

        btnStart.setOnClickListener {
            val key = etGroqKey.text.toString().trim()
            AppConfig.setGroqKey(this, key)

            checkPermissionsAndStart()
        }

        btnStop.setOnClickListener {
            stopTranslationService()
        }
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            checkOverlayAndStart()
        }
    }

    private fun checkOverlayAndStart() {
        if (!checkOverlayPermission()) {
            requestOverlayPermission()
        } else {
            startTranslationService()
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

    private fun startTranslationService() {
        val intent = Intent(this, FloatingSubtitleService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        updateUiState(true)
        Toast.makeText(this, "تم تفعيل الترجمة! سيظهر الشريط فوق شاشة الهاتف مباشرة", Toast.LENGTH_LONG).show()

        // الانتقال تلقائياً لخارج التطبيق ليبقى شريط الترجمة فوق يوتيوب أو المتصفح
        moveTaskToBack(true)
    }

    private fun stopTranslationService() {
        val intent = Intent(this, FloatingSubtitleService::class.java)
        stopService(intent)
        updateUiState(false)
        Toast.makeText(this, "تم إيقاف الترجمة", Toast.LENGTH_SHORT).show()
    }

    private fun updateUiState(running: Boolean) {
        if (running) {
            btnStart.visibility = View.GONE
            btnStop.visibility = View.VISIBLE
            tvStatus.text = "الحالة: شريط الترجمة نشط فوق كافة التطبيقات 🟢"
            tvStatus.setTextColor(0xFF00E676.toInt())
        } else {
            btnStart.visibility = View.VISIBLE
            btnStop.visibility = View.GONE
            tvStatus.text = "الحالة: متوقف"
            tvStatus.setTextColor(0xFFFFC107.toInt())
        }
    }
}
