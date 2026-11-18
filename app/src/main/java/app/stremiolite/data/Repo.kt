package app.stremiolite.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import app.stremiolite.App
import app.stremiolite.account.StremioApi
import app.stremiolite.addon.Addon
import app.stremiolite.addon.AddonClient
import app.stremiolite.addon.Manifest
import app.stremiolite.addon.MetaPreview
import app.stremiolite.addon.Video
import app.stremiolite.addon.objects
import app.stremiolite.addon.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** O que o player precisa para tocar e gravar progresso. */
data class PlayInfo(
    val metaId: String,
    val type: String,
    val videoId: String,
    val name: String,
    val poster: String?,
)

/** Episodio guardado em disco (a ordem da lista e a mesma do campo `watched` do Stremio). */
data class EpisodeRef(val id: String, val season: Int, val episode: Int, val released: String?) {
    val label get() = "T$season E$episode"
}

/** Onde a serie deve continuar. */
data class ResumeTarget(val episode: EpisodeRef, val offsetMs: Long, val isNext: Boolean)

object Repo {
    /** `timeOffset` que o Stremio usa para "proximo episodio, ainda nao iniciado". */
    private const val NEXT_EPISODE_OFFSET = 1L
    private const val SYNC_EVERY_MS = 2 * 60_000L
    private const val MAX_SERIES = 40

    private lateinit var prefs: SharedPreferences
    private lateinit var dir: File

    val DEFAULT_ADDONS = listOf(
        "https://v3-cinemeta.strem.io/manifest.json",
        "https://opensubtitles-v3.strem.io/manifest.json",
    )

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("main", Context.MODE_PRIVATE)
        dir = ctx.filesDir
        loadLibraryFromDisk()
        loadSeriesFromDisk()
    }

    // ---------- conta ----------
    val authKey: String? get() = prefs.getString("authKey", null)
    val email: String? get() = prefs.getString("email", null)
    val loggedIn get() = authKey != null

    suspend fun onLoggedIn(authKey: String, email: String?) {
        prefs.edit().putString("authKey", authKey).putString("email", email ?: StremioApi.getUser(authKey)).apply()
        addonsCache = null
        addons(force = true)
        syncLibrary()
    }

    suspend fun logout() {
        authKey?.let { StremioApi.logout(it) }
        prefs.edit().remove("authKey").remove("email").apply()
        addonsCache = null
        library.clear()
        saveLibraryToDisk()
        series.values.forEach { it.positions.clear(); it.watched.clear() }
        saveSeriesToDisk()
    }

    // ---------- addons ----------
    /** URLs de manifest adicionadas a mao (valem com ou sem login). */
    var localAddonUrls: List<String>
        get() = runCatching { JSONArray(prefs.getString("localAddons", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
        set(v) { prefs.edit().putString("localAddons", JSONArray(v).toString()).apply(); addonsCache = null }

    @Volatile private var addonsCache: List<Addon>? = null
    private val addonsLock = Mutex()
    private val addonsFile get() = File(dir, "addons.json")

    /** Lista de addons: conta (se logado) ou padrao, mais os locais. Usa cache em disco para abrir rapido. */
    suspend fun addons(force: Boolean = false): List<Addon> = addonsLock.withLock {
        addonsCache?.takeIf { !force }?.let { return it }
        if (!force) {
            readAddonsDisk()?.let { addonsCache = it; refreshAddonsInBackground(); return it }
        }
        val fresh = fetchAddons()
        addonsCache = fresh
        fresh
    }

    private fun refreshAddonsInBackground() {
        App.scope.launch { runCatching { val a = fetchAddons(); addonsCache = a } }
    }

    private suspend fun fetchAddons(): List<Addon> = coroutineScope {
        val key = authKey
        val pairs = mutableListOf<Pair<String, JSONObject>>()
        if (key != null) {
            runCatching { pairs += StremioApi.addonCollection(key) }
                .onFailure { Log.w(App.TAG, "addonCollection: ${it.message}") }
        }
        val wanted = (if (key == null || pairs.isEmpty()) DEFAULT_ADDONS else emptyList()) + localAddonUrls
        pairs += wanted.filter { u -> pairs.none { it.first == u } }.map { u ->
            async { runCatching { AddonClient.fetchManifestRaw(u) }.onFailure { Log.w(App.TAG, "manifest $u: ${it.message}") }.getOrNull() }
        }.awaitAll().filterNotNull()
        withContext(Dispatchers.IO) {
            addonsFile.writeText(JSONArray(pairs.map { JSONObject().put("transportUrl", it.first).put("manifest", it.second) }).toString())
        }
        pairs.map { Addon(it.first, Manifest.parse(it.second)) }
    }

    private suspend fun readAddonsDisk(): List<Addon>? = withContext(Dispatchers.IO) {
        runCatching {
            if (!addonsFile.exists()) return@runCatching null
            JSONArray(addonsFile.readText()).objects().map { Addon(it.getString("transportUrl"), Manifest.parse(it.getJSONObject("manifest"))) }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    // ---------- biblioteca / progresso ----------
    private val library = LinkedHashMap<String, JSONObject>()
    private val libFile get() = File(dir, "library.json")

    private fun loadLibraryFromDisk() {
        runCatching {
            if (libFile.exists()) JSONArray(libFile.readText()).objects().forEach { library[it.optString("_id")] = it }
        }
    }

    private fun saveLibraryToDisk() {
        val snapshot = JSONArray(library.values.toList()).toString()
        App.scope.launch(Dispatchers.IO) { runCatching { libFile.writeText(snapshot) } }
    }

    /** Puxa a biblioteca da conta e mescla com a local pelo _mtime. */
    suspend fun syncLibrary() {
        val key = authKey ?: return
        lastSync = System.currentTimeMillis()
        val remote = runCatching { StremioApi.libraryAll(key) }
            .onFailure { Log.w(App.TAG, "datastoreGet: ${it.message}") }.getOrNull() ?: return
        val toPush = mutableListOf<JSONObject>()
        val merged = LinkedHashMap<String, JSONObject>()
        remote.forEach { merged[it.optString("_id")] = it }
        library.values.forEach { l ->
            val r = merged[l.optString("_id")]
            if (r == null || l.optString("_mtime") > r.optString("_mtime")) { merged[l.optString("_id")] = l; toPush += l }
        }
        library.clear(); library.putAll(merged)
        saveLibraryToDisk()
        if (toPush.isNotEmpty()) runCatching { StremioApi.libraryPut(key, toPush) }
    }

    private var lastSync = 0L

    /** Traz o progresso feito em outros aparelhos sem consultar a conta a cada tela. */
    suspend fun syncIfStale(): Boolean {
        if (!loggedIn || System.currentTimeMillis() - lastSync < SYNC_EVERY_MS) return false
        syncLibrary()
        return true
    }

    fun libraryItem(id: String): JSONObject? = library[id]

    fun continueWatching(): List<MetaPreview> = library.values.filter { it ->
        val st = it.optJSONObject("state") ?: return@filter false
        (!it.optBoolean("removed") || it.optBoolean("temp")) && st.optLong("timeOffset") > 0 && it.optString("type") != "other"
    }.sortedByDescending { it.optJSONObject("state")?.optString("lastWatched") ?: it.optString("_mtime") }
        .take(20)
        .map {
            val st = it.getJSONObject("state")
            val dur = st.optLong("duration")
            val vid = st.str("video_id")
            val ep = vid?.split(":")?.takeIf { p -> p.size >= 3 }?.let { p -> "T${p[p.size - 2]} E${p.last()}" }
            val isNext = st.optLong("timeOffset") <= NEXT_EPISODE_OFFSET
            MetaPreview(
                id = it.optString("_id"), type = it.optString("type"), name = it.optString("name"),
                poster = it.str("poster"),
                subtitle = if (isNext) ep?.let { e -> "Próximo: $e" } else ep,
                progress = if (dur > 0 && !isNext) (st.optLong("timeOffset").toFloat() / dur).coerceIn(0f, 1f) else 0f,
                videoId = vid,
                resume = true,
            )
        }

    // ---------- episodios ----------
    private class SeriesData(
        var videos: List<EpisodeRef> = emptyList(),
        val positions: MutableMap<String, Long> = HashMap(),
        val watched: MutableSet<String> = HashSet(),
        var sourceVideo: String? = null,
        var sourceKey: String? = null,
        var quality: String? = null,
        var touched: Long = 0,
    )

    private val series = HashMap<String, SeriesData>()
    private val seriesFile get() = File(dir, "series.json")

    private fun loadSeriesFromDisk() {
        runCatching {
            if (!seriesFile.exists()) return
            val root = JSONObject(seriesFile.readText())
            root.keys().forEach { id ->
                val j = root.getJSONObject(id)
                val videos = j.optJSONArray("v").objects().map {
                    EpisodeRef(it.getString("i"), it.optInt("s"), it.optInt("e"), it.str("r"))
                }
                val positions = HashMap<String, Long>()
                j.optJSONObject("p")?.let { p -> p.keys().forEach { k -> positions[k] = p.optLong(k) } }
                val watched = HashSet<String>()
                j.optJSONArray("w")?.let { w -> for (i in 0 until w.length()) watched += w.getString(i) }
                series[id] = SeriesData(videos, positions, watched, j.str("sv"), j.str("sk"), j.str("q"), j.optLong("t"))
            }
        }.onFailure { Log.w(App.TAG, "series.json: ${it.message}") }
    }

    private fun saveSeriesToDisk() {
        series.entries.sortedBy { it.value.touched }.take((series.size - MAX_SERIES).coerceAtLeast(0))
            .map { it.key }.forEach { series.remove(it) }
        val root = JSONObject()
        series.forEach { (id, s) ->
            root.put(id, JSONObject()
                .put("v", JSONArray(s.videos.map { e ->
                    JSONObject().put("i", e.id).put("s", e.season).put("e", e.episode).put("r", e.released ?: JSONObject.NULL)
                }))
                .put("p", JSONObject(s.positions as Map<*, *>))
                .put("w", JSONArray(s.watched))
                .put("sv", s.sourceVideo ?: JSONObject.NULL).put("sk", s.sourceKey ?: JSONObject.NULL)
                .put("q", s.quality ?: JSONObject.NULL).put("t", s.touched))
        }
        val snapshot = root.toString()
        App.scope.launch(Dispatchers.IO) { runCatching { seriesFile.writeText(snapshot) } }
    }

    private fun seriesData(metaId: String) = series.getOrPut(metaId) { SeriesData() }

    /** Guarda os episodios ao abrir a serie: e o que permite achar o proximo e marcar assistidos. */
    fun rememberVideos(metaId: String, videos: List<Video>) {
        val s = seriesData(metaId)
        s.videos = videos.sortedWith(compareBy({ it.season }, { it.episode }, { it.released.orEmpty() }))
            .map { EpisodeRef(it.id, it.season, it.episode, it.released) }
        s.touched = System.currentTimeMillis()
        saveSeriesToDisk()
    }

    fun episode(metaId: String, videoId: String): EpisodeRef? = series[metaId]?.videos?.firstOrNull { it.id == videoId }

    private fun bitfield(s: SeriesData, state: JSONObject?) =
        WatchedBitfield.parse(state?.str("watched"), s.videos.map { it.id })

    /** Episodios assistidos: os da conta mais os marcados neste aparelho. */
    fun watchedIds(metaId: String): Set<String> {
        val s = series[metaId] ?: return emptySet()
        return bitfield(s, library[metaId]?.optJSONObject("state")).watchedIds() + s.watched
    }

    private fun released(e: EpisodeRef) = e.released == null || e.released <= iso()

    private fun nextUnwatched(s: SeriesData, videoId: String, watched: Set<String>): EpisodeRef? {
        val i = s.videos.indexOfFirst { it.id == videoId }
        if (i < 0) return null
        return s.videos.drop(i + 1).firstOrNull { it.id !in watched && released(it) }
    }

    /** Episodio que o botao "Continuar" deve abrir, ou null se a serie terminou. */
    fun resumeTarget(metaId: String): ResumeTarget? {
        val s = series[metaId]?.takeIf { it.videos.isNotEmpty() } ?: return null
        val st = library[metaId]?.optJSONObject("state")
        val watched = watchedIds(metaId)
        val current = st?.str("video_id")?.let { id -> s.videos.firstOrNull { it.id == id } }
        if (st != null && current != null) {
            val offset = st.optLong("timeOffset")
            if (offset > NEXT_EPISODE_OFFSET) return ResumeTarget(current, offset, false)
            if (offset == NEXT_EPISODE_OFFSET) return ResumeTarget(current, 0, true)
            if (current.id in watched || st.optInt("flaggedWatched") == 1) {
                return nextUnwatched(s, current.id, watched)?.let { ResumeTarget(it, 0, true) }
            }
        }
        return s.videos.firstOrNull { it.season > 0 && it.id !in watched && released(it) }?.let { ResumeTarget(it, 0, false) }
    }

    /** Posicao salva (ms) para este video, ou 0. Cada episodio guarda a sua. */
    fun resumePosition(metaId: String, videoId: String): Long {
        val st = library[metaId]?.optJSONObject("state")
        if (st != null && (st.optString("video_id") == videoId || (videoId == metaId && st.str("video_id") == null))) {
            return st.optLong("timeOffset").takeIf { it > NEXT_EPISODE_OFFSET } ?: 0
        }
        return series[metaId]?.positions?.get(videoId) ?: 0
    }

    /** Video que acabou de terminar no player: a tela de fontes fecha e volta para a serie. */
    var endedVideoId: String? = null

    fun rememberSource(metaId: String, videoId: String, sourceKey: String, quality: String?) {
        val s = seriesData(metaId)
        s.sourceVideo = videoId
        s.sourceKey = sourceKey
        s.quality = quality
        s.touched = System.currentTimeMillis()
        saveSeriesToDisk()
    }

    /** Fonte usada antes neste video (se houver) e a qualidade escolhida por ultimo neste titulo. */
    fun preferredSource(metaId: String, videoId: String): Pair<String?, String?> {
        val s = series[metaId] ?: return null to null
        return (if (s.sourceVideo == videoId) s.sourceKey else null) to s.quality
    }

    private fun iso(t: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))

    private fun newItem(p: PlayInfo, now: String) = JSONObject()
        .put("_id", p.metaId).put("name", p.name).put("type", p.type)
        .put("poster", p.poster ?: JSONObject.NULL).put("posterShape", "poster")
        .put("removed", true).put("temp", true)
        .put("_ctime", now).put("_mtime", now)
        .put("behaviorHints", JSONObject().put("defaultVideoId", JSONObject.NULL).put("featuredVideoId", JSONObject.NULL).put("hasScheduledVideos", false))
        .put("state", JSONObject()
            .put("lastWatched", now).put("timeWatched", 0).put("timeOffset", 0).put("overallTimeWatched", 0)
            .put("timesWatched", 0).put("flaggedWatched", 0).put("duration", 0).put("video_id", JSONObject.NULL)
            .put("watched", JSONObject.NULL).put("noNotif", false))

    /**
     * Grava o progresso (local + conta). [watchedDeltaMs] = tempo assistido desde a ultima gravacao.
     * Ao passar de 92% marca o episodio como assistido e deixa a serie apontando para o proximo.
     */
    fun saveProgress(p: PlayInfo, posMs: Long, durMs: Long, watchedDeltaMs: Long) {
        if (durMs <= 0) return
        val now = iso()
        val finished = posMs > durMs * 0.92
        val ser = series[p.metaId]?.takeIf { s -> s.videos.any { it.id == p.videoId } }
        if (ser != null) {
            if (finished || posMs < 5_000) ser.positions.remove(p.videoId) else ser.positions[p.videoId] = posMs
            ser.touched = System.currentTimeMillis()
        }
        val item = library[p.metaId]?.let { JSONObject(it.toString()) } ?: newItem(p, now)
        val st = item.optJSONObject("state") ?: JSONObject().also { item.put("state", it) }
        val current = st.str("video_id")
        // Abrir outro episodio por engano nao tira a serie do ponto em que estava.
        if (ser != null && !finished && current != null && current != p.videoId && posMs < 60_000) {
            saveSeriesToDisk()
            return
        }
        val wasFinished = if (ser != null) p.videoId in watchedIds(p.metaId)
            else current == p.videoId && st.optLong("timeOffset") == 0L && st.optInt("flaggedWatched") == 1
        st.put("lastWatched", now)
        st.put("video_id", p.videoId)
        st.put("duration", durMs)
        st.put("timeOffset", if (finished) 0 else posMs)
        st.put("timeWatched", st.optLong("timeWatched") + watchedDeltaMs.coerceAtLeast(0))
        st.put("overallTimeWatched", st.optLong("overallTimeWatched") + watchedDeltaMs.coerceAtLeast(0))
        if (finished && !wasFinished) {
            st.put("timesWatched", st.optInt("timesWatched") + 1)
            st.put("flaggedWatched", 1)
        }
        if (finished && ser != null) {
            ser.watched += p.videoId
            val field = bitfield(ser, st)
            if (field.reliable) {
                ser.watched.forEach { field[it] = true }
                st.put("watched", field.serialize())
            } else Log.w(App.TAG, "campo watched nao reconhecido: mantido como esta")
            nextUnwatched(ser, p.videoId, field.watchedIds() + ser.watched)?.let { next ->
                st.put("video_id", next.id)
                st.put("timeOffset", NEXT_EPISODE_OFFSET)
                st.put("duration", 0)
            }
        }
        if (ser != null) saveSeriesToDisk()
        if (item.optBoolean("removed")) item.put("temp", true)
        if (item.isNull("poster") && p.poster != null) item.put("poster", p.poster)
        item.put("_mtime", now)
        library[p.metaId] = item
        saveLibraryToDisk()
        val key = authKey ?: return
        App.scope.launch {
            runCatching { StremioApi.libraryPut(key, listOf(item)) }
                .onFailure { Log.w(App.TAG, "datastorePut: ${it.message}") }
        }
    }
}
