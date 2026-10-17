package com.roychong.deskspirit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.roychong.deskspirit.audio.AudioPlayer
import com.roychong.deskspirit.config.ApiConfig
import com.roychong.deskspirit.config.AudioSettings
import com.roychong.deskspirit.conversation.DictationController
import com.roychong.deskspirit.conversation.TextRefiner
import com.roychong.deskspirit.net.TtsClient
import com.roychong.deskspirit.voice.VoiceEntry
import com.roychong.deskspirit.voice.VoiceKind
import com.roychong.deskspirit.voice.VoiceStore
import kotlin.concurrent.thread

/**
 * 口述页（2-pass / 3-pass）：
 *
 *   进入即开始长时间聆听 → 点停止 → 整段 ASR → 文本可编辑 →
 *   可「整理」（发给工具 LLM）、可「朗读」、可「留档」、可「发送给伙伴」。
 */
class DictationActivity : AppCompatActivity() {

    private lateinit var config: ApiConfig
    private lateinit var local: AudioSettings
    private lateinit var store: VoiceStore
    private lateinit var player: AudioPlayer
    private var controller: DictationController? = null

    private lateinit var tvTimer: TextView
    private lateinit var tvHint: TextView
    private lateinit var recordPanel: LinearLayout
    private lateinit var reviewPanel: LinearLayout
    private lateinit var recognizingPanel: LinearLayout
    private lateinit var tvRecognizing: TextView
    private lateinit var tvRecog: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var etText: EditText
    private lateinit var tvStatus: TextView
    private lateinit var pbInline: ProgressBar
    private lateinit var btnUndo: Button

    private var entry: VoiceEntry? = null
    private var beforeRefine: String? = null

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            tvTimer.text = fmt(controller?.elapsedMs() ?: 0)
            ticker.postDelayed(this, 200)
        }
    }

    /** 识别 / 整理 / 合成 共用的「转圈 + 秒数」：0=不在忙，1=识别，2=整理，3=合成。 */
    private var busyMode = 0
    private var busySince = 0L
    private var busyPct = -1
    private val busyTick = object : Runnable {
        override fun run() {
            if (busyMode == 0) return
            val s = ((System.currentTimeMillis() - busySince) / 1000).toInt()
            when (busyMode) {
                1 -> tvRecognizing.text = if (busyPct in 0..99) {
                    getString(R.string.dict_uploading_fmt, s, busyPct)
                } else {
                    getString(R.string.dict_recognizing_fmt, s)
                }
                2 -> tvStatus.text = getString(R.string.dict_refining_fmt, s)
                3 -> tvStatus.text = getString(R.string.dict_synthesizing_fmt, s)
            }
            ticker.postDelayed(this, 500)
        }
    }

    private fun startRecognizing() {
        busyMode = 1
        busyPct = 0
        busySince = System.currentTimeMillis()
        recordPanel.visibility = View.GONE
        recognizingPanel.visibility = View.VISIBLE
        ticker.removeCallbacks(busyTick)
        busyTick.run()
    }

    private fun stopRecognizing() {
        if (busyMode == 1) busyMode = 0
        ticker.removeCallbacks(busyTick)
        recognizingPanel.visibility = View.GONE
    }

    /** 编辑区里的短任务（整理 / 朗读）：小转圈 + 秒数。 */
    private fun startInline(mode: Int) {
        busyMode = mode
        busySince = System.currentTimeMillis()
        pbInline.visibility = View.VISIBLE
        ticker.removeCallbacks(busyTick)
        busyTick.run()
    }

    private fun stopInline() {
        if (busyMode == 2 || busyMode == 3) busyMode = 0
        ticker.removeCallbacks(busyTick)
        pbInline.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dictation)
        config = ApiConfig(this)
        local = AudioSettings(this)
        store = VoiceStore(this)
        player = AudioPlayer(this)

        tvTimer = findViewById(R.id.tvTimer)
        tvHint = findViewById(R.id.tvHint)
        recordPanel = findViewById(R.id.recordPanel)
        reviewPanel = findViewById(R.id.reviewPanel)
        recognizingPanel = findViewById(R.id.recognizingPanel)
        tvRecognizing = findViewById(R.id.tvRecognizing)
        tvRecog = findViewById(R.id.tvRecog)
        levelBar = findViewById(R.id.levelBar)
        etText = findViewById(R.id.etText)
        tvStatus = findViewById(R.id.tvStatus)
        pbInline = findViewById(R.id.pbInline)
        btnUndo = findViewById(R.id.btnUndo)

        findViewById<View>(R.id.btnStop).setOnClickListener { controller?.stop() }
        findViewById<View>(R.id.btnRefine).setOnClickListener { refine() }
        findViewById<View>(R.id.btnUndo).setOnClickListener { undoRefine() }
        findViewById<View>(R.id.btnRead).setOnClickListener { readAloud() }
        findViewById<View>(R.id.btnSave).setOnClickListener { saveText(true) }
        findViewById<View>(R.id.btnRedo).setOnClickListener { restart() }
        findViewById<View>(R.id.btnSend).setOnClickListener { sendToPartner() }

        if (!hasMic()) {
            Toast.makeText(this, R.string.dict_need_mic, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        startRecording()
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun startRecording() {
        val c = DictationController(
            config = config,
            local = local,
            store = store,
            onState = { st ->
                if (st == DictationController.State.RECOGNIZING) {
                    // 麦克风已经关了，现在是「上传 + 等服务端识别」：整块换成转圈面板，
                    // 秒数和上传百分比一起走，别让录音面板僵在那里像卡死。
                    startRecognizing()
                    tvStatus.text = ""
                } else {
                    stopRecognizing()
                }
            },
            onProgress = { p ->
                if (busyMode == 1) busyPct = (p * 100).toInt().coerceIn(0, 100)
            },
            onBands = { b -> levelBar.progress = (b.level * 100).toInt().coerceIn(0, 100) },
            onText = { text, saved ->
                entry = saved
                showReview(text)
            },
            onError = { msg ->
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                // 识别失败也要回到可操作界面：切到编辑区（可重录/返回），别卡在录音面板
                if (recordPanel.visibility == View.VISIBLE) {
                    showReview("")
                }
                tvStatus.text = msg
            }
        )
        if (c.start()) {
            controller = c
            recordPanel.visibility = View.VISIBLE
            reviewPanel.visibility = View.GONE
            tvHint.text = getString(R.string.dict_hint_recording)
            ticker.post(tick)
        } else {
            finish()
        }
    }

    private fun showReview(text: String) {
        ticker.removeCallbacks(tick)
        stopRecognizing()
        recordPanel.visibility = View.GONE
        reviewPanel.visibility = View.VISIBLE
        tvRecog.visibility = View.GONE
        tvHint.text = getString(R.string.dict_hint_review)
        etText.setText(text)
        etText.setSelection(text.length)
        btnUndo.isEnabled = false
        beforeRefine = null
        tvStatus.text = if (entry?.hasAudio == true) getString(R.string.dict_saved_rec) else ""
    }

    private fun currentText() = etText.text.toString().trim()

    private fun refine() {
        val text = currentText()
        if (text.isBlank()) return
        val tool = config.toolLlm()
        if (!tool.configured) {
            tvStatus.text = getString(R.string.dict_need_tool_llm)
            return
        }
        tvStatus.text = getString(R.string.dict_refining)
        startInline(2)
        thread {
            try {
                val out = TextRefiner.refine(tool, local.refinePrompt(), text)
                runOnUiThread {
                    stopInline()
                    beforeRefine = text
                    etText.setText(out)
                    etText.setSelection(out.length)
                    btnUndo.isEnabled = true
                    tvStatus.text = getString(R.string.dict_refined)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    stopInline()
                    tvStatus.text = getString(R.string.dict_refine_failed, e.message ?: "")
                }
            }
        }
    }

    private fun undoRefine() {
        val prev = beforeRefine ?: return
        etText.setText(prev)
        btnUndo.isEnabled = false
        beforeRefine = null
        tvStatus.text = ""
    }

    private fun readAloud() {
        val text = currentText()
        if (text.isBlank()) return
        val tts = config.tts()
        if (!tts.configured) {
            tvStatus.text = getString(R.string.dict_need_tts)
            return
        }
        tvStatus.text = getString(R.string.dict_synthesizing)
        startInline(3)
        thread {
            try {
                val audio = TtsClient.synthesize(
                    tts.baseUrl, tts.apiKey, tts.model, tts.voice, text,
                    readTimeoutMs = local.ttsTimeoutSec() * 1000,
                )
                if (local.saveVoice()) {
                    store.save(
                        audio.bytes, audio.contentType, text, tts.model, tts.voice,
                        tts.name, VoiceKind.AI
                    )
                }
                runOnUiThread {
                    stopInline()
                    tvStatus.text = ""
                    player.play(audio.contentType, audio.bytes) {}
                }
            } catch (e: Exception) {
                runOnUiThread {
                    stopInline()
                    tvStatus.text = getString(R.string.dict_read_failed, e.message ?: "")
                }
            }
        }
    }

    private fun saveText(showToast: Boolean) {
        val text = currentText()
        if (text.isBlank()) return
        var e = entry
        if (e == null) {
            e = store.save(
                bytes = null, contentType = "", text = text,
                provider = config.asr().name, kind = VoiceKind.DICTATE
            )
            entry = e
        } else {
            store.updateText(e, text)
        }
        if (showToast) Toast.makeText(this, R.string.dict_saved, Toast.LENGTH_SHORT).show()
    }

    private fun restart() {
        entry = null
        beforeRefine = null
        tvStatus.text = ""
        controller?.release()
        controller = null
        startRecording()
    }

    private fun sendToPartner() {
        val text = currentText()
        if (text.isBlank()) return
        saveText(false)
        val data = Intent().putExtra(EXTRA_TEXT, text)
        setResult(RESULT_OK, data)
        finish()
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return "%02d:%02d".format(s / 60, s % 60)
    }

    override fun onDestroy() {
        ticker.removeCallbacks(tick)
        player.stop()
        controller?.release()
        controller = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TEXT = "text"
    }
}
