package app.stremiolite.account

import app.stremiolite.addon.objects
import app.stremiolite.net.Http
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** API de conta do Stremio (api.strem.io), a mesma usada por clientes de terceiros. */
object StremioApi {
    private const val API = "https://api.strem.io/api/"
    private const val LINK = "https://link.stremio.com/api/v2/"

    class ApiError(msg: String) : IOException(msg)

    private suspend fun call(method: String, body: JSONObject): Any {
        val r = Http.postJson(API + method, body)
        r.optJSONObject("error")?.let { throw ApiError(it.optString("message", "erro da API")) }
        return r.opt("result") ?: throw ApiError("resposta vazia de $method")
    }

    data class LoginResult(val authKey: String, val email: String)

    suspend fun login(email: String, password: String): LoginResult {
        val r = call("login", JSONObject().put("type", "Login").put("email", email).put("password", password).put("facebook", false)) as JSONObject
        return LoginResult(r.getString("authKey"), r.optJSONObject("user")?.optString("email") ?: email)
    }

    suspend fun logout(authKey: String) {
        runCatching { call("logout", JSONObject().put("type", "Logout").put("authKey", authKey)) }
    }

    suspend fun getUser(authKey: String): String? = runCatching {
        (call("getUser", JSONObject().put("type", "GetUser").put("authKey", authKey)) as JSONObject).optString("email")
    }.getOrNull()

    /** Login por codigo: o usuario abre o link no celular/PC ja logado e o app recebe o authKey. */
    data class LinkCode(val code: String, val link: String)

    suspend fun linkCreate(): LinkCode {
        val r = Http.getJson(LINK + "create?type=Create").getJSONObject("result")
        return LinkCode(r.getString("code"), r.getString("link"))
    }

    suspend fun linkRead(code: String): String? {
        val r = Http.getJson(LINK + "read?type=Read&code=$code")
        return r.optJSONObject("result")?.optString("authKey")?.ifBlank { null }
    }

    /** Colecao de addons da conta: pares (transportUrl, manifest bruto). */
    suspend fun addonCollection(authKey: String): List<Pair<String, JSONObject>> {
        val r = call("addonCollectionGet", JSONObject().put("type", "AddonCollectionGet").put("authKey", authKey).put("update", true)) as JSONObject
        return r.optJSONArray("addons").objects().mapNotNull { a ->
            val m = a.optJSONObject("manifest") ?: return@mapNotNull null
            a.optString("transportUrl").ifBlank { null }?.let { it to m }
        }
    }

    suspend fun libraryAll(authKey: String): List<JSONObject> {
        val r = call("datastoreGet", JSONObject().put("authKey", authKey).put("collection", "libraryItem").put("ids", JSONArray()).put("all", true))
        return (r as? JSONArray).objects()
    }

    suspend fun libraryPut(authKey: String, items: List<JSONObject>) {
        call("datastorePut", JSONObject().put("authKey", authKey).put("collection", "libraryItem").put("changes", JSONArray(items)))
    }
}
