package app.stremiolite.addon

import org.json.JSONArray
import org.json.JSONObject

/* Modelos do protocolo publico de addons do Stremio (stremio-addon-sdk/docs). */

fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it, "").ifBlank { null } }

fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONObject.str(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key, "").ifBlank { null }

data class ExtraDef(val name: String, val isRequired: Boolean)

data class CatalogDef(val type: String, val id: String, val name: String, val extras: List<ExtraDef>) {
    val supportsSearch get() = extras.any { it.name == "search" }
    val requiresExtra get() = extras.any { it.isRequired }
}

data class ResourceDef(val name: String, val types: List<String>?, val idPrefixes: List<String>?)

data class Manifest(
    val id: String,
    val name: String,
    val version: String,
    val types: List<String>,
    val idPrefixes: List<String>?,
    val resources: List<ResourceDef>,
    val catalogs: List<CatalogDef>,
) {
    fun supports(resource: String, type: String, id: String): Boolean {
        val r = resources.firstOrNull { it.name == resource } ?: return false
        val types = r.types ?: this.types
        val prefixes = r.idPrefixes ?: this.idPrefixes
        if (type !in types) return false
        return prefixes.isNullOrEmpty() || prefixes.any { id.startsWith(it) }
    }

    companion object {
        fun parse(j: JSONObject): Manifest {
            val resources = j.optJSONArray("resources")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    when (val r = arr.get(i)) {
                        is String -> ResourceDef(r, null, null)
                        is JSONObject -> ResourceDef(
                            r.optString("name"),
                            r.optJSONArray("types")?.strings(),
                            r.optJSONArray("idPrefixes")?.strings(),
                        )
                        else -> null
                    }
                }
            } ?: emptyList()
            val catalogs = j.optJSONArray("catalogs").objects().map { c ->
                val extras = mutableListOf<ExtraDef>()
                c.optJSONArray("extra").objects().forEach {
                    extras += ExtraDef(it.optString("name"), it.optBoolean("isRequired", false))
                }
                // formato antigo: extraSupported / extraRequired
                val req = c.optJSONArray("extraRequired").strings()
                c.optJSONArray("extraSupported").strings().forEach { n ->
                    if (extras.none { it.name == n }) extras += ExtraDef(n, n in req)
                }
                req.forEach { n -> if (extras.none { it.name == n }) extras += ExtraDef(n, true) }
                CatalogDef(c.optString("type"), c.optString("id"), c.str("name") ?: c.optString("id"), extras)
            }
            return Manifest(
                id = j.optString("id"),
                name = j.str("name") ?: j.optString("id"),
                version = j.optString("version"),
                types = j.optJSONArray("types").strings(),
                idPrefixes = j.optJSONArray("idPrefixes")?.strings(),
                resources = resources,
                catalogs = catalogs,
            )
        }
    }
}

data class Addon(val transportUrl: String, val manifest: Manifest) {
    /** URL base: transportUrl sem "/manifest.json" (preserva a configuracao embutida no caminho). */
    val base: String get() = transportUrl.substringBefore("?").removeSuffix("/manifest.json").removeSuffix("/")
}

data class MetaPreview(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val subtitle: String? = null,
    val progress: Float = 0f,
    val videoId: String? = null,
    /** Veio de "Continuar assistindo": abre direto nas fontes do episodio. */
    val resume: Boolean = false,
) {
    companion object {
        fun parse(j: JSONObject, fallbackType: String) = MetaPreview(
            id = j.optString("id"),
            type = j.str("type") ?: fallbackType,
            name = j.str("name") ?: "",
            poster = j.str("poster"),
            subtitle = j.str("releaseInfo"),
        )
    }
}

data class Video(
    val id: String,
    val title: String,
    val season: Int,
    val episode: Int,
    val released: String?,
)

data class Meta(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val background: String?,
    val description: String?,
    val info: String,
    val videos: List<Video>,
) {
    companion object {
        fun parse(j: JSONObject): Meta {
            val videos = j.optJSONArray("videos").objects().map { v ->
                Video(
                    id = v.optString("id"),
                    title = v.str("title") ?: v.str("name") ?: "",
                    season = v.optInt("season", 0),
                    episode = v.optInt("episode", v.optInt("number", 0)),
                    released = v.str("released"),
                )
            }
            val info = listOfNotNull(
                j.str("releaseInfo") ?: j.str("year"),
                j.str("runtime"),
                j.str("imdbRating")?.let { "IMDb $it" },
                j.optJSONArray("genres").strings().take(3).joinToString(", ").ifBlank { null },
            ).joinToString("  ·  ")
            return Meta(
                id = j.optString("id"),
                type = j.optString("type"),
                name = j.str("name") ?: "",
                poster = j.str("poster"),
                background = j.str("background"),
                description = j.str("description"),
                info = info,
                videos = videos,
            )
        }
    }
}

data class Stream(
    val addonName: String,
    val url: String?,
    val name: String,
    val title: String,
    val headers: Map<String, String>,
    val filename: String?,
    val videoSize: Long,
    val info: StreamInfo,
) {
    val playable get() = url != null && (url.startsWith("http://") || url.startsWith("https://"))

    /** Identifica a mesma fonte entre aberturas (a URL do debrid muda). */
    val sourceKey get() = filename ?: title.lineSequence().firstOrNull().orEmpty().ifBlank { name }

    companion object {
        fun parse(addon: String, j: JSONObject): Stream {
            val hints = j.optJSONObject("behaviorHints")
            val headers = mutableMapOf<String, String>()
            hints?.optJSONObject("proxyHeaders")?.optJSONObject("request")?.let { h ->
                h.keys().forEach { k -> headers[k] = h.optString(k) }
            }
            val name = j.str("name") ?: addon
            val title = j.str("description") ?: j.str("title") ?: ""
            val filename = hints?.str("filename")
            val size = hints?.optLong("videoSize", 0L) ?: 0L
            return Stream(
                addonName = addon,
                url = j.str("url"),
                name = name,
                title = title,
                headers = headers,
                filename = filename,
                videoSize = size,
                info = StreamInfo.parse(name, title, filename, size),
            )
        }
    }
}

data class Subtitle(val id: String, val url: String, val lang: String, val addon: String)
