package app.stremiolite.addon

import android.util.Log
import app.stremiolite.App
import app.stremiolite.net.Http
import java.net.URLEncoder

/**
 * Cliente do protocolo de addons: GET {base}/{resource}/{type}/{id}[/{extra}].json
 * (resource = catalog | meta | stream | subtitles).
 */
object AddonClient {

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun normalizeManifestUrl(input: String): String {
        var u = input.trim()
        if (u.startsWith("stremio://")) u = "https://" + u.removePrefix("stremio://")
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        if (!u.substringBefore("?").endsWith("/manifest.json")) u = u.trimEnd('/') + "/manifest.json"
        return u
    }

    /** Baixa o manifest; devolve (url normalizada, JSON bruto). */
    suspend fun fetchManifestRaw(url: String): Pair<String, org.json.JSONObject> {
        val norm = normalizeManifestUrl(url)
        return norm to Http.getJson(norm)
    }

    private fun url(addon: Addon, resource: String, type: String, id: String, extra: Map<String, String> = emptyMap()): String {
        val extraPath = if (extra.isEmpty()) "" else "/" + extra.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        return "${addon.base}/$resource/${enc(type)}/${enc(id)}$extraPath.json"
    }

    suspend fun catalog(addon: Addon, cat: CatalogDef, extra: Map<String, String> = emptyMap()): List<MetaPreview> {
        val j = Http.getJson(url(addon, "catalog", cat.type, cat.id, extra))
        return j.optJSONArray("metas").objects().map { MetaPreview.parse(it, cat.type) }.filter { it.id.isNotEmpty() }
    }

    /** Busca o meta no primeiro addon que suporta o tipo/prefixo e responde. */
    suspend fun meta(addons: List<Addon>, type: String, id: String): Meta? {
        for (a in addons.filter { it.manifest.supports("meta", type, id) }) {
            try {
                val m = Http.getJson(url(a, "meta", type, id)).optJSONObject("meta") ?: continue
                return Meta.parse(m)
            } catch (e: Exception) {
                Log.w(App.TAG, "meta falhou em ${a.manifest.name}: ${e.message}")
            }
        }
        return null
    }

    suspend fun streams(addon: Addon, type: String, videoId: String): List<Stream> {
        val j = Http.getJson(url(addon, "stream", type, videoId))
        return j.optJSONArray("streams").objects().map { Stream.parse(addon.manifest.name, it) }
    }

    suspend fun subtitles(addon: Addon, type: String, videoId: String, extra: Map<String, String>): List<Subtitle> {
        val j = Http.getJson(url(addon, "subtitles", type, videoId, extra))
        return j.optJSONArray("subtitles").objects().mapNotNull { s ->
            val u = s.str("url") ?: return@mapNotNull null
            Subtitle(s.optString("id", u), u, s.optString("lang", "und"), addon.manifest.name)
        }
    }
}
