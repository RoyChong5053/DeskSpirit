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
import com.roychong.deskspirit.config.AudioSettings
import com.roychong.deskspirit.config.LlmMode
import com.roychong.deskspirit.conversation.ConversationController
import com.roychong.deskspirit.net.TavernLabClient
import com.roychong.deskspirit.render.SphereStyle
import com.roychong.deskspirit.render.SphereView
import com.roychong.deskspirit.settings.SettingsActivity
import com.roychong.deskspirit.voice.VoiceStore
import kotlin.concurrent.thread

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
    private lateinit var pbBusy: android.widget.ProgressBar
    private lateinit var tvUser: TextView
    private lateinit var tvReply: TextView
    private lateinit var replyScroll: ScrollView
    private lateinit var btnReplay: android.widget.Button
    private lateinit var btnLive: android.widget.Button

    private var controller: ConversationController? = null

    private lateinit var config: ApiConfig
    private lateinit var local: AudioSettings

    /** 从口述页带回、等待发送给伙伴的文本（onActivityResult 早于 onResume）。 */
    private var pendingSendText: String? = null

    // ---- KTV 字幕 ----
    private var speechText = ""
    /** 每个字累计的「占用时长权重」，用来把播放进度映射成字符下标。 */
    private var weightCum = FloatArray(0)
    private var lastHighlight = -1
    private var dimColor = 0
    private var hotColor = 0
    /** 每一片口播文本在 [speechText] 里的字符区间，进度回调按片段号对进来。 */
    private val segRanges = ArrayList<IntRange>()
    /** 原文已经让位给口播文本了：之后的 [showRawText] 一律忽略（见其注释）。 */
    private var speechMode = false

    /**
     * 忙状态的秒数走动。识别（上传 + 服务端推理）常有 5-10 秒，
     * TavernLab 的 RAG 首字可能几十秒 —— 光有静态文案用户分不清是慢还是卡死。
     */
    private val ticker = Handler(Looper.getMainLooper())
    /** 忙状态的计时：哪条文案 + 起始时间 + 可选的上传百分比。 */
    private var busySince = 0L
    private var busyLabel = 0
    private var busyPct = -1

    private val tick = object : Runnable {
        override fun run() {
            if (busyLabel == 0) return
            val s = ((System.currentTimeMillis() - busySince) / 1000).toInt()
            var line = getString(busyLabel, s)
            if (busyPct >= 0) line += " · " + getString(R.string.status_pct_fmt, busyPct)
            tvStatus.text = statusLine(line)
            ticker.postDelayed(this, 500)
        }
    }

    /** 进入忙状态：转圈 + 秒数（+ 上传百分比）。 */
    private fun startBusy(labelRes: Int, pct: Int) {
        busyLabel = labelRes
        busyPct = pct
        busySince = System.currentTimeMillis()
        pbBusy.visibility = View.VISIBLE
        ticker.removeCallbacks(tick)
        tick.run()
    }

    private fun stopBusy() {
        busyLabel = 0
        ticker.removeCallbacks(tick)
        pbBusy.visibility = View.GONE
    }

    /** 状态行后缀：TavernLab 模式下顺带显示当前角色，免得搞不清在跟谁说话。 */
    private fun statusLine(base: String): String {
        val c = if (config.llmMode() == LlmMode.TAVERNLAB) local.tavernChar() else ""
        return if (c.isBlank()) base else getString(R.string.status_with_char_fmt, base, c)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        config = ApiConfig(this)
        local = AudioSettings(this)

        sphere = findViewById(R.id.sphere)
        sphere.setStyle(SphereStyle.of(local.sphereStyle()))
        tvStatus = findViewById(R.id.tvStatus)
        pbBusy = findViewById(R.id.pbBusy)
        tvUser = findViewById(R.id.tvUser)
        tvReply = findViewById(R.id.tvReply)
        replyScroll = findViewById(R.id.replyScroll)
        btnReplay = findViewById(R.id.btnReplay)
        btnLive = findViewById(R.id.btnLive)

        dimColor = ContextCompat.getColor(this, R.color.text_dim)
        hotColor = ContextCompat.getColor(this, R.color.accent)

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnReplay.setOnClickListener {
            // 播放中 → 停止；其余可播状态下 → 重读
            if (!btnReplay.isEnabled) return@setOnClickListener
            if (controller?.isSpeaking() == true) {
                controller?.stopSpeaking()
                updateControls(ConversationController.State.LISTENING)
            } else if (controller?.replayLast() != true) {
                Toast.makeText(this, getString(R.string.toast_nothing_to_replay), Toast.LENGTH_SHORT).show()
            }
        }
        btnLive.setOnClickListener {
            val on = !local.liveEnabled()
            local.saveLiveEnabled(on)
            controller?.setLive(on)
            updateLiveButton()
        }
        findViewById<View>(R.id.btnDictate).setOnClickListener {
            startActivityForResult(Intent(this, DictationActivity::class.java), REQ_DICTATE)
        }
        sphere.setOnClickListener { controller?.togglePause() }

        updateLiveButton()

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
            config = config,
            local = local,
            store = VoiceStore(this),
            onState = { renderState(it) },
            onAsrProgress = { p ->
                // 上传阶段显示真实百分比；到 100% 后切成「识别中」，秒数继续走
                if (busyLabel == R.string.status_uploading_fmt) {
                    val pct = (p * 100).toInt().coerceIn(0, 100)
                    busyPct = pct
                    if (pct >= 100) {
                        busyLabel = R.string.status_recognizing_fmt
                        busyPct = -1
                    }
                }
            },
            onBands = { b -> sphere.pushAudio(b.level, b.bass, b.mid, b.treble) },
            onUserText = { text ->
                tvUser.text = "你：$text"
                clearReply()
            },
            onAiText = { text -> showRawText(text) },
            onSpeechSegment = { index, text -> showSpeechSegment(index, text) },
            onSpeechProgress = { seg, frac -> renderSegmentProgress(seg, frac) },
            onError = { msg ->
                clearReply()
                tvReply.text = "⚠️ $msg"
            },
            onNotice = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        )
        c.start()
        controller = c

        pendingSendText?.let { t ->
            pendingSendText = null
            c.sendText(t)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DICTATE && resultCode == RESULT_OK) {
            val text = data?.getStringExtra(DictationActivity.EXTRA_TEXT).orEmpty()
            if (text.isNotBlank()) {
                // onActivityResult 早于 onResume，controller 可能还没建，先存起来
                if (controller == null) pendingSendText = text
                else controller?.sendText(text)
            }
        }
    }

    // ---------------- 文字区 ----------------

    private fun clearReply() {
        speechText = ""
        weightCum = FloatArray(0)
        segRanges.clear()
        speechMode = false
        lastHighlight = -1
        tvReply.text = ""
        replyScroll.scrollTo(0, 0)
    }

    /**
     * LLM 流式阶段：显示原文，并跟着往下滚。
     *
     * 逐句播报时，第一片口播文本一到（[showSpeechSegment]）就切到"念出来的那份"，
     * 但 LLM 这时候往往还在吐字 —— 那些 `onAiText` 是**原文**，会把已经切好的
     * 字幕又覆盖回去。所以切过去之后一律忽略原文。
     */
    private fun showRawText(text: String) {
        if (speechMode) return
        speechText = ""
        weightCum = FloatArray(0)
        segRanges.clear()
        lastHighlight = -1
        tvReply.text = text
        replyScroll.post { replyScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /**
     * 定稿了一片要念的文本：**按序追加**到字幕里。
     *
     * 第 0 片意味着"原文让位给口播文本"（清洗后的才是真正占播放时间的那些字），
     * 所以先把之前的原文清掉再从头开始。
     */
    private fun showSpeechSegment(index: Int, text: String) {
        if (index == 0) {
            speechMode = true
            speechText = ""
            weightCum = FloatArray(1)
            segRanges.clear()
            lastHighlight = -1
            replyScroll.scrollTo(0, 0)
        }
        val start = speechText.length
        speechText += text
        segRanges.add(start until speechText.length)
        weightCum = buildWeights(speechText)
        draw()
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

    /**
     * 第 [seg] 片的播放进度 → 全文字幕的高亮位置。
     *
     * 片段内用「按标点加权的字符表」折算成字符下标，片段之间靠累计位置天然对齐。
     * 比拿整篇按比例分准得多，而且后面的片段还没定稿（文本还在长）也不会跑偏。
     */
    private fun renderSegmentProgress(seg: Int, fraction: Float) {
        val r = segRanges.getOrNull(seg) ?: return
        if (r.isEmpty() || weightCum.size != speechText.length + 1) return
        val lo = weightCum[r.first]
        val hi = weightCum[r.last + 1]
        val target = lo + (hi - lo) * fraction.coerceIn(0f, 1f)
        renderHighlightAt(weightIndex(target))
    }

    /** 高亮**只前进不回退**：片段进度是估算的，偶尔会往回跳一点点。 */
    private fun renderHighlightAt(idx: Int) {
        if (idx <= lastHighlight) return
        lastHighlight = idx
        draw()
    }

    private fun draw() {
        val n = speechText.length
        if (n == 0 || weightCum.size != n + 1) return
        val i = lastHighlight.coerceIn(0, n)
        val sp = SpannableString(speechText)
        sp.setSpan(ForegroundColorSpan(dimColor), 0, n, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (i > 0) {
            sp.setSpan(ForegroundColorSpan(hotColor), 0, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tvReply.text = sp
        scrollToOffset(i)
    }

    /** 权重值 → 字符下标（weightCum 单调递增，二分即可）。 */
    private fun weightIndex(target: Float): Int {
        val n = weightCum.size - 1
        if (n <= 0) return 0
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

    /** 最近一次渲染的状态，用来在角色缓存更新后重画状态行。 */
    private var lastState: ConversationController.State? = null

    private fun renderState(state: ConversationController.State) {
        lastState = state
        when (state) {
            // 麦克风已经交出去了，正在上传音频 / 等服务端识别：这段常有 5-10 秒，
            // 必须有秒数和转圈，否则看着像卡死。
            ConversationController.State.RECOGNIZING ->
                startBusy(R.string.status_uploading_fmt, 0)

            // LLM 首字可能要几十秒
            ConversationController.State.THINKING ->
                startBusy(R.string.status_thinking_fmt, -1)

            // Live 弱模型：识别完先让工具 LLM 整理，同样要有秒数
            ConversationController.State.REFINING ->
                startBusy(R.string.status_refining_fmt, -1)

            // TTS 是"整段合成完才返回"，慢模型/长段落可能要几十秒。
            // 这期间还没出声，状态不能写"说话中"。
            ConversationController.State.SYNTHESIZING ->
                startBusy(R.string.status_synthesizing_fmt, -1)

            else -> {
                stopBusy()
                tvStatus.text = statusLine(
                    getString(
                        when (state) {
                            ConversationController.State.LISTENING -> R.string.status_listening
                            ConversationController.State.PAUSED -> R.string.status_paused
                            ConversationController.State.SPEAKING -> R.string.status_speaking
                            ConversationController.State.LIVE_OFF -> R.string.status_live_off
                            else -> R.string.status_listening
                        }
                    )
                )
            }
        }
        updateControls(state)
        updateLiveButton()
        when (state) {
            ConversationController.State.RECOGNIZING -> {
                clearReply()
                tvUser.text = ""
            }
            else -> Unit
        }
    }

    override fun onResume() {
        super.onResume()
        sphere.onResume()
        // 设置页可能刚换了外观 / 角色，回来时重新取一次
        sphere.setStyle(SphereStyle.of(local.sphereStyle()))
        startController()
        controller?.onForeground()
        updateLiveButton()
        updateControls(lastState)
        syncCurrentChar()
    }

    /**
     * 角色是 TavernLab 服务端的全局状态：在网页端换了之后，App 里的缓存会过期，
     * 状态行就会显示错的人名。回前台时对一次，失败就静默保持原样。
     */
    private fun syncCurrentChar() {
        if (config.llmMode() != LlmMode.TAVERNLAB) return
        val ep = config.llm()
        thread {
            val name = runCatching { TavernLabClient.currentChar(ep.baseUrl, ep.apiKey) }
                .getOrNull().orEmpty()
            if (name.isBlank() || name == local.tavernChar()) return@thread
            local.saveTavernChar(name)
            runOnUiThread {
                // THINKING 时状态行每 500ms 自己重画，不用管；其余常见状态重画一次
                when (lastState) {
                    ConversationController.State.LISTENING,
                    ConversationController.State.PAUSED ->
                        lastState?.let { renderState(it) }
                    else -> Unit
                }
            }
        }
    }

    override fun onPause() {
        ticker.removeCallbacks(tick)
        sphere.onPause()
        // 只关麦、不释放：切后台/锁屏时 TTS 继续合成、继续播放。
        controller?.onBackground()
        super.onPause()
    }

    override fun onDestroy() {
        controller?.release()
        controller = null
        super.onDestroy()
    }

    private fun updateControls(state: ConversationController.State?) {
        val speaking = state == ConversationController.State.SPEAKING
        btnReplay.text = getString(if (speaking) R.string.btn_stop else R.string.btn_replay)

        // 这一轮的音频还没准备好（识别/整理/思考/合成）时，重播没有意义：
        // 置灰，避免像以前那样点出一段"上一条"，随后和新合成的声音撞在一起。
        val pending = state == ConversationController.State.RECOGNIZING ||
            state == ConversationController.State.REFINING ||
            state == ConversationController.State.THINKING ||
            state == ConversationController.State.SYNTHESIZING
        btnReplay.isEnabled = !pending
        btnReplay.alpha = if (pending) 0.4f else 1f
    }

    private fun updateLiveButton() {
        val on = local.liveEnabled()
        btnLive.text = getString(if (on) R.string.btn_live_on else R.string.btn_live_off)
        btnLive.alpha = if (on) 1f else 0.55f
    }

    companion object {
        private const val REQ_MIC = 1001
        private const val REQ_DICTATE = 1002
    }
}
