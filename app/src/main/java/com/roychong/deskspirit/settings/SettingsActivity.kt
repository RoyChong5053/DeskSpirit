package com.roychong.deskspirit.settings

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.roychong.deskspirit.R
import com.roychong.deskspirit.config.ApiConfig
import com.roychong.deskspirit.net.ApiTester
import kotlin.concurrent.thread

class SettingsActivity : AppCompatActivity() {

    private lateinit var config: ApiConfig

    private lateinit var llmBase: EditText
    private lateinit var llmKey: EditText
    private lateinit var llmModel: EditText
    private lateinit var llmResult: TextView

    private lateinit var asrBase: EditText
    private lateinit var asrKey: EditText
    private lateinit var asrModel: EditText
    private lateinit var asrResult: TextView

    private lateinit var ttsBase: EditText
    private lateinit var ttsKey: EditText
    private lateinit var ttsModel: EditText
    private lateinit var ttsVoice: EditText
    private lateinit var ttsResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        config = ApiConfig(this)

        llmBase = findViewById(R.id.llmBase)
        llmKey = findViewById(R.id.llmKey)
        llmModel = findViewById(R.id.llmModel)
        llmResult = findViewById(R.id.llmResult)
        asrBase = findViewById(R.id.asrBase)
        asrKey = findViewById(R.id.asrKey)
        asrModel = findViewById(R.id.asrModel)
        asrResult = findViewById(R.id.asrResult)
        ttsBase = findViewById(R.id.ttsBase)
        ttsKey = findViewById(R.id.ttsKey)
        ttsModel = findViewById(R.id.ttsModel)
        ttsVoice = findViewById(R.id.ttsVoice)
        ttsResult = findViewById(R.id.ttsResult)

        load()

        findViewById<Button>(R.id.btnTestLlm).setOnClickListener {
            val r = llmResult; r.text = getString(R.string.testing)
            thread {
                val res = ApiTester.testLlm(llmBase.text.toString(), llmKey.text.toString(), llmModel.text.toString())
                runOnUiThread { r.text = "${if (res.ok) "✔" else "✘"} ${res.message}" }
            }
        }
        findViewById<Button>(R.id.btnTestAsr).setOnClickListener {
            val r = asrResult; r.text = getString(R.string.testing)
            thread {
                val res = ApiTester.testAsr(asrBase.text.toString(), asrKey.text.toString(), asrModel.text.toString())
                runOnUiThread { r.text = "${if (res.ok) "✔" else "✘"} ${res.message}" }
            }
        }
        findViewById<Button>(R.id.btnTestTts).setOnClickListener {
            val r = ttsResult; r.text = getString(R.string.testing)
            thread {
                val res = ApiTester.testTts(
                    ttsBase.text.toString(), ttsKey.text.toString(),
                    ttsModel.text.toString(), ttsVoice.text.toString()
                )
                runOnUiThread { r.text = "${if (res.ok) "✔" else "✘"} ${res.message}" }
            }
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            save()
            Toast.makeText(this, getString(R.string.saved), Toast.LENGTH_SHORT).show()
        }
    }

    private fun load() {
        val l = config.llm(); llmBase.setText(l.baseUrl); llmKey.setText(l.apiKey); llmModel.setText(l.model)
        val a = config.asr(); asrBase.setText(a.baseUrl); asrKey.setText(a.apiKey); asrModel.setText(a.model)
        val t = config.tts(); ttsBase.setText(t.baseUrl); ttsKey.setText(t.apiKey); ttsModel.setText(t.model); ttsVoice.setText(t.voice)
    }

    private fun save() {
        config.saveLlm(llmBase.text.toString(), llmKey.text.toString(), llmModel.text.toString())
        config.saveAsr(asrBase.text.toString(), asrKey.text.toString(), asrModel.text.toString())
        config.saveTts(ttsBase.text.toString(), ttsKey.text.toString(), ttsModel.text.toString(), ttsVoice.text.toString())
    }
}
