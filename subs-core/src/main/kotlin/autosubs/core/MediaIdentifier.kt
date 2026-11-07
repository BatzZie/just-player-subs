package autosubs.core

import java.util.Locale

/** Works out the IMDb id of what is playing: from the URL if it contains one, otherwise via Cinemeta. */
object MediaIdentifier {

    private const val CINEMETA = "https://v3-cinemeta.strem.io"
    private val IMDB_IN_URL = Regex("\\b(tt\\d{5,10})(?::(\\d{1,3}):(\\d{1,4}))?")
    private val YEAR = Regex("(\\d{4})")

    /** Some addons (e.g. StremThru) put "tt123:2:1" in the stream URL. */
    @JvmStatic
    fun fromUrl(url: String?, info: ReleaseInfo?): MediaId? {
        if (url == null) return null
        val m = IMDB_IN_URL.find(UrlCodec.decode(url)) ?: return null
        val id = m.groupValues[1]
        if (m.groupValues[2].isNotEmpty()) return MediaId(id, m.groupValues[2].toInt(), m.groupValues[3].toInt())
        if (info != null && info.isEpisode()) return MediaId(id, info.season, info.episode)
        return MediaId(id, -1, -1)
    }

    @JvmStatic
    fun lookup(info: ReleaseInfo?): MediaId? {
        val title = info?.title ?: return null
        val type = if (info.isEpisode()) "series" else "movie"
        var id = search(type, title, info.year)
        if (id == null && info.year > 0) {
            // A year can be part of the title, e.g. "Blade Runner 2049"
            id = search(type, title + " " + info.year, -1)
        }
        if (id == null) return null
        return if (info.isEpisode()) MediaId(id, info.season, info.episode) else MediaId(id, -1, -1)
    }

    private fun search(type: String, title: String, year: Int): String? {
        val url = "$CINEMETA/catalog/$type/top/search=${UrlCodec.encode(title)}.json"
        return try {
            val metas = JsonUtil.parse(Http.getString(url)).obj()?.get("metas").arr() ?: return null
            val wanted = normalize(title)
            var bestId: String? = null
            var bestScore = 0
            for (i in 0 until minOf(metas.size, 20)) {
                val meta = metas[i].obj() ?: continue
                val id = meta.str("imdb_id", meta.str("id"))
                if (!id.startsWith("tt")) continue
                val name = normalize(meta.str("name"))
                var score = when {
                    name == wanted -> 10
                    name.startsWith(wanted) || wanted.startsWith(name) -> 3
                    else -> continue
                }
                val metaYear = parseYear(meta.str("releaseInfo", meta.str("year")))
                if (year > 0 && metaYear > 0) {
                    val diff = Math.abs(metaYear - year)
                    if (diff == 0) score += 6 else if (diff == 1) score += 3 else if (type != "series") score -= 5
                }
                score -= i / 5 // slight preference for Cinemeta's own ordering (popularity)
                if (score > bestScore) {
                    bestScore = score
                    bestId = id
                }
            }
            CoreLog.i("Cinemeta $type '$title' ($year) -> $bestId")
            bestId
        } catch (e: Exception) {
            CoreLog.w("Cinemeta search failed: $e")
            null
        }
    }

    @JvmStatic
    fun normalize(s: String?): String =
        s?.lowercase(Locale.ROOT)?.replace("&", "and")?.replace(Regex("[^\\p{L}\\p{N}]+"), "") ?: ""

    private fun parseYear(s: String?): Int = s?.let { YEAR.find(it)?.groupValues?.get(1)?.toInt() } ?: -1
}
