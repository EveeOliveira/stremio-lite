package app.stremiolite.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.stremiolite.R
import app.stremiolite.account.StremioApi
import app.stremiolite.addon.AddonClient
import app.stremiolite.data.Repo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    private lateinit var status: TextView
    private var linkJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        status = findViewById(R.id.status)

        findViewById<TextView>(R.id.btnLinkLogin).setOnClickListener { startLinkLogin() }
        findViewById<TextView>(R.id.btnEmailLogin).setOnClickListener {
            findViewById<View>(R.id.loginForm).visibility = View.VISIBLE
            findViewById<EditText>(R.id.email).requestFocus()
        }
        findViewById<TextView>(R.id.btnDoLogin).setOnClickListener { emailLogin() }
        findViewById<TextView>(R.id.btnLogout).setOnClickListener {
            lifecycleScope.launch { Repo.logout(); bindAccount(); reloadAddons() }
        }
        findViewById<TextView>(R.id.btnAddAddon).setOnClickListener { addAddon() }
        findViewById<EditText>(R.id.addonUrl).onConfirm { addAddon() }
        findViewById<EditText>(R.id.password).onConfirm { emailLogin() }
        findViewById<TextView>(R.id.btnReload).setOnClickListener {
            lifecycleScope.launch { Repo.syncLibrary(); reloadAddons(force = true) }
        }
        bindAccount()
        lifecycleScope.launch { reloadAddons() }
        findViewById<TextView>(R.id.btnLinkLogin).requestFocus()
    }

    override fun onDestroy() {
        linkJob?.cancel()
        super.onDestroy()
    }

    private fun bindAccount() {
        findViewById<TextView>(R.id.accountStatus).text =
            if (Repo.loggedIn) "Conectado como ${Repo.email ?: "(conta Stremio)"} — addons, biblioteca e progresso sincronizados."
            else "Sem login: usando Cinemeta + OpenSubtitles + addons adicionados abaixo. Progresso fica só no aparelho."
        findViewById<View>(R.id.btnLogout).visibility = if (Repo.loggedIn) View.VISIBLE else View.GONE
    }

    private fun startLinkLogin() {
        linkJob?.cancel()
        val info = findViewById<TextView>(R.id.linkInfo)
        info.visibility = View.VISIBLE
        info.text = "Gerando código…"
        linkJob = lifecycleScope.launch {
            val code = runCatching { StremioApi.linkCreate() }.getOrElse { info.text = "Falha ao gerar código: ${it.message}"; return@launch }
            info.text = "No celular ou PC (logado no Stremio), abra:\n${code.link}\nCódigo: ${code.code}\nAguardando confirmação…"
            repeat(150) { // ~5 minutos
                delay(2000)
                val key = runCatching { StremioApi.linkRead(code.code) }.getOrNull()
                if (key != null) {
                    info.text = "Conectado! Carregando conta…"
                    Repo.onLoggedIn(key, null)
                    info.visibility = View.GONE
                    bindAccount(); reloadAddons()
                    return@launch
                }
            }
            info.text = "Código expirou. Tente de novo."
        }
    }

    private fun emailLogin() {
        val email = findViewById<EditText>(R.id.email).text.toString().trim()
        val pass = findViewById<EditText>(R.id.password).text.toString()
        if (email.isEmpty() || pass.isEmpty()) return
        status.text = "Entrando…"
        lifecycleScope.launch {
            runCatching { StremioApi.login(email, pass) }
                .onSuccess {
                    findViewById<EditText>(R.id.password).setText("")
                    findViewById<View>(R.id.loginForm).visibility = View.GONE
                    status.text = "Carregando conta…"
                    Repo.onLoggedIn(it.authKey, it.email)
                    bindAccount(); reloadAddons()
                    status.text = "Login feito."
                }
                .onFailure { status.text = "Falha no login: ${it.message}" }
        }
    }

    private fun addAddon() {
        val field = findViewById<EditText>(R.id.addonUrl)
        val raw = field.text.toString().trim()
        if (raw.isEmpty()) return
        status.text = "Verificando addon…"
        lifecycleScope.launch {
            runCatching { AddonClient.fetchManifestRaw(raw) }
                .onSuccess { (url, _) ->
                    if (url !in Repo.localAddonUrls) Repo.localAddonUrls = Repo.localAddonUrls + url
                    field.setText("")
                    status.text = "Addon adicionado."
                    reloadAddons(force = true)
                }
                .onFailure { status.text = "Não consegui ler o manifest: ${it.message}" }
        }
    }

    private suspend fun reloadAddons(force: Boolean = false) {
        val box = findViewById<LinearLayout>(R.id.addonList)
        if (force) status.text = "Recarregando…"
        val addons = runCatching { Repo.addons(force) }.getOrElse { status.text = "Erro: ${it.message}"; return }
        if (force) status.text = "${addons.size} addons carregados."
        box.removeAllViews()
        val local = Repo.localAddonUrls
        addons.forEach { a ->
            val isLocal = a.transportUrl in local
            val tv = layoutInflater.inflate(R.layout.item_text, box, false) as TextView
            val res = a.manifest.resources.joinToString(", ") { it.name }
            // nao mostra o caminho da URL (pode conter chave do Real-Debrid)
            tv.text = "${a.manifest.name}  v${a.manifest.version}   ·  $res   ·  ${android.net.Uri.parse(a.transportUrl).host}" +
                if (isLocal) "   (local)" else if (Repo.loggedIn) "   (conta)" else "   (padrão)"
            tv.setOnClickListener {
                if (!isLocal) { status.text = "Addons da conta são gerenciados no app/site do Stremio."; return@setOnClickListener }
                AlertDialog.Builder(this).setTitle("Remover ${a.manifest.name}?")
                    .setPositiveButton("Remover") { _, _ ->
                        Repo.localAddonUrls = Repo.localAddonUrls - a.transportUrl
                        lifecycleScope.launch { reloadAddons(force = true) }
                    }
                    .setNegativeButton("Cancelar", null).show()
            }
            box.addView(tv)
        }
    }
}
