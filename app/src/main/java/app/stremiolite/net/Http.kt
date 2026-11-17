package app.stremiolite.net

import app.stremiolite.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    const val USER_AGENT = "StremioLite/0.1 (Android TV)"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .cache(Cache(File(App.instance.cacheDir, "http"), 8L * 1024 * 1024))
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .build()
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} em $url")
            JSONObject(r.body?.string().orEmpty())
        }
    }

    suspend fun postJson(url: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).post(body.toString().toRequestBody(JSON)).build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful && text.isEmpty()) throw IOException("HTTP ${r.code} em $url")
            JSONObject(text)
        }
    }
}
