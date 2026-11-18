package app.stremiolite.remote

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import app.stremiolite.App
import app.stremiolite.ui.SearchActivity
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.lang.ref.WeakReference
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/**
 * Controle pelo celular: servidor HTTP minimo na rede local, ligado so com o app em primeiro plano.
 * A pagina e publica, mas todo comando exige o codigo de 6 digitos mostrado na tela da TV.
 */
object RemoteServer {
    private const val FIRST_PORT = 8766
    private const val MAX_FAILURES = 5
    private const val LOCK_MS = 60_000L
    private const val MAX_HEADER = 8 * 1024
    private const val MAX_BODY = 512

    private val KEYS = mapOf(
        "up" to KeyEvent.KEYCODE_DPAD_UP, "down" to KeyEvent.KEYCODE_DPAD_DOWN,
        "left" to KeyEvent.KEYCODE_DPAD_LEFT, "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "ok" to KeyEvent.KEYCODE_DPAD_CENTER, "back" to KeyEvent.KEYCODE_BACK,
        "playpause" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        "rewind" to KeyEvent.KEYCODE_MEDIA_REWIND, "forward" to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
    )

    private lateinit var app: Application
    private val prefs by lazy { app.getSharedPreferences("remote", Context.MODE_PRIVATE) }
    private val random = SecureRandom()
    private val slots = Semaphore(4)
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "remote").apply { isDaemon = true } }

    @Volatile private var socket: ServerSocket? = null
    @Volatile private var current: WeakReference<Activity>? = null
    private var started = 0
    private var failures = 0
    private var lockedUntil = 0L

    val port: Int? get() = socket?.localPort

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) {
            prefs.edit().putBoolean("enabled", v).apply()
            if (v && started > 0) start() else if (!v) stop()
        }

    /** Fica salvo para o celular continuar pareado; "Trocar código" desconecta os aparelhos. */
    val code: String
        get() = prefs.getString("code", null) ?: newCode()

    fun newCode(): String {
        val c = "%06d".format(random.nextInt(1_000_000))
        prefs.edit().putString("code", c).apply()
        return c
    }

    fun address(): String? {
        val p = port ?: return null
        val ip = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        }.getOrNull() ?: return null
        return "http://$ip:$p"
    }

    fun init(application: Application) {
        app = application
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { if (started++ == 0 && enabled) start() }
            override fun onActivityStopped(a: Activity) { if (--started == 0) stop() }
            override fun onActivityResumed(a: Activity) { current = WeakReference(a) }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    @Synchronized private fun start() {
        if (socket != null) return
        val server = (FIRST_PORT until FIRST_PORT + 5).firstNotNullOfOrNull { p -> runCatching { ServerSocket(p) }.getOrNull() }
        if (server == null) { Log.w(App.TAG, "controle: nenhuma porta livre"); return }
        socket = server
        pool.execute {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                if (!slots.tryAcquire()) { runCatching { client.close() }; continue }
                pool.execute {
                    try { client.use(::handle) } catch (e: Exception) { Log.d(App.TAG, "controle: ${e.message}") } finally { slots.release() }
                }
            }
        }
        Log.i(App.TAG, "controle ativo na porta ${server.localPort}")
    }

    @Synchronized private fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun isLocal(a: InetAddress) = a.isSiteLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    private fun handle(s: Socket) {
        s.soTimeout = 5_000
        if (!isLocal(s.inetAddress)) return
        val req = read(BufferedInputStream(s.getInputStream())) ?: return respond(s, 400, "text/plain", "pedido invalido")
        when {
            req.method == "GET" && req.path == "/" ->
                respond(s, 200, "text/html; charset=utf-8", app.assets.open("remote.html").use { it.readBytes().decodeToString() })
            req.method == "POST" && req.path.startsWith("/api/") -> api(s, req)
            else -> respond(s, 404, "text/plain", "nao encontrado")
        }
    }

    private fun read(input: InputStream): Request? {
        val head = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0 || head.size() >= MAX_HEADER) return null
            head.write(b)
            matched = when {
                b == "\r\n\r\n"[matched].code -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
        }
        val lines = head.toString("ISO-8859-1").split("\r\n")
        val first = lines[0].split(" ")
        if (first.size < 3) return null
        val headers = lines.drop(1).filter { ':' in it }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length !in 0..MAX_BODY) return null
        val body = input.readNBytesCompat(length) ?: return null
        return Request(first[0], first[1].substringBefore('?'), headers, body)
    }

    private fun InputStream.readNBytesCompat(n: Int): ByteArray? {
        val out = ByteArray(n)
        var done = 0
        while (done < n) {
            val r = read(out, done, n - done)
            if (r < 0) return null
            done += r
        }
        return out
    }

    private fun api(s: Socket, req: Request) {
        // Outro site aberto no celular nao pode mandar comandos.
        val origin = req.headers["origin"]
        if (origin != null && origin.substringAfter("://") != req.headers["host"]) return respond(s, 403, "text/plain", "origem negada")
        when (checkCode(req.headers["x-code"].orEmpty())) {
            Auth.LOCKED -> return respond(s, 429, "text/plain", "muitas tentativas")
            Auth.WRONG -> return respond(s, 401, "text/plain", "codigo incorreto")
            Auth.OK -> Unit
        }
        val text = req.body.decodeToString().trim()
        val activity = current?.get()
        when (req.path) {
            "/api/check" -> respond(s, 200, "text/plain", "ok")
            "/api/key" -> {
                val key = KEYS[text] ?: return respond(s, 400, "text/plain", "tecla desconhecida")
                if (activity == null || started == 0) return respond(s, 409, "text/plain", "app em segundo plano")
                sendKey(key, activity)
                respond(s, 200, "text/plain", "ok")
            }
            "/api/search" -> {
                if (text.length !in 2..100) return respond(s, 400, "text/plain", "busca invalida")
                if (activity == null || started == 0) return respond(s, 409, "text/plain", "app em segundo plano")
                activity.runOnUiThread {
                    activity.startActivity(Intent(activity, SearchActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(SearchActivity.EXTRA_QUERY, text))
                }
                respond(s, 200, "text/plain", "ok")
            }
            else -> respond(s, 404, "text/plain", "nao encontrado")
        }
    }

    /** Entrega a tecla pela janela da tela atual, como faria um teclado (nao exige permissao especial). */
    private fun sendKey(key: Int, activity: Activity) = activity.runOnUiThread {
        val input = BaseInputConnection(activity.window.decorView, true)
        val now = SystemClock.uptimeMillis()
        input.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0))
        input.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0))
    }

    private enum class Auth { OK, WRONG, LOCKED }

    @Synchronized private fun checkCode(given: String): Auth {
        val now = System.currentTimeMillis()
        if (now < lockedUntil) return Auth.LOCKED
        if (MessageDigest.isEqual(given.toByteArray(), code.toByteArray())) { failures = 0; return Auth.OK }
        if (++failures >= MAX_FAILURES) { failures = 0; lockedUntil = now + LOCK_MS }
        return Auth.WRONG
    }

    private fun respond(s: Socket, status: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        val head = "HTTP/1.1 $status X\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n" +
            "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'\r\n" +
            "Connection: close\r\n\r\n"
        s.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
    }
}
