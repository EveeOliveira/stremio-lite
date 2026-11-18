package app.stremiolite.data

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Campo `state.watched` da biblioteca do Stremio: `{videoId ancora}:{tamanho ancora}:{base64(zlib(bits))}`.
 * Cada bit e um video, na ordem temporada > episodio > lancamento. A ancora permite deslocar os bits quando
 * entram episodios novos no comeco da lista.
 *
 * [reliable] = false quando o valor existente nao pode ser interpretado com esta lista de videos. Nesse caso
 * ele nao deve ser sobrescrito, para nao apagar o historico da conta.
 */
class WatchedBitfield private constructor(
    private val videoIds: List<String>,
    private var values: ByteArray,
    val reliable: Boolean,
) {
    private val index = videoIds.withIndex().associate { it.value to it.index }

    operator fun get(videoId: String): Boolean = index[videoId]?.let { bit(values, it) } ?: false

    operator fun set(videoId: String, watched: Boolean) {
        val i = index[videoId] ?: return
        if (i / 8 >= values.size) values = values.copyOf(i / 8 + 1)
        val mask = 1 shl (i % 8)
        val old = values[i / 8].toInt()
        values[i / 8] = (if (watched) old or mask else old and mask.inv()).toByte()
    }

    fun watchedIds(): Set<String> = videoIds.filterTo(LinkedHashSet()) { get(it) }

    fun serialize(): String {
        val last = videoIds.indices.lastOrNull { bit(values, it) } ?: 0
        return "${videoIds.getOrNull(last) ?: "undefined"}:${last + 1}:${encode(values)}"
    }

    companion object {
        fun parse(serialized: String?, videoIds: List<String>): WatchedBitfield {
            val size = (videoIds.size + 7) / 8
            fun blank(reliable: Boolean) = WatchedBitfield(videoIds, ByteArray(size), reliable)
            if (serialized.isNullOrBlank()) return blank(true)
            val parts = serialized.split(":")
            if (parts.size < 3) return blank(false)
            val anchorLength = parts[parts.size - 2].toIntOrNull() ?: return blank(false)
            val anchor = parts.dropLast(2).joinToString(":")
            val old = runCatching { decode(parts.last()) }.getOrNull() ?: return blank(false)
            val anchorIndex = videoIds.indexOf(anchor)
            if (anchorIndex < 0) return blank(old.all { it.toInt() == 0 })

            val offset = anchorLength - anchorIndex - 1
            if (offset == 0) return WatchedBitfield(videoIds, old.copyOf(maxOf(size, old.size)), true)
            val moved = ByteArray(size)
            for (i in videoIds.indices) {
                val previous = i + offset
                if (previous in videoIds.indices && bit(old, previous)) {
                    moved[i / 8] = (moved[i / 8].toInt() or (1 shl (i % 8))).toByte()
                }
            }
            return WatchedBitfield(videoIds, moved, true)
        }

        private fun bit(bytes: ByteArray, i: Int): Boolean =
            i / 8 < bytes.size && (bytes[i / 8].toInt() shr (i % 8)) and 1 != 0

        private fun decode(text: String): ByteArray {
            val inflater = Inflater()
            try {
                inflater.setInput(Base64.getDecoder().decode(text))
                val out = ByteArrayOutputStream()
                val buf = ByteArray(512)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buf)
                    check(n > 0 || inflater.finished() || !(inflater.needsInput() || inflater.needsDictionary())) { "zlib incompleto" }
                    out.write(buf, 0, n)
                    check(out.size() <= 64 * 1024) { "bitfield grande demais" }
                }
                return out.toByteArray()
            } finally {
                inflater.end()
            }
        }

        private fun encode(bytes: ByteArray): String {
            val deflater = Deflater(6)
            try {
                deflater.setInput(bytes)
                deflater.finish()
                val out = ByteArrayOutputStream()
                val buf = ByteArray(512)
                while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
                return Base64.getEncoder().encodeToString(out.toByteArray())
            } finally {
                deflater.end()
            }
        }
    }
}
