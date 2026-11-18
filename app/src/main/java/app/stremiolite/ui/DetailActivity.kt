package app.stremiolite.ui

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
import app.stremiolite.data.Repo
import kotlinx.coroutines.launch

class DetailActivity : ComponentActivity() {
    private var meta: Meta? = null
    private lateinit var seasonsAdapter: SimpleAdapter<Int>
    private lateinit var episodesAdapter: SimpleAdapter<Video>
    private lateinit var episodes: RecyclerView
    private var selectedSeason = -1
    private var watched: Set<String> = emptySet()
    private var resumeVideoId: String? = null
    private var focusPlaced = false

    private val Meta.isSeries get() = videos.any { it.season > 0 || it.episode > 0 } && type != "movie"

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
        episodesAdapter = SimpleAdapter(R.layout.item_text, { v, ep, _ -> bindEpisode(v, ep) }) { ep, _ -> openEpisode(ep) }
        episodes = findViewById(R.id.episodes)
        findViewById<RecyclerView>(R.id.seasons).apply { tvSetup(horizontal = true); adapter = seasonsAdapter }
        episodes.apply { tvSetup(); adapter = episodesAdapter }

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
        val m = meta ?: return
        watched = Repo.watchedIds(m.id)
        updateButtons(m)
        episodesAdapter.notifyItemRangeChanged(0, episodesAdapter.itemCount)
    }

    private fun bindEpisode(v: View, ep: Video) {
        val m = meta ?: return
        val isWatched = ep.id in watched
        val position = Repo.resumePosition(m.id, ep.id)
        (v as TextView).apply {
            text = buildString {
                if (isWatched) append("✓ ")
                append("${ep.episode}. ${ep.title.ifBlank { "Episódio ${ep.episode}" }}")
                ep.released?.take(10)?.let { append("   ·  $it") }
                if (position > 0) append("   ▶ ${fmt(position)}")
            }
            setBackgroundResource(if (isWatched) R.drawable.row_watched_bg else R.drawable.row_bg)
            setTextColor(getColor(when {
                position > 0 -> R.color.in_progress
                isWatched -> R.color.watched
                else -> R.color.text
            }))
        }
    }

    private fun bind(m: Meta) {
        findViewById<TextView>(R.id.title).text = m.name
        findViewById<TextView>(R.id.info).text = m.info
        findViewById<TextView>(R.id.desc).text = m.description.orEmpty()
        findViewById<ImageView>(R.id.poster).loadUrl(m.poster, 150, 225)
        findViewById<ImageView>(R.id.bg).loadUrl(m.background, 320, 180)

        if (m.isSeries) {
            Repo.rememberVideos(m.id, m.videos)
            watched = Repo.watchedIds(m.id)
            val seasons = m.videos.map { it.season }.distinct().sortedWith(compareBy({ it == 0 }, { it }))
            seasonsAdapter.items = seasons
            val wanted = intent.getStringExtra("videoId") ?: Repo.resumeTarget(m.id)?.episode?.id
            showSeason(m.videos.firstOrNull { it.id == wanted }?.season ?: seasons.first(), wanted)
        }
        updateButtons(m)
    }

    private fun updateButtons(m: Meta) {
        val play = findViewById<TextView>(R.id.btnPlay)
        val resume = findViewById<TextView>(R.id.btnResume)
        if (!m.isSeries) {
            val videoId = m.videos.singleOrNull()?.id ?: m.id
            val position = Repo.resumePosition(m.id, videoId)
            play.visibility = View.VISIBLE
            play.text = if (position > 0) "Continuar (${fmt(position)})" else "Assistir"
            play.setOnClickListener { openStreams(videoId, m.name) }
            resume.visibility = View.GONE
            if (!focusPlaced) play.requestFocus()
            focusPlaced = true
            return
        }
        play.visibility = View.GONE
        val target = Repo.resumeTarget(m.id)
        val changed = target?.episode?.id != resumeVideoId
        resumeVideoId = target?.episode?.id
        val video = target?.let { t -> m.videos.firstOrNull { it.id == t.episode.id } }
        if (target == null || video == null) {
            resume.visibility = View.GONE
            if (!focusPlaced) episodes.post { episodes.getChildAt(0)?.requestFocus() }
            focusPlaced = true
            return
        }
        val name = target.episode.label
        resume.visibility = View.VISIBLE
        resume.text = when {
            target.offsetMs > 0 -> "Continuar $name (${fmt(target.offsetMs)})"
            target.isNext -> "Próximo: $name"
            else -> "Começar $name"
        }
        resume.setOnClickListener { openEpisode(video) }
        // So move o foco na abertura ou quando a serie avancou; senao voce fica onde estava na lista.
        if (!focusPlaced || changed) {
            if (focusPlaced) showSeason(video.season, video.id)
            resume.requestFocus()
        }
        focusPlaced = true
    }

    private fun showSeason(s: Int, scrollTo: String? = resumeVideoId) {
        val m = meta ?: return
        selectedSeason = s
        seasonsAdapter.notifyDataSetChanged()
        val list = m.videos.filter { it.season == s }.sortedBy { it.episode }
        episodesAdapter.items = list
        val index = list.indexOfFirst { it.id == scrollTo }.coerceAtLeast(0)
        (episodes.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index, 0)
    }

    private fun openEpisode(ep: Video) = openStreams(ep.id, "T${ep.season} E${ep.episode} · ${ep.title}")

    private fun openStreams(videoId: String, label: String) {
        val m = meta ?: return
        startActivity(streamsIntent(m.type, m.id, videoId, m.name, m.poster, label))
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format("%d:%02d", s / 60, s % 60)
    }
}
