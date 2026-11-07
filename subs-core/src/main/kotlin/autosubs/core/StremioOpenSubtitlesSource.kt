package autosubs.core

import java.io.IOException

/** The public OpenSubtitles v3 Stremio addon. No API key needed; downloads are already converted to UTF-8. */
class StremioOpenSubtitlesSource : SubtitleSource {

    override fun name(): String = "OpenSubtitles"

    override fun download(candidate: SubtitleCandidate): ByteArray = Http.getBytes(candidate.url, 4 * 1024 * 1024)

    override fun search(id: MediaId, hash: String?, size: Long, fileName: String?, languages: List<String>): List<SubtitleCandidate> {
        val extra = StringBuilder()
        fun append(key: String, value: String) {
            if (extra.isNotEmpty()) extra.append('&')
            extra.append(key).append('=').append(UrlCodec.encode(value))
        }
        if (fileName != null) append("filename", fileName)
        if (hash != null && size > 0) {
            append("videoHash", hash)
            append("videoSize", size.toString())
        }
        val url = BASE + "/subtitles/" + id.type() + "/" + UrlCodec.encode(id.videoId(), ":") +
            (if (extra.isNotEmpty()) "/$extra" else "") + ".json"

        return parse(Http.getString(url))
    }

    /** Parses an addon response (separate for tests). */
    @Throws(IOException::class)
    fun parse(body: String): List<SubtitleCandidate> {
        val result = ArrayList<SubtitleCandidate>()
        val seen = HashSet<String>()
        val subs = try {
            JsonUtil.parse(body).obj()?.get("subtitles").arr()
        } catch (e: RuntimeException) {
            throw IOException("Bad addon response", e)
        } ?: return result
        for (el in subs) {
            val s = el.obj() ?: continue
            val c = SubtitleCandidate()
            c.legacyId = s.str("id")
            c.id = "os3:" + c.legacyId
            c.source = this
            c.url = s.str("url")
            c.lang = Languages.canonical(s.str("lang"))
            if (c.url.isEmpty() || !seen.add(if (c.legacyId.isNullOrEmpty()) c.url else c.legacyId!!)) continue
            c.releaseName = s.str("movieReleaseName", s.str("subtitleFileName"))
            // "m" tells how the addon matched: "i" = by IMDb id, "h" = by file hash
            c.hashMatch = s.str("m") == "h"
            c.fpsMilli = s.int("fpsMilli", -1)
            c.release = ReleaseNameParser.parse(c.releaseName)
            if (c.release.group == null) {
                val g = s.str("releaseGroup")
                if (g.isNotEmpty() && g != "null") c.release.group = g
            }
            if (c.release.source == null) c.release.source = ReleaseNameParser.source(s.str("releaseFormat"))
            result.add(c)
        }
        return result
    }

    companion object {
        private const val BASE = "https://opensubtitles-v3.strem.io"
    }
}
