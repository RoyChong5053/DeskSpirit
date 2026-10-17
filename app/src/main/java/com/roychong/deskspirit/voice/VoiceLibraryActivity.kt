package com.roychong.deskspirit.voice

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.roychong.deskspirit.R
import com.roychong.deskspirit.audio.AudioPlayer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 语音库：列出所有已存档的语音/口述记录，可播放、收藏、删除、清空。
 * 文件本身落在 Android/media/<pkg>/DeskSpirit/voice/，可复制路径交给任意文件管理器导出。
 */
class VoiceLibraryActivity : AppCompatActivity() {

    private lateinit var store: VoiceStore
    private lateinit var player: AudioPlayer
    private lateinit var listView: ListView
    private lateinit var tvInfo: TextView
    private lateinit var tvEmpty: TextView

    private var items: List<VoiceEntry> = emptyList()
    private val adapter = Adapter()
    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_library)
        store = VoiceStore(this)
        player = AudioPlayer(this)

        listView = findViewById(R.id.list)
        tvInfo = findViewById(R.id.tvInfo)
        tvEmpty = findViewById(R.id.tvEmpty)
        listView.adapter = adapter

        findViewById<View>(R.id.btnOpen).setOnClickListener { showDir() }
        findViewById<View>(R.id.btnClear).setOnClickListener { confirmClear() }

        listView.setOnItemClickListener { _, _, pos, _ -> play(items[pos]) }
        refresh()
    }

    private fun refresh() {
        items = store.list()
        adapter.notifyDataSetChanged()
        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val kb = store.totalBytes() / 1024
        tvInfo.text = getString(R.string.lib_info_fmt, items.size, kb, store.dir.absolutePath)
    }

    private fun play(entry: VoiceEntry) {
        if (!entry.hasAudio) {
            Toast.makeText(this, R.string.lib_no_audio, Toast.LENGTH_SHORT).show()
            return
        }
        val bytes = store.readBytes(entry) ?: return
        player.play(entry.contentType, bytes) {}
    }

    private fun showDir() {
        AlertDialog.Builder(this)
            .setTitle(R.string.lib_open_dir)
            .setMessage(store.dir.absolutePath)
            .setPositiveButton(R.string.lib_copy_path) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("path", store.dir.absolutePath))
                Toast.makeText(this, R.string.lib_path_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage(R.string.lib_clear_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                store.clear(keepFavorites = true)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroy() {
        player.stop()
        super.onDestroy()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: LayoutInflater.from(this@VoiceLibraryActivity)
                .inflate(R.layout.item_voice, parent, false)
            val e = items[position]

            val tvStar = v.findViewById<TextView>(R.id.tvStar)
            val tvTitle = v.findViewById<TextView>(R.id.tvTitle)
            val tvBody = v.findViewById<TextView>(R.id.tvBody)
            val btnPlay = v.findViewById<Button>(R.id.btnPlay)
            val btnDel = v.findViewById<Button>(R.id.btnDel)

            tvStar.text = if (e.favorite) "★" else "☆"
            tvTitle.text = buildString {
                append(e.kind.label)
                append(" · ")
                append(fmt.format(Date(e.ts)))
                if (e.durationMs > 0) append(" · ${e.durationMs / 1000}s")
                if (e.sizeBytes > 0) append(" · ${e.sizeBytes / 1024}KB")
                if (e.provider.isNotBlank()) append(" · ${e.provider}")
            }
            tvBody.text = e.text.ifBlank { getString(R.string.lib_no_text) }

            tvStar.setOnClickListener {
                store.setFavorite(e, !e.favorite)
                refresh()
            }
            btnPlay.isEnabled = e.hasAudio
            btnPlay.setOnClickListener { play(e) }
            btnDel.setOnClickListener {
                store.delete(e)
                refresh()
            }
            return v
        }
    }
}
