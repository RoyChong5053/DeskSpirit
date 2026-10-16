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
import com.roychong.deskspirit.config.ApiConfig
import com.roychong.deskspirit.conversation.ConversationController
import com.roychong.deskspirit.render.SphereView
import com.roychong.deskspirit.settings.SettingsActivity

/**
 * v0.1 主界面：上半屏音频驱动 3D 圆球，下半屏文字输出。
 *
 * 常驻监听：说完一句话自动走 ASR → LLM → TTS 并出声（播放时自动关麦）。
 * 轻触屏幕 = 暂停/继续监听。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var sphere: SphereView
    private lateinit var tvStatus: TextView
    private lateinit var tvUser: TextView
    private lateinit var tvReply: TextView

    private var controller: ConversationController? = null

    /**
     * 「思考中」的秒数走动。TavernLab 要先做 RAG 检索 + 上下文组装才回响应，
     * embedding/rerank 落在 CPU channel 时可能要几十秒。把秒数显示出来，
     * 用户能看出它还在干活，而不是卡死了。
     */
    private val ticker = Handler(Looper.getMainLooper())
    private var thinkingSince = 0L
    private val tick = object : Runnable {
        override fun run() {
            val s = (System.currentTimeMillis() - thinkingSince) / 1000
            tvStatus.text = getString(R.string.status_thinking_fmt, s)
            ticker.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        sphere = findViewById(R.id.sphere)
        tvStatus = findViewById(R.id.tvStatus)
        tvUser = findViewById(R.id.tvUser)
        tvReply = findViewById(R.id.tvReply)

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        sphere.setOnClickListener { controller?.togglePause() }

        ensureMicPermission()
    }

    private fun ensureMicPermission() {
        if (hasMic()) {
            startController()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC
            )
        }
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startController()
            } else {
                Toast.makeText(this, "没有麦克风权限，无法监听", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startController() {
        if (controller != null || !hasMic()) return
        val c = ConversationController(
            context = this,
            config = ApiConfig(this),
            onState = { renderState(it) },
            onBands = { b -> sphere.pushAudio(b.level, b.bass, b.mid, b.treble) },
            onUserText = { text ->
                tvUser.text = "你：$text"
                tvReply.text = ""
            },
            onAiText = { text -> tvReply.text = text },
            onError = { msg -> tvReply.text = "⚠️ $msg" }
        )
        c.start()
        controller = c
    }

    private fun renderState(state: ConversationController.State) {
        ticker.removeCallbacks(tick)
        tvStatus.text = getString(
            when (state) {
                ConversationController.State.LISTENING -> R.string.status_listening
                ConversationController.State.PAUSED -> R.string.status_paused
                ConversationController.State.RECOGNIZING -> R.string.status_recognizing
                ConversationController.State.THINKING -> R.string.status_thinking
                ConversationController.State.SPEAKING -> R.string.status_speaking
            }
        )
        when (state) {
            ConversationController.State.RECOGNIZING -> {
                tvReply.text = ""
                tvUser.text = ""
            }
            ConversationController.State.THINKING -> {
                thinkingSince = System.currentTimeMillis()
                ticker.post(tick)
            }
            else -> Unit
        }
    }

    override fun onResume() {
        super.onResume()
        sphere.onResume()
        startController()
    }

    override fun onPause() {
        ticker.removeCallbacks(tick)
        sphere.onPause()
        controller?.release()
        controller = null
        super.onPause()
    }

    companion object {
        private const val REQ_MIC = 1001
    }
}
