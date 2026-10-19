package com.roychong.deskspirit.conversation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.roychong.deskspirit.audio.AudioPlayer
import com.roychong.deskspirit.audio.SpeechInput
import com.roychong.deskspirit.audio.Spectrum
import com.roychong.deskspirit.audio.Wav
import com.roychong.deskspirit.config.ApiConfig
import com.roychong.deskspirit.config.AudioSettings
import com.roychong.deskspirit.config.Endpoint
import com.roychong.deskspirit.config.LlmMode
import com.roychong.deskspirit.net.AsrClient
import com.roychong.deskspirit.net.LlmClient
import com.roychong.deskspirit.net.TtsClient
import com.roychong.deskspirit.voice.VoiceEntry
import com.roychong.deskspirit.voice.VoiceKind
import com.roychong.deskspirit.voice.VoiceStore
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 一轮对话的编排（轮次互斥）：
 *
 *   常驻听 → VAD 切出一句话 → 关麦 → ASR → LLM(流式) → TTS → 播放 → 开麦
 *
 * 关麦这一步很关键：不然手机喇叭放出来的 AI 声音会被自己听回去。
 *
 * 配置**每轮现读**（`config.llm()/asr()/tts()`），所以在设置里换了 profile 立刻生效。
 * TTS 生成后按设置落进 [VoiceStore]，供重放 / 语音库 / 外部导出。
 *
 * **逐句流式播报**（默认开，见 [AudioSettings.streamSpeech]）：LLM 一边出字、
 * 一遇到句号就切出一片（[SpeechSegmenter]），丢给合成线程按序合成，
 * 合成出来一块就喂给 [AudioPlayer.Speaker] 连续通道播放。
 * 于是「第一句合成完」就能开口，而不是等「整段 LLM + 整段 TTS」——
 * 角色回复 500 字时，开口时间从 60–95 秒降到几秒。
 *
 * 这个优化**只动客户端编排**：切句是本地文本处理，每一片走的还是原来那条
 * `POST /audio/speech`，不依赖任何后端专有特性，所以对任意 OpenAI 兼容 TTS
 * 都成立；关掉开关就退回旧行为。
 *
 * LLM 分两种模式：
 *  - TAVERNLAB：只把这一句话发给 TavernLab，上下文/RAG 记忆全在服务端。
 *  - OPENAI：通用 OpenAI 兼容端点，客户端自己带一小段历史。
 */
class ConversationController(
    context: Context,
    private val config: ApiConfig,
    private val local: AudioSettings,
    private val store: VoiceStore,
    private val onState: (State) -> Unit,
    /** 录音上传进度 0f..1f；1f 表示音频已送达，正在等服务端返回识别结果。 */
    private val onAsrProgress: (Float) -> Unit = {},
    private val onBands: (Spectrum.Bands) -> Unit,
    private val onUserText: (String) -> Unit,
    private val onAiText: (String) -> Unit,
    /**
     * 定稿了一片要念的文本（已口播清洗），给 KTV 字幕**按序追加**。
     * [index] 从 0 连续递增，和 [onSpeechProgress] 的片段号一一对应。
     */
    private val onSpeechSegment: (index: Int, text: String) -> Unit,
    /** 第 [segment] 片的播放进度 0f..1f，用来推进字幕高亮。 */
    private val onSpeechProgress: (segment: Int, fraction: Float) -> Unit,
    private val onError: (String) -> Unit,
    /** 非致命的提示（例如整理失败已回退原文），界面上用 Toast 即可。 */
    private val onNotice: (String) -> Unit = {},
) {

    enum class State { LISTENING, RECOGNIZING, REFINING, THINKING, SYNTHESIZING, SPEAKING, PAUSED, LIVE_OFF }

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val appContext = context.applicationContext
    private val player = AudioPlayer(appContext)

    /** release() 之后置位：被释放的控制器不再受理新话轮，也不能重新开麦。 */
    @Volatile private var released = false

    /** 只在 OPENAI 模式下使用。 */
    private val history = ArrayList<LlmClient.Msg>()
    private var historyMode: LlmMode? = null

    private var input: SpeechInput? = null

    @Volatile private var busy = false
    @Volatile private var paused = false

    /** App 是否在前台。后台只停麦，不打断正在进行的合成/播放。 */
    @Volatile private var foreground = true

    /** 是否正在播放 AI 语音（重播按钮据此变成「停止」）。 */
    @Volatile private var speaking = false

    /** 当前这一轮（合成/播放中的那个）。按「停止」或 release 时要能把它掐掉。 */
    @Volatile private var currentTurn: Turn? = null

    /**
     * 最近一条**真正播放过**的 AI 语音（重放用）。
     *
     * 注意：合成完成但还没播出来的音频不算 —— 否则回前台按重播时，
     * 会和「马上要播的那一条」撞在一起（曾经真的出过两段同时出声）。
     */
    @Volatile private var lastPlayedEntry: VoiceEntry? = null

    /** 未落盘时的内存副本，保证"不存档也能重放"。同样只在真正播放后记录。 */
    @Volatile private var lastPlayedAudio: Pair<String, ByteArray>? = null

    fun start() {
        if (released) return
        paused = false
        if (local.liveEnabled()) startMic() else postState(State.LIVE_OFF)
    }

    /** 回前台：恢复 Live 拾音（Live 关着就只更新状态，不开麦）。 */
    fun onForeground() {
        foreground = true
        if (released) return
        if (!local.liveEnabled()) {
            stopMic()
            if (!busy && !paused) postState(State.LIVE_OFF)
            return
        }
        if (!busy && !paused && input == null) startMic()
    }

    /**
     * 切后台 / 锁屏：**只停麦**。
     * TTS 合成和播放继续（一边刷别的 App 一边听角色说话是正常用法），
     * 只有真正退出（onDestroy → release）才中断。
     */
    fun onBackground() {
        foreground = false
        stopMic()
    }

    /** 主屏 Live 开关。写进 prefs，重启/锁屏后保持。 */
    fun setLive(on: Boolean) {
        local.saveLiveEnabled(on)
        if (on) {
            if (!busy && !paused) startMic()
        } else {
            stopMic()
            if (!busy) postState(State.LIVE_OFF)
        }
    }

    fun isSpeaking(): Boolean = speaking

    /**
     * 播放中按「停止」：停掉连续通道、掐断在途的合成请求，本轮按正常收尾。
     *
     * 三样都要做：只停播放的话，合成线程还会把后面几句算完（白烧 CPU），
     * 而 LLM 那边也还在流字。全部掐掉这一轮才算真的结束。
     */
    fun stopSpeaking(): Boolean {
        if (!speaking) return false
        speaking = false
        currentTurn?.cancel()
        player.stop()
        return true
    }

    /** 一轮结束（正常或被停止）后：按 Live 设置决定是否重新开麦。 */
    private fun afterTurn() {
        if (released || paused) return
        if (!local.liveEnabled()) {
            postState(State.LIVE_OFF)
            return
        }
        if (!foreground) return
        startMic()
    }

    /**
     * 往 worker 里丢一轮活。release() 之后线程池已 shutdown，
     * 此时麦克风线程晚到一步是正常节奏（回桌面 / 锁屏 / 进设置的瞬间切出语音），
     * 直接把这一轮丢掉就行——在这条线程上抛异常会把整个 App 带崩。
     */
    private fun submit(block: () -> Unit) {
        if (released) return
        try {
            worker.execute(block)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            Log.i(TAG, "controller released, utterance dropped")
        }
    }

    fun togglePause() {
        if (busy) return
        // Live 关着的时候没有麦可暂停/恢复，点球不做无效的状态切换
        if (!local.liveEnabled()) return
        if (paused) {
            paused = false
            startMic()
        } else {
            paused = true
            stopMic()
            postState(State.PAUSED)
        }
    }

    fun release() {
        released = true
        stopMic()
        currentTurn?.cancel()
        player.stop()
        worker.shutdownNow()
    }

    val isBusy: Boolean get() = busy

    /** 有没有可以重放的 AI 语音。 */
    fun canReplay(): Boolean = lastPlayedEntry != null || lastPlayedAudio != null

    fun startMic() {
        if (input != null || released) return
        // Live 关闭、或在后台，一律不开麦
        if (!local.liveEnabled() || !foreground) return
        val si = SpeechInput(
            vad = local.vad(),
            onFrame = { bands, _ -> if (!busy) onBands(bands) },
            onUtterance = { pcm -> onUtterance(pcm) }
        )
        if (si.start()) {
            input = si
            postState(State.LISTENING)
        } else {
            post { onError("麦克风启动失败") }
        }
    }

    private fun stopMic() {
        input?.stop()
        input = null
    }

    private fun onUtterance(pcm: ShortArray) {
        if (busy || paused) return
        busy = true
        stopMic()
        submit { runTurn(pcm, null) }
    }

    /** 口述 / 编辑后的文本直接进入对话（跳过 ASR）。 */
    fun sendText(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        busy = true
        stopMic()
        submit { runTurn(null, t) }
    }

    /** 重放最近一条 AI 语音（同样关麦防回声）。 */
    fun replayLast(): Boolean {
        // 优先「上次真正听过的那一条」；内存没有（进程被回收/重启）才退到
        // 语音库里最新一条 AI 语音，保证重播永远有东西可听。
        val entry = lastPlayedEntry?.takeIf { it.hasAudio }
            ?: store.list().firstOrNull { it.kind == VoiceKind.AI && it.hasAudio }
        val bytes: ByteArray
        val contentType: String
        val fromEntry = entry?.let { store.readBytes(it) }
        if (fromEntry != null) {
            bytes = fromEntry
            contentType = entry.contentType
        } else {
            val mem = lastPlayedAudio ?: return false
            contentType = mem.first
            bytes = mem.second
        }
        if (busy) return false
        busy = true
        stopMic()
        submit {
            try {
                speaking = true
                player.play(
                    contentType = contentType,
                    bytes = bytes,
                    onLevel = { onBands(it) },
                    onProgress = { _, _ -> },
                    onDone = {},
                    onStarted = {
                        // 真正出声了才算「听过」的这一条
                        if (entry != null) lastPlayedEntry = entry
                        lastPlayedAudio = contentType to bytes
                        postState(State.SPEAKING)
                    },
                )
            } catch (ex: Exception) {
                Log.e(TAG, "replay failed", ex)
            } finally {
                speaking = false
                busy = false
                post { afterTurn() }
            }
        }
        return true
    }

    // ---------------- 一轮的合成/播放状态 ----------------

    /**
     * 一轮的临时状态。取消（用户按停止 / 被新的一轮顶掉 / 退出）时，
     * 任意线程把 [cancelled] 置位并 [canceller.cancel]，工作线程自己收摊。
     */
    private inner class Turn {
        /** 本轮开始时刻，用来打「多久开口」的时间线。 */
        val t0 = System.currentTimeMillis()
        @Volatile var cancelled = false
        val canceller = TtsClient.Canceller()
        @Volatile var speaker: AudioPlayer.Speaker? = null
        @Volatile var synth: ExecutorService? = null

        /** 0 还没定 / 1 走 PCM 连续通道 / 2 压缩格式整段播。 */
        private var mode = 0
        private var curIndex = -1
        var segBytes = 0
        private val segBuf = ByteArrayOutputStream()
        private val rawAll = ByteArrayOutputStream()
        private val textAll = StringBuilder()

        @Volatile var contentType = ""
        @Volatile var heard = false
        @Volatile var synthStatePosted = false
        var segments = 0
        var emitted = 0

        fun elapsedMs() = System.currentTimeMillis() - t0

        fun beginSegment(index: Int, ct: String, pcm: Boolean) {
            if (cancelled) return
            curIndex = index
            segBytes = 0
            segBuf.reset()
            if (contentType.isEmpty()) contentType = ct
            if (mode != 0) return
            mode = if (pcm) 1 else 2
            if (mode == 1) {
                // PCM：开一条跨片段不释放的连续通道。采样率只能从响应头里知道，
                // 所以通道是"第一片到货时"才建的。
                val rate = AudioPlayer.parseRate(ct) ?: 24000
                speaker = player.speaker(
                    sampleRate = rate,
                    onLevel = { onBands(it) },
                    onStarted = {
                        heard = true
                        speaking = true
                        postState(State.SPEAKING)
                        Log.i(TAG, "首次出声 +${elapsedMs()}ms")
                    },
                    onProgress = { seg, frac -> post { onSpeechProgress(seg, frac) } },
                    onDone = {},
                )
            }
        }

        fun onBlock(b: ByteArray, off: Int, len: Int): Boolean {
            if (cancelled) return false
            rawAll.write(b, off, len)
            segBytes += len
            if (mode == 1) {
                val sp = speaker ?: return !cancelled
                return sp.push(curIndex, b.copyOfRange(off, off + len), false, 0)
            }
            // 压缩格式（mp3/aac/…）没法拼进 PCM 通道，先攒够整段
            segBuf.write(b, off, len)
            return true
        }

        fun endSegment(index: Int, text: String) {
            if (cancelled) return
            textAll.append(text)
            segments++
            if (mode == 2) {
                val bytes = segBuf.toByteArray()
                segBuf.reset()
                if (bytes.isEmpty()) {
                    post { onSpeechProgress(index, 1f) }
                    return
                }
                // 整段丢给 MediaPlayer 播，等它播完再合成下一段
                // （压缩格式段与段之间必然有小间隙，这是回退路径的代价）
                val latch = CountDownLatch(1)
                player.play(
                    contentType = contentType,
                    bytes = bytes,
                    onLevel = { onBands(it) },
                    onProgress = { played, total ->
                        if (total > 0) post { onSpeechProgress(index, played.toFloat() / total) }
                    },
                    onDone = { latch.countDown() },
                    onStarted = {
                        heard = true
                        speaking = true
                        postState(State.SPEAKING)
                        Log.i(TAG, "首次出声 +${elapsedMs()}ms（压缩格式）")
                    },
                )
                while (!cancelled && latch.count > 0L) {
                    runCatching { latch.await(200, TimeUnit.MILLISECONDS) }
                }
                if (!cancelled) post { onSpeechProgress(index, 1f) }
                return
            }
            val sp = speaker
            if (sp == null || segBytes == 0) {
                post { onSpeechProgress(index, 1f) }
                return
            }
            // 零字节的收尾标记：播放线程据此知道这一片段的总长度
            sp.push(index, ByteArray(0), true, segBytes)
        }

        fun texts(): String = textAll.toString()
        fun audioBytes(): ByteArray = rawAll.toByteArray()

        /**
         * Stream mode 关时整段归档：完整音频一次进账。
         * 只喂 rawAll/textAll（存档用），不碰播放状态。
         */
        fun archiveWhole(index: Int, ct: String, bytes: ByteArray, text: String) {
            if (cancelled) return
            if (contentType.isEmpty()) contentType = ct
            rawAll.write(bytes)
            textAll.append(text)
            segments++
            Log.i(TAG, "整段 ${bytes.size} 字节 · $ct")
        }

        /** 等连续通道把缓冲里的声音播完。 */
        fun finishQueue() {
            speaker?.finish()
        }

        fun cancel() {
            cancelled = true
            canceller.cancel()
            speaker?.cancel()
            runCatching { synth?.shutdownNow() }
        }
    }

    /**
     * 合成一片，边收边喂给连续通道。
     * 跑在单线程的合成池上，所以调用顺序就是播放顺序。
     */
    private fun synthesizeSegment(turn: Turn, s: SpeechSegmenter.Segment) {
        if (turn.cancelled) return
        // 文本先给界面：字幕立刻出现，不用等这一片算完
        post { onSpeechSegment(s.index, s.text) }
        val tts = config.tts()
        // Stream mode 关：整段只发一次请求，拿到完整音频再一次性播。
        // 云端快模型（Qwen、OpenAI…）几秒就回，没必要切句，也不该碰连续通道。
        if (!local.streamSpeech()) {
            synthesizeWhole(turn, s, tts)
            return
        }
        val t = System.currentTimeMillis()
        var ok = false
        try {
            TtsClient.synthesizeStreaming(
                base = tts.baseUrl,
                key = tts.apiKey,
                model = tts.model,
                voice = tts.voice,
                text = s.text,
                readTimeoutMs = local.ttsTimeoutSec() * 1000,
                canceller = turn.canceller,
                onHeader = { ct -> turn.beginSegment(s.index, ct, player.isPcm(ct)) },
                onBlock = { b, o, l -> turn.onBlock(b, o, l) },
            )
            ok = true
        } catch (e: Exception) {
            if (!turn.cancelled) {
                // 单片失败不该毁掉整轮：跳过它继续念后面的
                Log.w(TAG, "第 ${s.index + 1} 片合成失败：${e.message}")
                post { onSpeechProgress(s.index, 1f) }
            }
        }
        if (ok) {
            turn.endSegment(s.index, s.text)
            Log.i(
                TAG,
                "第 ${s.index + 1} 片 ${System.currentTimeMillis() - t}ms / ${turn.segBytes} 字节" +
                    "（+${turn.elapsedMs()}ms）"
            )
        }
    }

    /**
     * Stream mode 关时的整段路径：一次 `POST /audio/speech` 拿完整音频，
     * 然后用 [AudioPlayer.play] 一整块写进 AudioTrack。
     *
     * 和逐句路径的区别不只是"不切句"：
     *  - 不建连续通道（Speaker），不预垫、不跨段拼接，也就没有那条路上的任何状态；
     *  - 播放前整段音频已经在内存里，写轨道的速度由音频本身决定，最接近"重放"那条干净路径。
     *
     * 代价是开口要等整段合成完 —— 云端快模型无所谓，本地慢模型请用 Stream mode 开。
     */
    private fun synthesizeWhole(turn: Turn, s: SpeechSegmenter.Segment, tts: Endpoint) {
        val t = System.currentTimeMillis()
        try {
            val audio = TtsClient.synthesize(
                base = tts.baseUrl,
                key = tts.apiKey,
                model = tts.model,
                voice = tts.voice,
                text = s.text,
                readTimeoutMs = local.ttsTimeoutSec() * 1000,
            )
            if (turn.cancelled) return
            Log.i(
                TAG,
                "整段合成 ${System.currentTimeMillis() - t}ms / ${audio.bytes.size} 字节" +
                    "（+${turn.elapsedMs()}ms）"
            )
            turn.archiveWhole(s.index, audio.contentType, audio.bytes, s.text)
            post { onSpeechSegment(s.index, s.text) }

            val latch = CountDownLatch(1)
            player.play(
                contentType = audio.contentType,
                bytes = audio.bytes,
                onLevel = { onBands(it) },
                onProgress = { played, total ->
                    if (total > 0) post { onSpeechProgress(s.index, played.toFloat() / total) }
                },
                onDone = { latch.countDown() },
                onStarted = {
                    turn.heard = true
                    speaking = true
                    postState(State.SPEAKING)
                    Log.i(TAG, "首次出声 +${turn.elapsedMs()}ms（整段）")
                },
            )
            while (!turn.cancelled && latch.count > 0L) {
                runCatching { latch.await(200, TimeUnit.MILLISECONDS) }
            }
            if (!turn.cancelled) post { onSpeechProgress(s.index, 1f) }
        } catch (e: Exception) {
            if (!turn.cancelled) {
                Log.w(TAG, "整段合成失败：${e.message}")
                post { onSpeechProgress(s.index, 1f) }
            }
        }
    }

    private fun runTurn(pcm: ShortArray?, presetText: String?) {
        try {
            val asr = config.asr()
            val llm = config.llm()
            val tts = config.tts()
            val mode = llm.llmMode

            val needAsr = presetText == null
            val missing = when {
                needAsr && !asr.configured -> "ASR"
                !llm.configured -> "LLM"
                !tts.configured -> "TTS"
                else -> null
            }
            if (missing != null) {
                post { onError("请先在设置里填好 $missing") }
                return
            }

            // ---- 用户输入 ----
            var userText: String
            if (presetText != null) {
                userText = presetText
            } else {
                postState(State.RECOGNIZING)
                val wav = Wav.fromPcm16(pcm!!, pcm.size, SpeechInput.SAMPLE_RATE)
                Log.i(TAG, "utterance captured: ${pcm.size} samples, wav ${wav.size} bytes")
                // 调试用：把这一轮听到的东西落盘，方便回放/分析
                runCatching { java.io.File(appContext.cacheDir, "utterance.wav").writeBytes(wav) }
                if (local.saveUserAudio()) {
                    store.save(
                        bytes = wav, contentType = "audio/wav", text = "", kind = VoiceKind.USER,
                        provider = "麦克风",
                        durationMs = pcm.size * 1000 / SpeechInput.SAMPLE_RATE,
                    )
                }
                userText = AsrClient.transcribe(
                    asr.baseUrl, asr.apiKey, asr.model, wav,
                    readTimeoutMs = local.asrTimeoutSec() * 1000,
                ) { sent, total ->
                    if (total > 0) {
                        val p = sent.toFloat() / total
                        post { onAsrProgress(p) }
                    }
                }
                Log.i(TAG, "ASR → $userText")

                // Live 弱模型兜底：先把识别结果交给工具 LLM 整理，再进对话。
                // 失败不阻断这一轮，直接用原文（云端强 ASR 可以关掉这个开关）。
                if (local.liveRefine() && userText.isNotBlank()) {
                    val tool = config.toolLlm()
                    if (tool.configured) {
                        postState(State.REFINING)
                        val raw = userText
                        val refined = runCatching {
                            TextRefiner.refine(tool, local.refinePrompt(), raw)
                        }.getOrNull()
                        if (!refined.isNullOrBlank()) {
                            userText = refined
                            Log.i(TAG, "整理 → ${refined.length} chars（原文 ${raw.length}）")
                        } else {
                            Log.w(TAG, "整理失败，使用 ASR 原文")
                            post { onNotice("整理失败，已用识别原文") }
                        }
                    } else {
                        post { onNotice("工具 LLM 未配置，跳过整理") }
                    }
                }
            }
            post { onUserText(userText) }

            // ---- LLM（流式）+ 逐句 TTS ----
            postState(State.THINKING)
            val turn = Turn()
            currentTurn = turn
            val synth = Executors.newSingleThreadExecutor { r ->
                Thread(r, "tts-synth").also { it.isDaemon = true }
            }
            turn.synth = synth

            val segmenter = SpeechSegmenter(
                firstMax = local.firstChunkChars(),
                max = local.chunkChars(),
                chunked = local.streamSpeech(),
            )

            fun emit(list: List<SpeechSegmenter.Segment>) {
                if (list.isEmpty() || turn.cancelled) return
                if (!turn.synthStatePosted) {
                    turn.synthStatePosted = true
                    // 出声之后别再退回「合成中」，否则状态行会来回跳
                    if (!turn.heard) postState(State.SYNTHESIZING)
                }
                for (s in list) {
                    if (turn.emitted == 0) {
                        Log.i(TAG, "首片定稿 +${turn.elapsedMs()}ms：${s.text}")
                    }
                    turn.emitted++
                    // 取消（停止 / 被新的一轮顶掉）会让池子先 shutdownNow，
                    // 这里再 execute 会抛 RejectedExecutionException —— 属于正常收摊，吞掉即可，
                    // 否则会冒到最外层变成一个假的"出错"提示。
                    try {
                        synth.execute { synthesizeSegment(turn, s) }
                    } catch (_: java.util.concurrent.RejectedExecutionException) {
                    }
                }
            }

            val sb = StringBuilder()
            var failure: Exception? = null
            val reply = try {
                when (mode) {
                    LlmMode.TAVERNLAB -> LlmClient.streamText(
                        base = llm.baseUrl,
                        key = llm.apiKey,
                        model = llm.model,
                        text = userText,
                        onDelta = { delta ->
                            sb.append(delta)
                            post { onAiText(sb.toString()) }
                            emit(segmenter.feed(delta))
                        },
                        shouldStop = { turn.cancelled },
                    )

                    LlmMode.OPENAI -> {
                        prepareHistory(mode)
                        history.add(LlmClient.Msg("user", userText))
                        val r = LlmClient.streamChat(
                            base = llm.baseUrl,
                            key = llm.apiKey,
                            model = llm.model,
                            history = history,
                            onDelta = { delta ->
                                sb.append(delta)
                                post { onAiText(sb.toString()) }
                                emit(segmenter.feed(delta))
                            },
                            shouldStop = { turn.cancelled },
                        )
                        if (!turn.cancelled) history.add(LlmClient.Msg("assistant", r))
                        trimHistory()
                        r
                    }
                }
            } catch (e: Exception) {
                failure = e
                turn.cancelled = true
                sb.toString()
            } finally {
                emit(segmenter.finish())
                synth.shutdown()
                // 合成线程把已提交的都做完；取消后它会很快收摊
                runCatching { synth.awaitTermination(1800, TimeUnit.SECONDS) }
                turn.finishQueue()
            }
            Log.i(TAG, "LLM → ${reply.length} chars")
            failure?.let { throw it }

            // 真正出声了才算「这一轮说过了」
            val heard = turn.heard
            val audio = turn.audioBytes()
            if (audio.isNotEmpty()) {
                Log.i(TAG, "TTS → ${turn.contentType} ${audio.size} bytes / ${turn.segments} 片")
            }

            // 存进语音库：**原始字节拼接**一份。
            // PCM 会被 VoiceStore 包成 WAV（可播、也是 fine-tune 想要的样子）；
            // mp3/aac 这类帧式容器直接首尾相接也能播（时长元数据可能不准，
            // 但归档本来也不拿来听）。OGG/WAV 这类带容器的拼接后可能播不动，
            // 那也是"原始备份"，导出后自己转码即可。
            val saved = if (local.saveVoice() && audio.isNotEmpty()) {
                store.save(
                    bytes = audio,
                    contentType = turn.contentType,
                    text = turn.texts(),
                    model = tts.model,
                    voice = tts.voice,
                    provider = tts.name,
                    kind = VoiceKind.AI,
                    durationMs = estimateDurationMs(turn.contentType, audio),
                ).also { pruneQuietly() }
            } else {
                null
            }
            if (heard) {
                lastPlayedEntry = saved
                lastPlayedAudio = turn.contentType to audio
            }
            speaking = false
            Log.i(TAG, "playback done")
        } catch (e: Exception) {
            Log.e(TAG, "turn failed", e)
            val msg = e.message ?: e.javaClass.simpleName
            post { onError(msg) }
        } finally {
            currentTurn = null
            busy = false
            speaking = false
            post { afterTurn() }
        }
    }

    private fun pruneQuietly() {
        runCatching { store.prune(local.voiceMaxCount(), local.voiceMaxDays()) }
    }

    private fun estimateDurationMs(contentType: String, bytes: ByteArray): Int {
        val t = contentType.lowercase()
        if (t.contains("pcm") || t.contains("l16")) {
            val rate = AudioPlayer.parseRate(contentType) ?: 24000
            return bytes.size / 2 * 1000 / rate
        }
        return 0
    }

    /** 通用 OpenAI 模式：切换模式时重置历史（system 钉在 index 0）。 */
    private fun prepareHistory(mode: LlmMode) {
        if (historyMode != mode) {
            history.clear()
            history.add(LlmClient.Msg("system", SYSTEM_PROMPT))
            historyMode = mode
        }
    }

    /**
     * 通用 OpenAI 模式的历史裁剪：system 钉在 index 0 永不淘汰，
     * 只从最老的 user+assistant 成对删，保证窗口永远以 user 开头。
     */
    private fun trimHistory() {
        while (history.size > 1 + MAX_TURNS * 2) {
            history.removeAt(1)
            if (history.size > 1) history.removeAt(1)
        }
    }

    private fun postState(s: State) = post { onState(s) }

    private fun post(block: () -> Unit) = main.post(block)

    companion object {
        private const val TAG = "Conversation"

        /** 通用 OpenAI 模式最多保留的轮数（TavernLab 模式不用这段历史）。 */
        private const val MAX_TURNS = 8

        /** 仅在通用 OpenAI 模式下使用：桌面伙伴的语气，短、口语化。 */
        private const val SYSTEM_PROMPT =
            "你是 DeskSpirit，一个住在手机里的桌面 AI 伙伴。用简短、口语化的中文回答，" +
                "一般不超过两句话，除非对方明确要你详细说。"
    }
}
