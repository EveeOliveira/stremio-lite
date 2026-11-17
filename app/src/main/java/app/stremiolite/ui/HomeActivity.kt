package app.stremiolite.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import app.stremiolite.App
import app.stremiolite.R
import app.stremiolite.addon.AddonClient
import app.stremiolite.data.Repo
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class HomeActivity : ComponentActivity() {
    private lateinit var adapter: RowsAdapter
    private lateinit var status: TextView
    private var loadJob: Job? = null
    private var loadedFor: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        status = findViewById(R.id.status)
        adapter = RowsAdapter { openDetail(it) }
        findViewById<RecyclerView>(R.id.rows).apply { tvSetup(); setItemViewCacheSize(4); adapter = this@HomeActivity.adapter }
        findViewById<TextView>(R.id.btnSearch).setOnClickListener { startActivity(Intent(this, SearchActivity::class.java)) }
        findViewById<TextView>(R.id.btnSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        if (Repo.loggedIn) lifecycleScope.launch { Repo.syncLibrary(); refreshContinue() }
    }

    override fun onResume() {
        super.onResume()
        refreshContinue()
        // recarrega catalogos se a conta/addons mudaram
        val key = "${Repo.authKey}|${Repo.localAddonUrls}"
        if (key != loadedFor) { loadedFor = key; loadCatalogs() }
    }

    private fun refreshContinue() {
        adapter.put(Row("cw", -1, "Continuar assistindo", Repo.continueWatching()))
    }

    private fun loadCatalogs() {
        loadJob?.cancel()
        adapter.clear()
        refreshContinue()
        status.text = "Carregando addons…"
        loadJob = lifecycleScope.launch {
            val addons = runCatching { Repo.addons() }.getOrElse { status.text = "Erro ao carregar addons: ${it.message}"; return@launch }
            val catalogs = addons.flatMap { a -> a.manifest.catalogs.filter { !it.requiresExtra }.map { a to it } }
            if (catalogs.isEmpty()) { status.text = "Nenhum catálogo. Adicione addons em \"Conta e addons\"."; return@launch }
            status.text = ""
            val gate = Semaphore(4) // poucas requisicoes simultaneas: CPU fraca
            catalogs.forEachIndexed { i, (addon, cat) ->
                launch {
                    gate.withPermit {
                        runCatching { AddonClient.catalog(addon, cat) }
                            .onSuccess { metas ->
                                val typeName = when (cat.type) { "movie" -> "Filmes"; "series" -> "Séries"; else -> cat.type }
                                adapter.put(Row("${addon.transportUrl}|${cat.type}|${cat.id}", i, "${cat.name} · $typeName", metas.take(40)))
                            }
                            .onFailure { Log.w(App.TAG, "catalogo ${cat.id}: ${it.message}") }
                    }
                }
            }
        }
    }
}
