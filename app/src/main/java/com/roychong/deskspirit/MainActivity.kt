package com.roychong.deskspirit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ScrollView
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
 *
 * 文字区是一块可滚动的「KTV 字幕」：
 *  - LLM 流式阶段显示原文，自动跟到底部；
 *  - 开始播报时换成**真正要念的文本**（口播清洗后），并按播放进度逐字高亮。
 *    （高亮打在清洗后的文本上、而不是原文上：原文里那些动作描写不占播放时间，
 *      按它来分进度会越走越偏。）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var sphere: SphereView
    private lateinit var tvStatus: TextView
    private lateinit var tvUser: TextView
    private lateinit var tvReply: TextView
    private lateinit var replyScroll: ScrollView

    private var controller: ConversationController? = null

    // ---- KTV 字幕 ----
    private var speechText = ""
    /** 每个字累计的「占用时长权重」，用来把播放进度映射成字符下标。 */
    private var weightCum = FloatArray(0)
    private var lastHighlight = -1
    private var dimColor = 0
    private var hotColor = 0

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
        replyScroll = findViewById(R.id.replyScroll)

        dimColor = ContextCompat.getColor(this, R.color.text_dim)
        hotColor = ContextCompat.getColor(this, R.color.accent)

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
                clearReply()
            },
            onAiText = { text -> showRawText(text) },
            onSpeechText = { text -> showSpeechText(text) },
            onSpeechProgress = { p -> renderHighlight(p) },
            onError = { msg ->
                clearReply()
                tvReply.text = "⚠️ $msg"
            }
        )
        c.start()
        controller = c
    }

    // ---------------- 文字区 ----------------

    private fun clearReply() {
        speechText = ""
        weightCum = FloatArray(0)
        lastHighlight = -1
        tvReply.text = ""
        replyScroll.scrollTo(0, 0)
    }

    /** LLM 流式阶段：显示原文，并跟着往下滚。 */
    private fun showRawText(text: String) {
        speechText = ""
        weightCum = FloatArray(0)
        lastHighlight = -1
        tvReply.text = text
        replyScroll.post { replyScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** 开始播报：换成真正要念的文本，准备逐字高亮。 */
    private fun showSpeechText(text: String) {
        speechText = text
        weightCum = buildWeights(text)
        lastHighlight = -1
        replyScroll.scrollTo(0, 0)
        renderHighlight(0f)
    }

    /**
     * 给每个字一个「占用时长」权重：标点和换行后面会自然停顿，所以多给一点。
     * 比按字数平均分准得多，高亮才不会越走越偏。
     */
    private fun buildWeights(text: String): FloatArray {
        val cum = FloatArray(text.length + 1)
        var acc = 0f
        for (i in text.indices) {
            acc += 1f
            acc += when (text[i]) {
                '。', '！', '？', '!', '?', '\n' -> 3.5f
                '，', '、', '；', '：', ',', ';', ':' -> 1.5f
                else -> 0f
            }
            cum[i + 1] = acc
        }
        return cum
    }

    private fun renderHighlight(progress: Float) {
        val n = speechText.length
        if (n == 0 || weightCum.size != n + 1) return
        val idx = indexForProgress(progress)
        if (idx == lastHighlight) return
        lastHighlight = idx

        val sp = SpannableString(speechText)
        sp.setSpan(ForegroundColorSpan(dimColor), 0, n, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (idx > 0) {
            sp.setSpan(ForegroundColorSpan(hotColor), 0, idx, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tvReply.text = sp
        scrollToOffset(idx)
    }

    private fun indexForProgress(p: Float): Int {
        val n = weightCum.size - 1
        if (n <= 0) return 0
        val target = weightCum[n] * p.coerceIn(0f, 1f)
        var lo = 0
        var hi = n
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (weightCum[mid] < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** 把当前高亮的位置滚到可视区偏上的地方，读起来才跟得上。 */
    private fun scrollToOffset(offset: Int) {
        tvReply.post {
            val layout = tvReply.layout ?: return@post
            val line = layout.getLineForOffset(offset.coerceIn(0, tvReply.text.length))
            val y = layout.getLineTop(line) - replyScroll.height / 3
            replyScroll.scrollTo(0, y.coerceAtLeast(0))
        }
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
                clearReply()
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
