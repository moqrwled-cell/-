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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var btnOverlayPerm: Button
    private lateinit var btnMicPerm: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var cbBilingual: CheckBox

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updatePermissionButtons()
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updatePermissionButtons()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnOverlayPerm = findViewById(R.id.btnOverlayPerm)
        btnMicPerm = findViewById(R.id.btnMicPerm)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        cbBilingual = findViewById(R.id.cbBilingual)

        cbBilingual.isChecked = AppConfig.isBilingual(this)
        cbBilingual.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.setBilingual(this, isChecked)
        }

        btnOverlayPerm.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            }
        }

        btnMicPerm.setOnClickListener {
            val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            micPermissionLauncher.launch(permissions.toTypedArray())
        }

        btnStart.setOnClickListener {
            if (!hasOverlayPermission()) {
                Toast.makeText(this, "الرجاء الضغط على زر منح إذن الظهور فوق الشاشة أولاً", Toast.LENGTH_LONG).show()
                btnOverlayPerm.performClick()
                return@setOnClickListener
            }

            if (!hasMicPermission()) {
                Toast.makeText(this, "الرجاء الضغط على زر منح إذن الميكروفون أولاً", Toast.LENGTH_LONG).show()
                btnMicPerm.performClick()
                return@setOnClickListener
            }

            startFloatingService()
        }

        btnStop.setOnClickListener {
            stopFloatingService()
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionButtons()
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun updatePermissionButtons() {
        if (hasOverlayPermission()) {
            btnOverlayPerm.text = "✅ 1. إذن الظهور فوق الشاشة (ممنوح)"
            btnOverlayPerm.setBackgroundColor(0xFF2E7D32.toInt())
            btnOverlayPerm.isEnabled = false
        } else {
            btnOverlayPerm.text = "⚠️ 1. اضغط لمنح إذن الظهور فوق التطبيقات"
            btnOverlayPerm.setBackgroundColor(0xFFC62828.toInt())
            btnOverlayPerm.isEnabled = true
        }

        if (hasMicPermission()) {
            btnMicPerm.text = "✅ 2. إذن الميكروفون والصوت (ممنوح)"
            btnMicPerm.setBackgroundColor(0xFF2E7D32.toInt())
            btnMicPerm.isEnabled = false
        } else {
            btnMicPerm.text = "⚠️ 2. اضغط لمنح إذن الميكروفون"
            btnMicPerm.setBackgroundColor(0xFFC62828.toInt())
            btnMicPerm.isEnabled = true
        }
    }

    private fun startFloatingService() {
        val intent = Intent(this, FloatingSubtitleService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        btnStart.visibility = View.GONE
        btnStop.visibility = View.VISIBLE
        tvStatus.text = "الحالة: شريط الترجمة يعمل فوق الشاشة بنجاح 🟢"
        tvStatus.setTextColor(0xFF00E676.toInt())

        Toast.makeText(this, "شريط الترجمة ظاهر الآن فوق شاشتك! افتح أي فيديو", Toast.LENGTH_LONG).show()

        // مغادرة التطبيق تلقائياً للشاشة الرئيسية
        moveTaskToBack(true)
    }

    private fun stopFloatingService() {
        val intent = Intent(this, FloatingSubtitleService::class.java)
        stopService(intent)

        btnStart.visibility = View.VISIBLE
        btnStop.visibility = View.GONE
        tvStatus.text = "الحالة: متوقف"
        tvStatus.setTextColor(0xFFFFC107.toInt())
        Toast.makeText(this, "تم إغلاق شريط الترجمة", Toast.LENGTH_SHORT).show()
    }
}
