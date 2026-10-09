package dev.krita.jpsub

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        info = TextView(this).apply { textSize = 14f; setPadding(0, 32, 0, 0) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 160, 48, 48)
            addView(Button(this@MainActivity).apply { text = "开始"; setOnClickListener { start() } })
            addView(Button(this@MainActivity).apply {
                text = "停止"
                setOnClickListener { startService(Intent(this@MainActivity, CaptionService::class.java).setAction(CaptionService.ACTION_STOP)) }
            })
            addView(info)
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val missing = ModelFiles(getExternalFilesDir(null)!!).missing()
        info.text = if (missing.isEmpty()) "模型就绪。点开始 → 选择要共享的 App（或整个屏幕）。\n长按字幕结束，上下拖动字幕移动位置。"
        else "缺少模型文件，请按 README 用 adb push：\n" + missing.joinToString("\n") { it.path }
    }

    private fun start() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val ask = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) ask += Manifest.permission.POST_NOTIFICATIONS // optional
            requestPermissions(ask.toTypedArray(), 1)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), 2)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
        else info.text = "需要录音权限才能捕获 App 声音（不会使用麦克风）。"
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 2 && resultCode == RESULT_OK && data != null) {
            CaptionService.start(this, resultCode, data)
            moveTaskToBack(true)
        }
    }
}
