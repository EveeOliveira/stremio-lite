package app.stremiolite.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.stremiolite.App
import app.stremiolite.R
import app.stremiolite.addon.MetaPreview
import coil.request.Disposable
import coil.request.ImageRequest

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

/** Carrega imagem redimensionada para o tamanho exibido (economiza RAM). */
fun ImageView.loadUrl(url: String?, wDp: Int, hDp: Int) {
    (getTag(R.id.tag_image) as? Disposable)?.dispose()
    setImageDrawable(null)
    if (url.isNullOrBlank()) return
    val req = ImageRequest.Builder(context)
        .data(url)
        .size(context.dp(wDp), context.dp(hDp))
        .target(this)
        .build()
    setTag(R.id.tag_image, App.instance.images.enqueue(req))
}

/** Sem animacoes: RecyclerView sem item animator. */
fun RecyclerView.tvSetup(horizontal: Boolean = false) {
    itemAnimator = null
    layoutManager = LinearLayoutManager(context, if (horizontal) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL, false)
}

// ---------- cards de poster ----------

class PosterAdapter(private val onClick: (MetaPreview) -> Unit) : RecyclerView.Adapter<PosterAdapter.VH>() {
    var items: List<MetaPreview> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    init { setHasStableIds(true) }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.poster)
        val name: TextView = v.findViewById(R.id.name)
        val sub: TextView = v.findViewById(R.id.sub)
        val progress: ProgressBar = v.findViewById(R.id.progress)
    }

    override fun getItemId(position: Int) = (items[position].id + items[position].type).hashCode().toLong()
    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_poster, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val m = items[position]
        h.name.text = m.name
        h.sub.text = m.subtitle.orEmpty()
        h.sub.visibility = if (m.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        h.progress.visibility = if (m.progress > 0f) View.VISIBLE else View.GONE
        h.progress.progress = (m.progress * 1000).toInt()
        h.img.loadUrl(m.poster, 112, 168)
        h.itemView.setOnClickListener { onClick(m) }
    }

    override fun onViewRecycled(h: VH) {
        (h.img.getTag(R.id.tag_image) as? Disposable)?.dispose()
        h.img.setImageDrawable(null)
    }
}

data class Row(val key: String, val order: Int, val title: String, val items: List<MetaPreview>)

/** Lista vertical de linhas, cada uma com cards horizontais. */
class RowsAdapter(private val onClick: (MetaPreview) -> Unit) : RecyclerView.Adapter<RowsAdapter.VH>() {
    private val rows = mutableListOf<Row>()
    private val pool = RecyclerView.RecycledViewPool().apply { setMaxRecycledViews(0, 40) }

    init { setHasStableIds(true) }

    /** Insere ou atualiza uma linha mantendo a ordem (para carregamento progressivo). */
    fun put(row: Row) {
        val i = rows.indexOfFirst { it.key == row.key }
        if (row.items.isEmpty()) { if (i >= 0) { rows.removeAt(i); notifyItemRemoved(i) }; return }
        if (i >= 0) { rows[i] = row; notifyItemChanged(i); return }
        val pos = rows.indexOfFirst { it.order > row.order }.let { if (it < 0) rows.size else it }
        rows.add(pos, row)
        notifyItemInserted(pos)
    }

    fun clear() { rows.clear(); notifyDataSetChanged() }

    class VH(v: View, pool: RecyclerView.RecycledViewPool, onClick: (MetaPreview) -> Unit) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.rowTitle)
        val list: RecyclerView = v.findViewById(R.id.rowList)
        val adapter = PosterAdapter(onClick)
        init {
            list.tvSetup(horizontal = true)
            list.setRecycledViewPool(pool)
            list.setHasFixedSize(true)
            list.adapter = adapter
        }
    }

    override fun getItemId(position: Int) = rows[position].key.hashCode().toLong()
    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false), pool, onClick)

    override fun onBindViewHolder(h: VH, position: Int) {
        h.title.text = rows[position].title
        h.adapter.items = rows[position].items
    }
}

/** Adaptador generico de lista simples. */
class SimpleAdapter<T>(
    private val layout: Int,
    private val bind: (View, T, Int) -> Unit,
    private val onClick: (T, Int) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var items: List<T> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(layout, parent, false)) {}

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        bind(h.itemView, item, position)
        h.itemView.setOnClickListener { onClick(item, h.bindingAdapterPosition) }
    }
}

/** Confirma com a tecla "Concluido/Buscar" do teclado da TV ou com Enter do controle. */
fun android.widget.EditText.onConfirm(action: () -> Unit) {
    setOnEditorActionListener { _, id, ev ->
        val enter = id == android.view.inputmethod.EditorInfo.IME_NULL && ev?.action == android.view.KeyEvent.ACTION_DOWN
        if (enter || id == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH || id == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
            id == android.view.inputmethod.EditorInfo.IME_ACTION_GO) { action(); true } else id == android.view.inputmethod.EditorInfo.IME_NULL
    }
}

fun Context.openDetail(m: MetaPreview) {
    startActivity(android.content.Intent(this, DetailActivity::class.java)
        .putExtra("type", m.type).putExtra("id", m.id).putExtra("videoId", m.videoId))
}
