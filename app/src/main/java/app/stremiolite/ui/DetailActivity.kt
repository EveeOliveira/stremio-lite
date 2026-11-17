package app.stremiolite.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.stremiolite.R
import app.stremiolite.addon.AddonClient
import app.stremiolite.addon.Meta
import app.stremiolite.addon.Video
import app.stremiolite.addon.str
import app.stremiolite.data.Repo
import kotlinx.coroutines.launch

class DetailActivity : ComponentActivity() {
    private var meta: Meta? = null
    private lateinit var seasonsAdapter: SimpleAdapter<Int>
    private lateinit var episodesAdapter: SimpleAdapter<Video>
    private var selectedSeason = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)
        val type = intent.getStringExtra("type") ?: "movie"
        val id = intent.getStringExtra("id") ?: return finish()
        val status = findViewById<TextView>(R.id.status)

        seasonsAdapter = SimpleAdapter(R.layout.item_chip, { v, s, _ ->
            (v as TextView).text = if (s == 0) "Especiais" else "Temporada $s"
            v.isSelected = s == selectedSeason
        }) { s, _ -> showSeason(s) }
        episodesAdapter = SimpleAdapter(R.layout.item_text, { v, ep, _ ->
            val lib = Repo.libraryItem(id)?.optJSONObject("state")
            val cur = lib?.str("video_id") == ep.id && (lib?.optLong("timeOffset") ?: 0) > 0
            (v as TextView).text = buildString {
                append("${ep.episode}. ${ep.title.ifBlank { "Episódio ${ep.episode}" }}")
                ep.released?.take(10)?.let { append("   ·  $it") }
                if (cur) append("   ▶ continuar")
            }
        }) { ep, _ -> openStreams(ep.id, "T${ep.season} E${ep.episode} · ${ep.title}") }
        findViewById<RecyclerView>(R.id.seasons).apply { tvSetup(horizontal = true); adapter = seasonsAdapter }
        findViewById<RecyclerView>(R.id.episodes).apply { tvSetup(); adapter = episodesAdapter }

        status.text = "Carregando…"
        lifecycleScope.launch {
            val m = runCatching { AddonClient.meta(Repo.addons(), type, id) }.getOrNull()
            if (m == null) { status.text = "Não foi possível carregar os detalhes."; return@launch }
            status.text = ""
            meta = m.copy(type = m.type.ifBlank { type }, id = m.id.ifBlank { id })
            bind(meta!!)
        }
    }

    override fun onResume() {
        super.onResume()
        meta?.let { updateButtons(it) }
        episodesAdapter.notifyDataSetChanged()
    }

    private fun bind(m: Meta) {
        findViewById<TextView>(R.id.title).text = m.name
        findViewById<TextView>(R.id.info).text = m.info
        findViewById<TextView>(R.id.desc).text = m.description.orEmpty()
        findViewById<ImageView>(R.id.poster).loadUrl(m.poster, 150, 225)
        findViewById<ImageView>(R.id.bg).loadUrl(m.background, 320, 180)

        val isSeries = m.videos.any { it.season > 0 || it.episode > 0 } && m.type != "movie"
        if (isSeries) {
            val seasons = m.videos.map { it.season }.distinct().sortedWith(compareBy({ it == 0 }, { it }))
            seasonsAdapter.items = seasons
            val wanted = intent.getStringExtra("videoId") ?: Repo.libraryItem(m.id)?.optJSONObject("state")?.str("video_id")
            val start = m.videos.firstOrNull { it.id == wanted }?.season ?: seasons.first()
            showSeason(start)
        }
        updateButtons(m)
    }

    private fun updateButtons(m: Meta) {
        val play = findViewById<TextView>(R.id.btnPlay)
        val resume = findViewById<TextView>(R.id.btnResume)
        val isSeries = m.videos.any { it.season > 0 || it.episode > 0 } && m.type != "movie"
        val st = Repo.libraryItem(m.id)?.optJSONObject("state")
        val offset = st?.optLong("timeOffset") ?: 0
        if (!isSeries) {
            val videoId = m.videos.singleOrNull()?.id ?: m.id
            play.visibility = View.VISIBLE
            play.text = if (offset > 0 && Repo.resumePosition(m.id, videoId) > 0) "Continuar (${fmt(offset)})" else "Assistir"
            play.setOnClickListener { openStreams(videoId, m.name) }
            resume.visibility = View.GONE
            play.requestFocus()
        } else {
            play.visibility = View.GONE
            val vid = st?.str("video_id")
            val ep = m.videos.firstOrNull { it.id == vid }
            if (ep != null && offset > 0) {
                resume.visibility = View.VISIBLE
                resume.text = "Continuar T${ep.season} E${ep.episode} (${fmt(offset)})"
                resume.setOnClickListener { openStreams(ep.id, "T${ep.season} E${ep.episode} · ${ep.title}") }
                resume.requestFocus()
            } else {
                resume.visibility = View.GONE
                findViewById<RecyclerView>(R.id.episodes).post { findViewById<RecyclerView>(R.id.episodes).getChildAt(0)?.requestFocus() }
            }
        }
    }

    private fun showSeason(s: Int) {
        val m = meta ?: return
        selectedSeason = s
        seasonsAdapter.notifyDataSetChanged()
        episodesAdapter.items = m.videos.filter { it.season == s }.sortedBy { it.episode }
        (findViewById<RecyclerView>(R.id.episodes).layoutManager as LinearLayoutManager).scrollToPosition(0)
    }

    private fun openStreams(videoId: String, label: String) {
        val m = meta ?: return
        startActivity(Intent(this, StreamsActivity::class.java)
            .putExtra("type", m.type).putExtra("metaId", m.id).putExtra("videoId", videoId)
            .putExtra("name", m.name).putExtra("poster", m.poster).putExtra("label", label))
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format("%d:%02d", s / 60, s % 60)
    }
}
