package autosubs.core

import java.io.IOException
import java.util.TreeMap

/**
 * Official OpenSubtitles.com REST API. Needs a (free) API key from opensubtitles.com/consumers.
 * Username/password are optional; logging in raises the daily download limit.
 */
class OpenSubtitlesComSource(
    private val apiKey: String,
    private val username: String,
    private val password: String,
    /** e.g. "JustPlayerSubs v1.0" */
    private val appUserAgent: String,
) : SubtitleSource {

    override fun name(): String = "OpenSubtitles.com"

    private fun headers(auth: Boolean): Map<String, String> {
        val h = HashMap<String, String>()
        h["Api-Key"] = apiKey
        h["User-Agent"] = appUserAgent
        h["Accept"] = "application/json"
        val t = token
        if (auth && t != null) h["Authorization"] = "Bearer $t"
        return h
    }

    override fun search(id: MediaId, hash: String?, size: Long, fileName: String?, languages: List<String>): List<SubtitleCandidate> {
        // Parameters must be sorted and lower case, otherwise the API answers with a redirect
        val params = TreeMap<String, String>()
        val imdbNumber = id.imdbId.replaceFirst(Regex("^tt0*"), "")
        if (id.isSeries()) {
            params["parent_imdb_id"] = imdbNumber
            params["season_number"] = id.season.toString()
            params["episode_number"] = id.episode.toString()
        } else {
            params["imdb_id"] = imdbNumber
        }
        val langs = languages.mapNotNull { Languages.toIso1(it) }.distinct().sorted()
        if (langs.isNotEmpty()) params["languages"] = langs.joinToString(",")
        if (hash != null) params["moviehash"] = hash

        val url = StringBuilder("https://$host/api/v1/subtitles")
        var sep = '?'
        for ((k, v) in params) {
            url.append(sep).append(k).append('=').append(UrlCodec.encode(v, ","))
            sep = '&'
        }
        val r = Http.request("GET", url.toString(), headers(false), null)
        if (r.code != 200) throw IOException("OpenSubtitles.com search HTTP ${r.code}: ${r.body.take(200)}")
        return parseSearch(r.body)
    }

    /** Parses a /subtitles response (separate for tests). */
    @Throws(IOException::class)
    fun parseSearch(body: String): List<SubtitleCandidate> {
        val result = ArrayList<SubtitleCandidate>()
        val data = try {
            JsonUtil.parse(body).obj()?.get("data").arr()
        } catch (e: RuntimeException) {
            throw IOException("Bad OpenSubtitles.com response", e)
        } ?: return result
        for (el in data) {
            val a = el.obj()?.get("attributes").obj() ?: continue
            val files = a["files"].arr() ?: continue
            if (files.size != 1) continue // skip multi-CD
            val file = files[0].obj() ?: continue
            val c = SubtitleCandidate()
            c.source = this
            c.fileId = file.long("file_id", -1)
            if (c.fileId < 0) continue
            c.id = "osc:" + c.fileId
            c.legacyId = if (a.has("legacy_subtitle_id")) a.long("legacy_subtitle_id", 0).toString() else null
            c.lang = Languages.canonical(a.str("language"))
            c.releaseName = a.str("release", file.str("file_name"))
            c.hashMatch = a.bool("moviehash_match")
            val fps = a.double("fps", 0.0)
            c.fpsMilli = if (fps > 0) Math.round(fps * 1000).toInt() else -1
            c.machineTranslated = a.bool("machine_translated") || a.bool("ai_translated")
            c.release = ReleaseNameParser.parse(c.releaseName)
            result.add(c)
        }
        return result
    }

    override fun download(candidate: SubtitleCandidate): ByteArray {
        ensureLogin()
        val body = "{\"file_id\":${candidate.fileId}}"
        var r = Http.request("POST", "https://$host/api/v1/download", headers(true), body)
        if (r.code == 401 && token != null) {
            token = null
            ensureLogin()
            r = Http.request("POST", "https://$host/api/v1/download", headers(true), body)
        }
        if (r.code != 200) throw IOException("OpenSubtitles.com download HTTP ${r.code}: ${r.body.take(200)}")
        val json = try {
            JsonUtil.parse(r.body).obj()
        } catch (e: RuntimeException) {
            null
        } ?: throw IOException("Bad download response")
        CoreLog.i("OpenSubtitles.com downloads remaining: " + json.str("remaining"))
        val link = json.str("link")
        if (link.isEmpty()) throw IOException("No download link")
        return Http.getBytes(link, 4 * 1024 * 1024)
    }

    private fun ensureLogin() {
        if (username.isEmpty() || password.isEmpty()) return
        if (token != null && username == tokenUser) return
        try {
            val body = "{\"username\":${jsonString(username)},\"password\":${jsonString(password)}}"
            val r = Http.request("POST", "https://$DEFAULT_HOST/api/v1/login", headers(false), body)
            if (r.code != 200) {
                CoreLog.w("OpenSubtitles.com login failed: HTTP ${r.code}")
                return
            }
            val json = JsonUtil.parse(r.body).obj() ?: return
            token = json.str("token").ifEmpty { null }
            tokenUser = username
            val baseUrl = json.str("base_url")
            host = if (baseUrl.isEmpty()) DEFAULT_HOST else baseUrl.replaceFirst(Regex("^https?://"), "").replace(Regex("/.*$"), "")
            CoreLog.i("OpenSubtitles.com login ok, host $host")
        } catch (e: Exception) {
            CoreLog.w("OpenSubtitles.com login failed: $e")
        }
    }

    companion object {
        private const val DEFAULT_HOST = "api.opensubtitles.com"

        // Login token is shared between instances (one login per app run)
        @Volatile private var token: String? = null
        @Volatile private var tokenUser: String? = null
        @Volatile private var host: String = DEFAULT_HOST
    }
}
