package app.stremiolite.player

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import app.stremiolite.App
import app.stremiolite.R
import app.stremiolite.addon.AddonClient
import app.stremiolite.addon.Subtitle
import app.stremiolite.data.PlayInfo
import app.stremiolite.data.Repo
import app.stremiolite.net.Http
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_HEADERS = "headers"
        const val EXTRA_FILENAME = "filename"
        const val EXTRA_SIZE = "videoSize"
        private const val SAVE_EVERY_MS = 30_000L
    }

    private lateinit var view: PlayerView
    private lateinit var osd: TextView
    private var player: ExoPlayer? = null
    private var info: PlayInfo? = null
    private var subtitles: List<Subtitle>? = null
    private var startPos = 0L
    private var lastSavedPos = -1L
    private val handler = Handler(Looper.getMainLooper())
    private val hideOsd = Runnable { osd.visibility = View.GONE }
    private val saver = object : Runnable {
        override fun run() { saveProgress(); handler.postDelayed(this, SAVE_EVERY_MS) }
    }

    private val url: String? get() = intent.getStringExtra(EXTRA_URL) ?: intent.data?.toString()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        view = findViewById(R.id.playerView)
        osd = findViewById(R.id.osd)
        view.controllerShowTimeoutMs = 4000
        view.setShowSubtitleButton(true)

        val metaId = intent.getStringExtra("metaId")
        if (metaId != null) {
            info = PlayInfo(metaId, intent.getStringExtra("type") ?: "movie",
                intent.getStringExtra("videoId") ?: metaId, intent.getStringExtra("name") ?: "", intent.getStringExtra("poster"))
            startPos = Repo.resumePosition(metaId, info!!.videoId)
        }
        if (url == null) { finish(); return }
        Log.i(App.TAG, "ffmpeg disponivel=${FfmpegLibrary.isAvailable()} versao=${FfmpegLibrary.getVersion()} " +
            "ac3=${FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_AC3)} eac3=${FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_E_AC3)} " +
            "dts=${FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_DTS)} truehd=${FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_TRUEHD)}")
    }

    override fun onStart() {
        super.onStart()
        if (url == null) return
        val i = info
        if (subtitles == null && i != null) {
            showOsd("Carregando legendas…", 0)
            lifecycleScope.launch {
                subtitles = withTimeoutOrNull(5000) { fetchSubtitles(i) } ?: emptyList()
                if (!isFinishing && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) startPlayer()
            }
        } else startPlayer()
    }

    override fun onStop() {
        saveProgress()
        handler.removeCallbacks(saver)
        player?.let { startPos = it.currentPosition; it.release() }
        player = null
        super.onStop()
    }

    private suspend fun fetchSubtitles(i: PlayInfo): List<Subtitle> {
        val extra = buildMap {
            intent.getStringExtra(EXTRA_FILENAME)?.let { put("filename", it) }
            intent.getLongExtra(EXTRA_SIZE, 0L).takeIf { it > 0 }?.let { put("videoSize", it.toString()) }
        }
        val addons = Repo.addons().filter { it.manifest.supports("subtitles", i.type, i.videoId) }
        val all = addons.map { a ->
            lifecycleScope.async {
                runCatching { AddonClient.subtitles(a, i.type, i.videoId, extra) }
                    .recoverCatching { if (extra.isNotEmpty()) AddonClient.subtitles(a, i.type, i.videoId, emptyMap()) else throw it }
                    .getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
        // portugues do Brasil > portugues > ingles; no maximo 6 para nao pesar
        fun rank(l: String) = when (normLang(l)) { "pt-BR" -> 0; "pt" -> 1; "en" -> 2; else -> 9 }
        return all.filter { rank(it.lang) < 9 }.sortedBy { rank(it.lang) }
            .groupBy { normLang(it.lang) }.flatMap { it.value.take(2) }.take(6)
    }

    private fun normLang(l: String) = when (l.lowercase()) {
        "pob", "pt-br", "ptbr", "pt_br", "brazilian" -> "pt-BR"
        "por", "pt", "pt-pt", "portuguese" -> "pt"
        "eng", "en", "english" -> "en"
        else -> l.lowercase()
    }

    private fun startPlayer() {
        if (player != null) return
        val headers = (intent.getSerializableExtra(EXTRA_HEADERS) as? HashMap<*, *>)
            ?.entries?.associate { it.key.toString() to it.value.toString() } ?: emptyMap()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(25_000)
            .setDefaultRequestProperties(headers)

        // Buffer moderado para 2 GB de RAM: ate 50 s ou 64 MB, sem back-buffer.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 2_500, 5_000)
            .setTargetBufferBytes(64 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(0, false)
            .build()

        val selector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters()
                .setPreferredAudioLanguages("pt", "en")
                .setPreferredTextLanguages("pt-BR", "pt")
                .setMaxVideoSize(1920, 1080))
        }

        val p = ExoPlayer.Builder(this, SwAudioRenderersFactory(this))
            .setTrackSelector(selector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player = p
        view.player = p

        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e(App.TAG, "erro de reproducao", error)
                showOsd("Erro: ${error.errorCodeName}\n${error.cause?.message ?: error.message}", 0)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) { saveProgress(); finish() }
            }
            private var cuesLogged = 0
            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                if (cuesLogged < 3 && cueGroup.cues.isNotEmpty()) {
                    cuesLogged++
                    Log.i(App.TAG, "legenda exibida: ${cueGroup.cues.joinToString(" / ") { it.text.toString() }}")
                }
            }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                tracks.groups.filter { it.isSelected }.forEach { g ->
                    val f = g.mediaTrackGroup.getFormat(0)
                    Log.i(App.TAG, "faixa selecionada: tipo=${g.type} ${f.sampleMimeType} lang=${f.language} label=${f.label}")
                }
            }
        })
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioInputFormatChanged(t: AnalyticsListener.EventTime, format: Format, eval: DecoderReuseEvaluation?) {
                Log.i(App.TAG, "audio entrada: ${format.sampleMimeType} ${format.channelCount}ch ${format.sampleRate}Hz")
            }
            override fun onAudioDecoderInitialized(t: AnalyticsListener.EventTime, name: String, initMs: Long, durMs: Long) {
                Log.i(App.TAG, "decodificador de audio: $name")
                val f = p.audioFormat
                showOsd("Áudio: ${f?.sampleMimeType?.substringAfter('/')?.uppercase() ?: "?"} ${f?.channelCount ?: "?"}ch → $name", 5000)
            }
            override fun onDroppedVideoFrames(t: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                Log.i(App.TAG, "frames de video descartados: $droppedFrames em ${elapsedMs}ms")
            }
            override fun onVideoDecoderInitialized(t: AnalyticsListener.EventTime, name: String, initMs: Long, durMs: Long) {
                Log.i(App.TAG, "decodificador de video: $name")
            }
        })

        val subs = subtitles.orEmpty().mapIndexed { idx, s ->
            val u = s.url.lowercase()
            val mime = when {
                u.contains(".vtt") -> MimeTypes.TEXT_VTT
                u.contains(".ass") || u.contains(".ssa") -> MimeTypes.TEXT_SSA
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.url))
                .setMimeType(mime)
                .setLanguage(normLang(s.lang))
                .setLabel("${normLang(s.lang)} · ${s.addon} ${idx + 1}")
                .setId("addon-sub-$idx")
                .build()
        }
        val item = MediaItem.Builder().setUri(url).setSubtitleConfigurations(subs).build()
        Log.i(App.TAG, "iniciando em ${startPos}ms com ${subs.size} legenda(s) de addon")
        p.setMediaItem(item, startPos)
        p.prepare()
        p.playWhenReady = true
        lastSavedPos = startPos
        if (startPos > 5_000) showOsd("Retomando de ${fmt(startPos)}", 4000) else osd.visibility = View.GONE
        handler.postDelayed(saver, SAVE_EVERY_MS)
    }

    private fun saveProgress() {
        val p = player ?: return
        val i = info ?: return
        val dur = p.duration
        if (dur == C.TIME_UNSET || dur <= 0) return
        val pos = p.currentPosition
        val delta = if (lastSavedPos >= 0 && pos > lastSavedPos && pos - lastSavedPos < 120_000) pos - lastSavedPos else 0
        lastSavedPos = pos
        Repo.saveProgress(i, pos, dur, delta)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player
        if (p != null && event.action == KeyEvent.ACTION_DOWN && !view.isControllerFullyVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0)); showOsd("⏪ ${fmt(p.currentPosition)}", 1500); return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    p.seekTo(p.currentPosition + 10_000); showOsd("⏩ ${fmt(p.currentPosition)}", 1500); return true
                }
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && view.isControllerFullyVisible) {
            view.hideController(); return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        return view.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    private fun showOsd(text: String, ms: Long) {
        handler.removeCallbacks(hideOsd)
        osd.text = text
        osd.visibility = View.VISIBLE
        if (ms > 0) handler.postDelayed(hideOsd, ms)
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format("%d:%02d", s / 60, s % 60)
    }
}
