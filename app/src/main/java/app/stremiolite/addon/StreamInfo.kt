package app.stremiolite.addon

import java.util.Locale

/** Informacoes extraidas do texto da fonte (nome/titulo/arquivo), no estilo do Torrentio. */
data class StreamInfo(
    val quality: String?,   // "4K", "1080p", "720p"...
    val debrid: String?,    // "RD+", "RD", "AD+"...
    val size: String?,      // "2.1 GB"
    val audio: String?,     // "AC3 5.1", "EAC3 Atmos"...
    val video: String?,     // "HEVC", "HDR"...
) {
    val qualityRank: Int get() = when (quality) {
        "4K" -> 2160; "1440p" -> 1440; "1080p" -> 1080; "720p" -> 720; "480p" -> 480; "360p" -> 360; else -> 0
    }

    companion object {
        private val Q = Regex("""(?i)\b(2160p|4k|uhd|1440p|1080p|720p|576p|480p|360p)\b""")
        private val DEBRID = Regex("""\[([A-Z]{2,3})(\+| download)?]""")
        private val SIZE = Regex("""(?i)(\d+(?:[.,]\d+)?)\s?(GB|MB|GiB|MiB)\b""")
        private val CH = Regex("""\b([257]\.[01])\b""")

        fun parse(name: String, title: String, filename: String?, videoSize: Long): StreamInfo {
            val all = "$name\n$title\n${filename.orEmpty()}"
            val up = all.uppercase(Locale.ROOT)

            val q = Q.find(all)?.value?.lowercase(Locale.ROOT)?.let {
                when (it) { "2160p", "4k", "uhd" -> "4K"; else -> it }
            }
            val d = DEBRID.find(name)?.let { m ->
                m.groupValues[1] + if (m.groupValues[2] == "+") "+" else ""
            }
            val size = SIZE.find(title)?.let { "${it.groupValues[1].replace(',', '.')} ${it.groupValues[2].uppercase(Locale.ROOT).replace("I", "")}" }
                ?: if (videoSize > 0) String.format(Locale.ROOT, "%.1f GB", videoSize / 1e9) else null

            val codec = when {
                "TRUEHD" in up -> "TrueHD"
                Regex("""DTS[-.]?(HD|X|MA)""").containsMatchIn(up) -> "DTS-HD"
                "DTS" in up -> "DTS"
                Regex("""\b(DDP|DD\+|EAC3|E-AC-3|E-AC3)""").containsMatchIn(up) -> "EAC3"
                Regex("""\b(DD|AC3|AC-3|DOLBY DIGITAL)\b""").containsMatchIn(up) || Regex("""\bDD[257]\.[01]""").containsMatchIn(up) -> "AC3"
                "OPUS" in up -> "Opus"
                "FLAC" in up -> "FLAC"
                "AAC" in up -> "AAC"
                else -> null
            }
            val atmos = if ("ATMOS" in up) " Atmos" else ""
            val ch = CH.find(all.replace("DDP", "DDP ").replace("DD", "DD "))?.value
            val audio = listOfNotNull(codec, ch).joinToString(" ").ifBlank { null }?.plus(atmos)
                ?: atmos.trim().ifBlank { null }

            val v = buildList {
                if (Regex("""\b(HEVC|X265|H\.?265)\b""").containsMatchIn(up)) add("HEVC")
                else if (Regex("""\b(AVC|X264|H\.?264)\b""").containsMatchIn(up)) add("H264")
                if (Regex("""\bAV1\b""").containsMatchIn(up)) add("AV1")
                if (Regex("""\b(DV|DOVI|DOLBY ?VISION)\b""").containsMatchIn(up)) add("DV")
                if (Regex("""\bHDR(10\+?)?\b""").containsMatchIn(up)) add("HDR")
            }.joinToString(" ").ifBlank { null }

            return StreamInfo(q, d, size, audio, v)
        }
    }
}
