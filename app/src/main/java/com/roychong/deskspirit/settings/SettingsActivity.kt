package com.roychong.deskspirit.settings

import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.roychong.deskspirit.R
import com.roychong.deskspirit.config.ApiConfig
import com.roychong.deskspirit.config.AudioSettings
import com.roychong.deskspirit.config.DictationParams
import com.roychong.deskspirit.config.Endpoint
import com.roychong.deskspirit.config.LlmMode
import com.roychong.deskspirit.config.Slot
import com.roychong.deskspirit.config.VadParams
import com.roychong.deskspirit.net.ApiTester
import com.roychong.deskspirit.net.TavernLabClient
import com.roychong.deskspirit.render.SphereStyle
import com.roychong.deskspirit.voice.VoiceStore
import kotlin.concurrent.thread

/**
 * 设置页：顶部四个槽位，**每页只显示自己的东西**。
 *
 *  - Main（LLM 对话）：端点 + TavernLab 开关 + 角色选择 + 圆球外观
 *  - 工具 LLM：端点 + 整理 prompt 模板
 *  - ASR：端点 + 收音/VAD + 口述时长 + 录音存档
 *  - TTS：端点 + Voice + 语音库
 *
 * 端点区（profile 下拉 / 改名 / 新建 / 字段 / 测试）四页共用，只换下面的专属区块。
 * 编辑在内存工作副本里进行，「保存」时统一落盘；角色切换是服务端动作，选了就立刻生效。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var config: ApiConfig
    private lateinit var local: AudioSettings

    private lateinit var spProfile: Spinner
    private lateinit var etBase: EditText
    private lateinit var etKey: EditText
    private lateinit var etModel: EditText
    private lateinit var etVoice: EditText
    private lateinit var swTavernLab: Switch
    private lateinit var tvResult: TextView
    private lateinit var tvEndpointTitle: TextView
    private lateinit var tvSlotHint: TextView
    private lateinit var slotButtons: Map<Slot, Button>

    private lateinit var boxMain: View
    private lateinit var boxTool: View
    private lateinit var boxAsr: View
    private lateinit var boxTts: View

    // Main 页 · 角色
    private lateinit var tvSecChar: TextView
    private lateinit var rowChar: View
    private lateinit var spChar: Spinner
    private lateinit var ivCharAvatar: ImageView
    private lateinit var tvCharHint: TextView

    // Main 页 · 外观
    private lateinit var spStyle: Spinner
    private lateinit var tvStyleHint: TextView

    private lateinit var etAsrTimeout: EditText
    private lateinit var etTtsTimeout: EditText
    private lateinit var etEndSilence: EditText
    private lateinit var etMinLevel: EditText
    private lateinit var etVoiceRatio: EditText
    private lateinit var etMaxSpeech: EditText
    private lateinit var etMinVoiced: EditText
    private lateinit var etMinRatio: EditText
    private lateinit var etMinSpeech: EditText
    private lateinit var etDictMax: EditText

    private lateinit var swSaveVoice: Switch
    private lateinit var swSaveUser: Switch
    private lateinit var etMaxCount: EditText
    private lateinit var etMaxDays: EditText
    private lateinit var tvLibPath: TextView

    private lateinit var etRefinePrompt: EditText

    private class SlotState(val list: MutableList<Endpoint>, var active: Int)

    private val work = HashMap<Slot, SlotState>()
    private var current: Slot = Slot.LLM_DIALOG
    private var rendering = false

    /** 角色列表（服务端）。角色区只在 Main + TavernLab 模式下才有意义。 */
    private var chars: List<TavernLabClient.Char> = emptyList()
    private var charLoaded = false
    private var charLoading = false
    /** 正在切换/刷新的角色名，避免 Spinner 回调把用户的选择又弹回去。 */
    private var charPinned: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        config = ApiConfig(this)
        local = AudioSettings(this)

        spProfile = findViewById(R.id.spProfile)
        etBase = findViewById(R.id.etBase)
        etKey = findViewById(R.id.etKey)
        etModel = findViewById(R.id.etModel)
        etVoice = findViewById(R.id.etVoice)
        swTavernLab = findViewById(R.id.swTavernLab)
        tvResult = findViewById(R.id.tvResult)
        tvEndpointTitle = findViewById(R.id.tvEndpointTitle)
        tvSlotHint = findViewById(R.id.tvSlotHint)

        boxMain = findViewById(R.id.boxMain)
        boxTool = findViewById(R.id.boxTool)
        boxAsr = findViewById(R.id.boxAsr)
        boxTts = findViewById(R.id.boxTts)

        tvSecChar = findViewById(R.id.tvSecChar)
        rowChar = findViewById(R.id.rowChar)
        spChar = findViewById(R.id.spChar)
        ivCharAvatar = findViewById(R.id.ivCharAvatar)
        tvCharHint = findViewById(R.id.tvCharHint)
        spStyle = findViewById(R.id.spStyle)
        tvStyleHint = findViewById(R.id.tvStyleHint)

        etAsrTimeout = findViewById(R.id.etAsrTimeout)
        etTtsTimeout = findViewById(R.id.etTtsTimeout)
        etEndSilence = findViewById(R.id.etEndSilence)
        etMinLevel = findViewById(R.id.etMinLevel)
        etVoiceRatio = findViewById(R.id.etVoiceRatio)
        etMaxSpeech = findViewById(R.id.etMaxSpeech)
        etMinVoiced = findViewById(R.id.etMinVoiced)
        etMinRatio = findViewById(R.id.etMinRatio)
        etMinSpeech = findViewById(R.id.etMinSpeech)
        etDictMax = findViewById(R.id.etDictMax)

        swSaveVoice = findViewById(R.id.swSaveVoice)
        swSaveUser = findViewById(R.id.swSaveUser)
        etMaxCount = findViewById(R.id.etMaxCount)
        etMaxDays = findViewById(R.id.etMaxDays)
        tvLibPath = findViewById(R.id.tvLibPath)

        etRefinePrompt = findViewById(R.id.etRefinePrompt)

        slotButtons = mapOf(
            Slot.LLM_DIALOG to findViewById(R.id.slotLlm),
            Slot.LLM_TOOL to findViewById(R.id.slotTool),
            Slot.ASR to findViewById(R.id.slotAsr),
            Slot.TTS to findViewById(R.id.slotTts),
        )

        // 载入工作副本
        for (s in Slot.values()) {
            work[s] = SlotState(config.profiles(s).toMutableList(), config.activeIndex(s))
        }

        for ((slot, btn) in slotButtons) {
            btn.setOnClickListener { switchSlot(slot) }
        }

        spProfile.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (rendering) return
                val st = work[current] ?: return
                // 载入/re-render 时 Spinner 也会回调，位置没变就忽略，
                // 否则会把刚填好的字段当成"用户切换了 profile"而清空。
                if (pos == st.active) return
                commit()
                st.active = pos
                // 换了端点（可能换了 TavernLab 服务器），角色列表作废
                if (current == Slot.LLM_DIALOG) invalidateChars()
                renderEditor()
            }

            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        spProfile.setOnLongClickListener { renameProfile(); true }

        findViewById<View>(R.id.btnRenameProfile).setOnClickListener { renameProfile() }
        findViewById<View>(R.id.btnAddProfile).setOnClickListener { addProfile() }
        findViewById<View>(R.id.btnDupProfile).setOnClickListener { dupProfile() }
        findViewById<View>(R.id.btnDelProfile).setOnClickListener { delProfile() }
        findViewById<View>(R.id.btnTest).setOnClickListener { testEndpoint() }
        findViewById<View>(R.id.btnPullModels).setOnClickListener { pullModels() }
        findViewById<View>(R.id.btnSave).setOnClickListener { saveAll() }

        swTavernLab.setOnCheckedChangeListener { _, _ ->
            if (rendering) return@setOnCheckedChangeListener
            renderCharSection()
        }

        // 角色：选了就切服务端当前角色
        spChar.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (rendering || chars.isEmpty()) return
                val pick = chars[pos.coerceIn(0, chars.size - 1)]
                if (pick.name == charPinned) return
                switchChar(pick)
            }

            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        findViewById<View>(R.id.btnCharRefresh).setOnClickListener { fetchChars(force = true) }

        // 外观：选了立刻存（切回主界面即生效）
        spStyle.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val st = SphereStyle.entries[pos.coerceIn(0, SphereStyle.entries.size - 1)]
                tvStyleHint.text = getString(st.descRes)
                if (rendering) return
                local.saveSphereStyle(st.id)
                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.style_switched_fmt, getString(st.labelRes)),
                    Toast.LENGTH_SHORT
                ).show()
            }

            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        loadLocalSettings()
        renderEditor()
    }

    // ---------------- profile 编辑 ----------------

    private fun switchSlot(slot: Slot) {
        commit()
        current = slot
        renderEditor()
    }

    private fun renderEditor() {
        rendering = true
        val st = work[current]!!
        val names = st.list.map { it.name }
        val ad = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spProfile.adapter = ad
        st.active = st.active.coerceIn(0, st.list.size - 1)
        spProfile.setSelection(st.active)

        val e = st.list[st.active]
        etBase.setText(e.baseUrl)
        etKey.setText(e.apiKey)
        etModel.setText(e.model)
        etVoice.setText(e.voice)
        swTavernLab.isChecked = current == Slot.LLM_DIALOG && e.llmMode == LlmMode.TAVERNLAB

        tvEndpointTitle.text = getString(R.string.sec_endpoint_fmt, current.label)
        etVoice.visibility = if (current.needsVoice) View.VISIBLE else View.GONE
        // TavernLab 只在 Main 那一页有意义：工具 LLM 明确不支持（见 hint_tool_llm）
        swTavernLab.visibility = if (current == Slot.LLM_DIALOG) View.VISIBLE else View.GONE
        if (current == Slot.LLM_TOOL) {
            tvSlotHint.setText(R.string.hint_tool_llm)
            tvSlotHint.visibility = View.VISIBLE
        } else {
            tvSlotHint.visibility = View.GONE
        }
        tvResult.text = ""

        // 每页只显示自己的区块
        boxMain.visibility = if (current == Slot.LLM_DIALOG) View.VISIBLE else View.GONE
        boxTool.visibility = if (current == Slot.LLM_TOOL) View.VISIBLE else View.GONE
        boxAsr.visibility = if (current == Slot.ASR) View.VISIBLE else View.GONE
        boxTts.visibility = if (current == Slot.TTS) View.VISIBLE else View.GONE

        for ((s, b) in slotButtons) {
            b.alpha = if (s == current) 1f else 0.5f
        }
        rendering = false

        if (current == Slot.LLM_DIALOG) {
            renderCharSection()
            if (swTavernLab.isChecked) fetchChars(force = false)
        }
    }

    private fun commit() {
        val st = work[current] ?: return
        if (st.list.isEmpty()) return
        val i = st.active.coerceIn(0, st.list.size - 1)
        val old = st.list[i]
        st.list[i] = old.copy(
            baseUrl = etBase.text.toString().trim(),
            apiKey = etKey.text.toString().trim(),
            model = etModel.text.toString().trim(),
            voice = etVoice.text.toString().trim(),
            // 只有 Main 页的开关决定 TavernLab 模式；别的槽位一律普通 OpenAI 兼容
            llmMode = if (current == Slot.LLM_DIALOG && swTavernLab.isChecked) {
                LlmMode.TAVERNLAB
            } else {
                LlmMode.OPENAI
            },
        )
    }

    private fun addProfile() {
        commit()
        val st = work[current]!!
        st.list.add(Endpoint(name = uniqueName(getString(R.string.prof_new, st.list.size + 1), st.list)))
        st.active = st.list.size - 1
        renderEditor()
        etBase.requestFocus()
        Toast.makeText(this, getString(R.string.prof_created_hint), Toast.LENGTH_SHORT).show()
    }

    private fun dupProfile() {
        commit()
        val st = work[current]!!
        val src = st.list[st.active]
        st.list.add(src.copy(name = uniqueName(getString(R.string.prof_copy, src.name), st.list)))
        st.active = st.list.size - 1
        renderEditor()
    }

    private fun delProfile() {
        commit()
        val st = work[current]!!
        if (st.list.size <= 1) {
            Toast.makeText(this, R.string.prof_keep_one, Toast.LENGTH_SHORT).show()
            return
        }
        st.list.removeAt(st.active)
        st.active = (st.active - 1).coerceAtLeast(0)
        renderEditor()
    }

    private fun renameProfile() {
        commit()
        val st = work[current]!!
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_TEXT
        et.setText(st.list[st.active].name)
        AlertDialog.Builder(this)
            .setTitle(R.string.prof_rename)
            .setView(et)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val raw = et.text.toString().trim()
                if (raw.isNotEmpty()) {
                    val others = st.list.filterIndexed { i, _ -> i != st.active }
                    val old = st.list[st.active]
                    st.list[st.active] = old.copy(name = uniqueName(raw, others))
                    renderEditor()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 名字去重：重名追加 (2)、(3)…，免得一眼看去全是同名。 */
    private fun uniqueName(want: String, taken: List<Endpoint>): String {
        val used = taken.map { it.name }.toSet()
        if (want !in used) return want
        var i = 2
        while ("$want ($i)" in used) i++
        return "$want ($i)"
    }

    private fun testEndpoint() {
        commit()
        val e = work[current]!!.let { it.list[it.active] }
        tvResult.text = getString(R.string.testing)
        thread {
            val res = ApiTester.test(current, e, local.asrTimeoutSec(), local.ttsTimeoutSec())
            runOnUiThread { tvResult.text = "${if (res.ok) "✔" else "✘"} ${res.message}" }
        }
    }

    private fun pullModels() {
        commit()
        val e = work[current]!!.let { it.list[it.active] }
        tvResult.text = getString(R.string.pulling)
        thread {
            val res = ApiTester.listModels(e.baseUrl, e.apiKey)
            runOnUiThread {
                if (!res.ok) {
                    tvResult.text = "✘ ${res.message}"
                    return@runOnUiThread
                }
                tvResult.text = "✔ ${res.message}"
                AlertDialog.Builder(this)
                    .setTitle(R.string.pick_model)
                    .setItems(res.ids.toTypedArray()) { _, which -> etModel.setText(res.ids[which]) }
                    .show()
            }
        }
    }

    // ---------------- Main 页 · 角色 ----------------

    private fun renderCharSection() {
        val tavern = swTavernLab.isChecked
        tvSecChar.visibility = if (tavern) View.VISIBLE else View.GONE
        rowChar.visibility = if (tavern) View.VISIBLE else View.GONE
        tvCharHint.visibility = if (tavern) View.VISIBLE else View.GONE
        if (!tavern) {
            invalidateChars()
            return
        }
        if (chars.isEmpty()) {
            ivCharAvatar.setImageDrawable(null)
        }
    }

    private fun invalidateChars() {
        chars = emptyList()
        charLoaded = false
        charPinned = null
        rendering = true
        spChar.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, listOf(getString(R.string.char_loading))
        )
        rendering = false
    }

    private fun fetchChars(force: Boolean) {
        if (charLoading) return
        if (charLoaded && !force) return
        if (!swTavernLab.isChecked) return

        commit()
        val llm = work[Slot.LLM_DIALOG]?.let { it.list[it.active] } ?: return
        if (llm.baseUrl.isBlank()) return

        charLoading = true
        tvCharHint.text = getString(R.string.char_loading)
        thread {
            val res = runCatching { TavernLabClient.characters(llm.baseUrl, llm.apiKey) }
            val cur = runCatching { TavernLabClient.currentChar(llm.baseUrl, llm.apiKey) }.getOrDefault("")
            runOnUiThread {
                charLoading = false
                tvCharHint.text = getString(R.string.char_hint)
                val list = res.getOrNull()
                if (list == null) {
                    charLoaded = false
                    tvCharHint.text = getString(
                        R.string.char_failed_fmt,
                        res.exceptionOrNull()?.message ?: "?"
                    )
                    return@runOnUiThread
                }
                chars = list
                charLoaded = true
                if (list.isEmpty()) {
                    tvCharHint.text = getString(R.string.char_none)
                    return@runOnUiThread
                }
                bindChars(cur.ifBlank { local.tavernChar() })
            }
        }
    }

    private fun bindChars(currentName: String) {
        rendering = true
        val labels = chars.map { it.name }
        val ad = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spChar.adapter = ad
        val idx = chars.indexOfFirst { it.name == currentName }.coerceAtLeast(0)
        spChar.setSelection(idx)
        charPinned = chars[idx].name
        rendering = false
        loadAvatar(chars[idx])
        if (currentName.isNotBlank()) local.saveTavernChar(chars[idx].name)
    }

    private fun switchChar(pick: TavernLabClient.Char) {
        val llm = work[Slot.LLM_DIALOG]?.let { it.list[it.active] } ?: return
        charPinned = pick.name
        loadAvatar(pick)
        local.saveTavernChar(pick.name)
        tvCharHint.text = getString(R.string.char_switched_fmt, pick.name)
        thread {
            val res = runCatching {
                TavernLabClient.setCurrentChar(llm.baseUrl, llm.apiKey, pick.name)
            }
            runOnUiThread {
                if (res.isSuccess) {
                    Toast.makeText(
                        this, getString(R.string.char_switched_fmt, pick.name), Toast.LENGTH_SHORT
                    ).show()
                } else {
                    tvCharHint.text = getString(
                        R.string.char_failed_fmt,
                        res.exceptionOrNull()?.message ?: "?"
                    )
                }
            }
        }
    }

    private fun loadAvatar(c: TavernLabClient.Char) {
        val llm = work[Slot.LLM_DIALOG]?.let { it.list[it.active] } ?: return
        if (c.avatarUrl.isBlank()) {
            ivCharAvatar.setImageDrawable(null)
            return
        }
        thread {
            val bytes = TavernLabClient.avatarBytes(llm.baseUrl, llm.apiKey, c.avatarUrl)
            val bmp = bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
            runOnUiThread {
                // 期间可能又切了角色，别把旧头像贴上去
                if (charPinned == c.name) ivCharAvatar.setImageBitmap(bmp)
            }
        }
    }

    // ---------------- 本地设置 ----------------

    private fun loadLocalSettings() {
        val v = local.vad()
        etAsrTimeout.setText(local.asrTimeoutSec().toString())
        etTtsTimeout.setText(local.ttsTimeoutSec().toString())
        etEndSilence.setText(v.endSilenceMs.toString())
        etMinLevel.setText(v.minLevel.toString())
        etVoiceRatio.setText(v.voiceRatio.toString())
        etMaxSpeech.setText(v.maxSpeechMs.toString())
        etMinVoiced.setText(v.minVoicedMs.toString())
        etMinRatio.setText(v.minVoicedRatio.toString())
        etMinSpeech.setText(v.minSpeechMs.toString())
        etDictMax.setText(local.dictation().maxMs.toString())

        swSaveVoice.isChecked = local.saveVoice()
        swSaveUser.isChecked = local.saveUserAudio()
        etMaxCount.setText(local.voiceMaxCount().toString())
        etMaxDays.setText(local.voiceMaxDays().toString())
        etRefinePrompt.setText(local.refinePrompt())

        tvLibPath.text = getString(R.string.lib_path_fmt, VoiceStore(this).dir.absolutePath)

        // 外观
        val styleAd = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            SphereStyle.entries.map { getString(it.labelRes) }
        )
        styleAd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        val savedStyle = SphereStyle.of(local.sphereStyle())
        rendering = true
        spStyle.adapter = styleAd
        spStyle.setSelection(savedStyle.ordinal)
        tvStyleHint.text = getString(savedStyle.descRes)
        rendering = false

        invalidateChars()
    }

    private fun saveLocalSettings() {
        local.saveAsrTimeoutSec(etAsrTimeout.i(90).coerceIn(10, 3600))
        local.saveTtsTimeoutSec(etTtsTimeout.i(90).coerceIn(10, 3600))
        local.saveVad(
            VadParams(
                endSilenceMs = etEndSilence.i(900),
                minLevel = etMinLevel.f(0.012f),
                voiceRatio = etVoiceRatio.f(3.0f),
                maxSpeechMs = etMaxSpeech.i(15000),
                minVoicedMs = etMinVoiced.i(320),
                minVoicedRatio = etMinRatio.f(0.30f),
                minSpeechMs = etMinSpeech.i(250),
            )
        )
        local.saveDictation(DictationParams(maxMs = etDictMax.i(120_000).coerceIn(10_000, 300_000)))
        local.saveVoicePrefs(
            save = swSaveVoice.isChecked,
            saveUser = swSaveUser.isChecked,
            maxCount = etMaxCount.i(200).coerceAtLeast(0),
            maxDays = etMaxDays.i(30).coerceAtLeast(0),
        )
        local.saveRefinePrompt(etRefinePrompt.text.toString())
        local.saveSphereStyle(SphereStyle.entries[spStyle.selectedItemPosition.coerceIn(
            0, SphereStyle.entries.size - 1
        )].id)
    }

    private fun saveAll() {
        commit()
        for (s in Slot.values()) {
            val st = work[s] ?: continue
            config.save(s, st.list, st.active)
        }
        saveLocalSettings()
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
    }

    private fun EditText.i(def: Int): Int = text.toString().trim().toIntOrNull() ?: def
    private fun EditText.f(def: Float): Float = text.toString().trim().toFloatOrNull() ?: def
}
