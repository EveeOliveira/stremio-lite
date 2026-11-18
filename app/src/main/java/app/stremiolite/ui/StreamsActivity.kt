package app.stremiolite.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.stremiolite.R
import app.stremiolite.addon.AddonClient
import app.stremiolite.addon.Stream
import app.stremiolite.data.Repo
import app.stremiolite.player.PlayerActivity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/** Lista de fontes de todos os addons; destaca e pre-seleciona 720p (resolucao nativa do projetor). */
class StreamsActivity : ComponentActivity() {
    private val streams = mutableListOf<Stream>()
    private lateinit var adapter: SimpleAdapter<Stream>
    private lateinit var list: RecyclerView
    private var userMoved = false
    private var bestIndex = -1
    private var usedBefore = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_streams)
        val type = intent.getStringExtra("type")!!
        val videoId = intent.getStringExtra("videoId")!!
        findViewById<TextView>(R.id.title).text = listOfNotNull(intent.getStringExtra("name"),
            intent.getStringExtra("label")?.takeIf { it != intent.getStringExtra("name") }).joinToString("  —  ")
        val status = findViewById<TextView>(R.id.status)

        adapter = SimpleAdapter(R.layout.item_stream, ::bindStream) { s, _ -> play(s) }
        list = findViewById(R.id.list)
        list.tvSetup()
        list.adapter = adapter

        status.text = "Buscando fontes…"
        lifecycleScope.launch {
            val addons = Repo.addons().filter { it.manifest.supports("stream", type, videoId) }
            if (addons.isEmpty()) { status.text = "Nenhum addon de fontes (ex.: Torrentio) para este título."; return@launch }
            var pending = addons.size
            var hidden = 0
            addons.map { a ->
                async {
                    val res = runCatching { AddonClient.streams(a, type, videoId) }
                    pending--
                    res.onSuccess { got ->
                        val ok = got.filter { it.playable }
                        hidden += got.size - ok.size
                        streams += ok
                        refresh()
                    }
                    status.text = buildString {
                        append("${streams.size} fontes")
                        if (pending > 0) append(" · aguardando $pending addon(s)…")
                        if (hidden > 0) append(" · $hidden ocultas (torrent/externo, sem suporte)")
                    }
                }
            }.awaitAll()
            if (streams.isEmpty()) status.text = "Nenhuma fonte HTTP encontrada." + if (hidden > 0) " ($hidden torrents ocultos: configure Real-Debrid no Torrentio)" else ""
        }
    }

    override fun onResume() {
        super.onResume()
        if (Repo.endedVideoId == intent.getStringExtra("videoId")) { Repo.endedVideoId = null; finish() }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN && streams.isNotEmpty()) userMoved = true
        return super.dispatchKeyEvent(event)
    }

    private fun refresh() {
        // melhor: a fonte ja usada neste video; depois a qualidade escolhida por ultimo (padrao 720p),
        // com debrid em cache (RD+) na frente; por fim a primeira
        val (sourceKey, quality) = Repo.preferredSource(intent.getStringExtra("metaId").orEmpty(), intent.getStringExtra("videoId").orEmpty())
        val same = sourceKey?.let { k -> streams.indexOfFirst { it.sourceKey == k } } ?: -1
        usedBefore = same >= 0
        fun cached(q: String) = streams.indexOfFirst { it.info.quality == q && it.info.debrid?.endsWith("+") == true }
        fun any(q: String) = streams.indexOfFirst { it.info.quality == q }
        val wanted = listOfNotNull(quality, "720p").distinct()
        bestIndex = (listOf(same) + wanted.flatMap { listOf(cached(it), any(it)) }).firstOrNull { it >= 0 } ?: 0
        adapter.items = streams.toList()
        if (!userMoved && streams.isNotEmpty()) {
            (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(bestIndex, 0)
            list.post { list.findViewHolderForAdapterPosition(bestIndex)?.itemView?.requestFocus() }
        }
    }

    private fun bindStream(v: View, s: Stream, pos: Int) {
        val q = v.findViewById<TextView>(R.id.quality)
        q.text = s.info.quality ?: "?"
        q.setTextColor(if (pos == bestIndex) getColor(R.color.ok) else getColor(R.color.text))
        v.findViewById<TextView>(R.id.name).text = s.name.replace('\n', ' ')
        v.findViewById<TextView>(R.id.desc).text = (s.filename ?: s.title.lineSequence().firstOrNull().orEmpty())
        val badges = v.findViewById<LinearLayout>(R.id.badges)
        badges.removeAllViews()
        fun badge(text: String?, color: Int = Color.WHITE) {
            if (text.isNullOrBlank()) return
            badges.addView(TextView(this).apply {
                this.text = text
                textSize = 11f
                setTextColor(color)
                setBackgroundResource(R.drawable.badge_bg)
                setPadding(dp(6), dp(1), dp(6), dp(1))
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }
            })
        }
        if (pos == bestIndex) badge(if (usedBefore) "★ fonte anterior" else "★ recomendado", getColor(R.color.ok))
        badge(s.info.debrid, if (s.info.debrid?.endsWith("+") == true) getColor(R.color.ok) else Color.WHITE)
        badge(s.info.size)
        badge(s.info.audio?.let { "🔊 $it" })
        badge(s.info.video)
        badge(s.addonName, getColor(R.color.text2))
    }

    private fun play(s: Stream) {
        Repo.rememberSource(intent.getStringExtra("metaId").orEmpty(), intent.getStringExtra("videoId").orEmpty(), s.sourceKey, s.info.quality)
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_URL, s.url)
            .putExtra(PlayerActivity.EXTRA_HEADERS, HashMap(s.headers))
            .putExtra(PlayerActivity.EXTRA_FILENAME, s.filename)
            .putExtra(PlayerActivity.EXTRA_SIZE, s.videoSize)
            .putExtra("type", intent.getStringExtra("type"))
            .putExtra("metaId", intent.getStringExtra("metaId"))
            .putExtra("videoId", intent.getStringExtra("videoId"))
            .putExtra("name", intent.getStringExtra("name"))
            .putExtra("poster", intent.getStringExtra("poster"))
            .putExtra("label", intent.getStringExtra("label")))
    }
}
