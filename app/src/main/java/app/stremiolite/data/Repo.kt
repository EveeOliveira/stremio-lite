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

object Repo {
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
            MetaPreview(
                id = it.optString("_id"), type = it.optString("type"), name = it.optString("name"),
                poster = it.str("poster"), subtitle = ep,
                progress = if (dur > 0) (st.optLong("timeOffset").toFloat() / dur).coerceIn(0f, 1f) else 0f,
                videoId = vid,
            )
        }

    /** Posicao salva (ms) para este video, ou 0. */
    fun resumePosition(metaId: String, videoId: String): Long {
        val st = library[metaId]?.optJSONObject("state") ?: return 0
        return if (st.optString("video_id") == videoId || (videoId == metaId && st.str("video_id") == null)) st.optLong("timeOffset") else 0
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
     * Ao passar de 92% marca como assistido e zera o offset (sai do "Continuar assistindo").
     */
    fun saveProgress(p: PlayInfo, posMs: Long, durMs: Long, watchedDeltaMs: Long) {
        if (durMs <= 0) return
        val now = iso()
        val item = library[p.metaId]?.let { JSONObject(it.toString()) } ?: newItem(p, now)
        val st = item.optJSONObject("state") ?: JSONObject().also { item.put("state", it) }
        val finished = posMs > durMs * 0.92
        val wasFinished = st.optString("video_id") == p.videoId && st.optLong("timeOffset") == 0L && st.optInt("flaggedWatched") == 1
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
