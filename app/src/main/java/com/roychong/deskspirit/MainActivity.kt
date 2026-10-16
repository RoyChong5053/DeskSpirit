package com.roychong.deskspirit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.roychong.deskspirit.audio.AudioAnalyzer
import com.roychong.deskspirit.config.ApiConfig
import com.roychong.deskspirit.render.SphereView
import com.roychong.deskspirit.settings.SettingsActivity

/**
 * v0.1 主界面：上半屏音频驱动 3D 圆球，下半屏文字输出。
 *
 * 当前只把"看得见的部分"跑通：本地麦克风 → 频谱 → 球体波动 + 文字区。
 * 对话链路（ASR/LLM/TTS）留了接口位，等 API 定下来再接。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var sphere: SphereView
    private lateinit var tvStatus: TextView
    private lateinit var tvReply: TextView

    private var analyzer: AudioAnalyzer? = null
    private val ui = Handler(Looper.getMainLooper())

    private var demoRunning = false
    private var voiceActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        sphere = findViewById(R.id.sphere)
        tvStatus = findViewById(R.id.tvStatus)
        tvReply = findViewById(R.id.tvReply)

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        sphere.setOnClickListener { onScreenTapped() }

        ensureMicPermission()
    }

    /** 演示用对话流程：让文字区动起来。真正的 ASR/LLM/TTS 接进来后替换这里。 */
    private fun onScreenTapped() {
        if (demoRunning) return
        demoRunning = true
        tvStatus.text = getString(R.string.status_listening)
        ui.postDelayed({
            if (demoRunning) tvStatus.text = getString(R.string.status_thinking)
        }, 1200)
        ui.postDelayed({
            val cfg = ApiConfig(this)
            if (cfg.llm().configured || cfg.asr().configured || cfg.tts().configured) {
                tvReply.text = "（示例）API 已配置。对话链路待接入——下一步就是把它接上。"
            } else {
                tvReply.text = "（示例）你好，我是 DeskSpirit。先点右上角 ⚙ 配置 API。"
            }
            tvStatus.text = getString(R.string.status_idle)
            demoRunning = false
        }, 2600)
    }

    private fun ensureMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startAudio()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startAudio()
            } else {
                Toast.makeText(this, "没有麦克风权限，圆球不会随声音波动", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startAudio() {
        if (analyzer != null) return
        val a = AudioAnalyzer { frame ->
            sphere.pushAudio(frame.level, frame.bass, frame.mid, frame.treble)
            if (frame.voice != voiceActive) {
                voiceActive = frame.voice
                ui.post {
                    if (demoRunning) return@post
                    tvStatus.text = getString(
                        if (voiceActive) R.string.status_listening else R.string.status_idle
                    )
                }
            }
        }
        if (a.start()) analyzer = a
    }

    override fun onResume() {
        super.onResume()
        sphere.onResume()
        startAudio()
    }

    override fun onPause() {
        sphere.onPause()
        analyzer?.stop()
        analyzer = null
        super.onPause()
    }

    companion object {
        private const val REQ_MIC = 1001
    }
}
