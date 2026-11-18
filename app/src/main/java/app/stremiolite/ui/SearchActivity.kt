package app.stremiolite.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import app.stremiolite.R
import app.stremiolite.addon.AddonClient
import app.stremiolite.data.Repo
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

class SearchActivity : ComponentActivity() {
    companion object { const val EXTRA_QUERY = "query" }

    private lateinit var adapter: RowsAdapter
    private lateinit var status: TextView
    private lateinit var query: EditText
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        status = findViewById(R.id.status)
        query = findViewById(R.id.query)
        adapter = RowsAdapter { openDetail(it) }
        findViewById<RecyclerView>(R.id.rows).apply { tvSetup(); adapter = this@SearchActivity.adapter }
        findViewById<TextView>(R.id.btnGo).setOnClickListener { search() }
        query.onConfirm { search() }
        query.requestFocus()
        handleRemoteQuery(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRemoteQuery(intent)
    }

    /** Busca digitada no celular: nao abre o teclado da TV. */
    private fun handleRemoteQuery(i: Intent) {
        val q = i.getStringExtra(EXTRA_QUERY) ?: return
        i.removeExtra(EXTRA_QUERY)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        query.setText(q)
        query.setSelection(q.length)
        search()
    }

    private fun search() {
        val q = query.text.toString().trim()
        if (q.length < 2) return
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(query.windowToken, 0)
        job?.cancel()
        adapter.clear()
        status.text = "Buscando “$q”…"
        job = lifecycleScope.launch {
            val addons = Repo.addons()
            val cats = addons.flatMap { a -> a.manifest.catalogs.filter { it.supportsSearch }.map { a to it } }
            if (cats.isEmpty()) { status.text = "Nenhum addon com busca instalado."; return@launch }
            var found = 0
            cats.mapIndexed { i, (a, c) ->
                async {
                    runCatching { AddonClient.catalog(a, c, mapOf("search" to q)) }.getOrNull()?.let { metas ->
                        found += metas.size
                        val typeName = when (c.type) { "movie" -> "Filmes"; "series" -> "Séries"; else -> c.type }
                        adapter.put(Row("${a.transportUrl}|${c.type}|${c.id}", i, "$typeName · ${a.manifest.name}", metas))
                    }
                }
            }.awaitAll()
            status.text = if (found == 0) "Nada encontrado para “$q”." else ""
            findViewById<RecyclerView>(R.id.rows).post { findViewById<RecyclerView>(R.id.rows).requestFocus() }
        }
    }
}
