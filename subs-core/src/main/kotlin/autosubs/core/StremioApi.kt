package autosubs.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Minimal client for the Stremio account API (api.strem.io), used to write watch progress to the user's library.
 * Not an official public API: everything here is best effort and optional.
 */
class StremioApi(private val base: String = "https://api.strem.io/api") {

    class Login(@JvmField val authKey: String, @JvmField val email: String)

    @Throws(IOException::class)
    fun login(email: String, password: String): Login {
        val body = buildJsonObject {
            put("type", "Login"); put("email", email); put("password", password); put("facebook", false)
        }
        val result = call("login", body)
        val key = result.str("authKey")
        if (key.isEmpty()) throw IOException("Login failed")
        val user = result["user"].obj()
        return Login(key, user?.str("email", email) ?: email)
    }

    /** @return the library item (JSON), or null if it isn't in the library yet */
    @Throws(IOException::class)
    fun getLibraryItem(authKey: String, id: String): JsonObject? {
        val body = buildJsonObject {
            put("authKey", authKey); put("collection", "libraryItem"); put("all", false)
            putJsonArray("ids") { add(JsonPrimitive(id)) }
        }
        val response = post("datastoreGet", body)
        val result = response["result"]
        return result.arr()?.firstOrNull()?.obj()
    }

    @Throws(IOException::class)
    fun putLibraryItem(authKey: String, item: JsonObject) {
        val body = buildJsonObject {
            put("authKey", authKey); put("collection", "libraryItem")
            putJsonArray("changes") { add(item) }
        }
        call("datastorePut", body)
    }

    /**
     * Reads the library item, updates its watch progress and writes it back (creates it if needed).
     * @return true if the item was new in the library
     */
    @Throws(IOException::class)
    @JvmOverloads
    fun reportProgress(authKey: String, id: MediaId, positionMs: Long, durationMs: Long, watchedMs: Long, finished: Boolean,
                       countWatch: Boolean = true): Boolean {
        val existing = getLibraryItem(authKey, id.imdbId)
        val meta = fullMeta(id.type(), id.imdbId)
        var item = withProgress(existing, id.imdbId, id.type(), id.videoId(), meta?.name, meta?.poster,
            positionMs, durationMs, watchedMs, finished, now(), countWatch)
        // Stremio marks an episode as watched when it hands it to an external player: undo that for this video
        // (or mark it when it was watched to the end)
        val state = item["state"].obj() ?: JsonObject(emptyMap())
        val newState: JsonObject = if (id.isSeries() && meta != null && meta.videos.isNotEmpty()) {
            val ids = WatchedBitField.sortedVideoIds(meta.videos)
            val field = WatchedBitField.parse(state.str("watched").ifEmpty { null }, ids)
            val before = field.get(id.videoId())
            field.set(id.videoId(), finished)
            CoreLog.i("Stremio watched ${id.videoId()}: $before -> $finished (${ids.size} videos)")
            JsonObject(state + ("watched" to JsonPrimitive(field.toString())))
        } else if (!id.isSeries()) {
            // Movies: watched to the end -> marked watched; stopped earlier -> not
            JsonObject(state + ("flaggedWatched" to JsonPrimitive(if (finished) 1 else 0)))
        } else state
        item = JsonObject(item + ("state" to newState))
        putLibraryItem(authKey, item)
        return existing == null
    }

    /** Watch position in the library for one video. */
    class Progress(@JvmField val videoId: String, @JvmField val offsetMs: Long, @JvmField val durationMs: Long)

    /** @return the library's progress for this item (whatever video it points to), or null if not in the library */
    @Throws(IOException::class)
    fun readProgress(authKey: String, id: MediaId): Progress? {
        val state = getLibraryItem(authKey, id.imdbId)?.get("state").obj() ?: return null
        return Progress(state.str("video_id"), state.long("timeOffset", 0), state.long("duration", 0))
    }

    /**
     * Writes only the position of one video: video_id, timeOffset, duration, lastWatched, time watched.
     * Stremio's own watched bookkeeping (the watched bitfield, flaggedWatched, timesWatched) is never touched.
     * @return false if the title isn't in the library (then nothing is written)
     */
    @Throws(IOException::class)
    fun writePosition(authKey: String, id: MediaId, positionMs: Long, durationMs: Long, watchedMs: Long): Boolean {
        // Only update what Stremio itself put in the library (it does so when it starts playing something);
        // a wrongly identified video must never end up in the user's library
        val existing = getLibraryItem(authKey, id.imdbId) ?: return false
        putLibraryItem(authKey, withProgress(existing, id.imdbId, id.type(), id.videoId(), null, null,
            positionMs, durationMs, watchedMs, false, now(), false))
        return true
    }

    /**
     * Safety net after a video was watched to the end: Stremio marks it itself, but can lose that mark again (its
     * own library sync can overwrite it). Sets only the watched mark of this video if it is missing; the video
     * the library points to, the position and everything else stay as they are.
     * @return "already", "marked" or "missing" (not in the library, or no episode list)
     */
    @Throws(IOException::class)
    fun ensureWatched(authKey: String, id: MediaId): String {
        val existing = getLibraryItem(authKey, id.imdbId) ?: return "missing"
        val ids = if (id.isSeries()) fullMeta(id.type(), id.imdbId)?.let { WatchedBitField.sortedVideoIds(it.videos) } else null
        if (id.isSeries() && ids.isNullOrEmpty()) return "missing"
        val item = withWatchedMark(existing, id, ids ?: emptyList(), now()) ?: return "already"
        putLibraryItem(authKey, item)
        return "marked"
    }

    /** Short description of the library state of a video, for logging ("offset=688s watched=false mtime=..."). */
    @Throws(IOException::class)
    fun describe(authKey: String, id: MediaId): String {
        val item = getLibraryItem(authKey, id.imdbId) ?: return "not in library"
        val state = item["state"].obj() ?: return "no state"
        val meta = if (id.isSeries()) fullMeta(id.type(), id.imdbId) else null
        val watched = if (meta != null) WatchedBitField.parse(state.str("watched").ifEmpty { null },
            WatchedBitField.sortedVideoIds(meta.videos)).get(id.videoId()) else state.long("flaggedWatched", 0) > 0
        return "video=" + state.str("video_id") + " offset=" + state.long("timeOffset", -1) / 1000 + "s watched=" + watched +
            " mtime=" + item.str("_mtime")
    }

    private fun call(method: String, body: JsonObject): JsonObject {
        val response = post(method, body)
        return response["result"].obj() ?: throw IOException("Stremio $method: no result")
    }

    private fun post(method: String, body: JsonObject): JsonObject {
        val r = Http.request("POST", "$base/$method", mapOf("Accept" to "application/json"), body.toString())
        val json = try {
            JsonUtil.parse(r.body).obj()
        } catch (e: RuntimeException) {
            null
        } ?: throw IOException("Stremio $method: HTTP ${r.code}")
        json["error"].obj()?.let { throw IOException("Stremio $method: " + it.str("message", it.toString()).take(200)) }
        if (r.code / 100 != 2) throw IOException("Stremio $method: HTTP ${r.code}")
        return json
    }

    companion object {
        /** Stremio's date format, e.g. 2026-11-07T15:04:05.123Z */
        @JvmStatic
        fun now(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS))

        /**
         * Library item with updated watch progress. An existing item keeps all its other fields (including Stremio's
         * own "watched" bookkeeping); a new one is created the way Stremio does for things you watch without adding
         * them to the library (removed + temp), so it shows up in "Continue watching".
         *
         * @param videoId   "tt123" for a movie, "tt123:S:E" for an episode
         * @param watchedMs time watched in this session (added to the totals)
         */
        @JvmStatic
        fun withProgress(
            existing: JsonObject?, id: String, type: String, videoId: String, name: String?, poster: String?,
            positionMs: Long, durationMs: Long, watchedMs: Long, finished: Boolean, now: String,
            countWatch: Boolean = true,
        ): JsonObject {
            val oldState = existing?.get("state").obj() ?: JsonObject(emptyMap())
            val sameVideo = oldState.str("video_id") == videoId
            val state = buildJsonObject {
                for ((k, v) in oldState) put(k, v)
                put("lastWatched", now)
                put("video_id", videoId)
                put("timeOffset", if (finished) 0 else positionMs)
                put("duration", durationMs)
                put("timeWatched", (if (sameVideo) oldState.long("timeWatched", 0) else 0) + watchedMs)
                put("overallTimeWatched", oldState.long("overallTimeWatched", 0) + watchedMs)
                put("timesWatched", oldState.long("timesWatched", 0) + if (finished && countWatch) 1 else 0)
                if (!oldState.has("flaggedWatched")) put("flaggedWatched", 0)
                if (!oldState.has("noNotif")) put("noNotif", false)
                if (!oldState.has("watched")) put("watched", JsonNull)
            }
            return buildJsonObject {
                if (existing != null) for ((k, v) in existing) put(k, v)
                put("_id", id)
                if (existing == null) {
                    put("name", name ?: id)
                    put("type", type)
                    if (poster != null) put("poster", poster) else put("poster", JsonNull)
                    put("posterShape", "poster")
                    put("removed", true)
                    put("temp", true)
                    put("_ctime", now)
                    putJsonObject("behaviorHints") {
                        put("defaultVideoId", JsonNull); put("featuredVideoId", JsonNull); put("hasScheduledVideos", false)
                    }
                }
                put("_mtime", now)
                put("state", state)
            }
        }

        class Meta(
            @JvmField val name: String?, @JvmField val poster: String?, @JvmField val videos: List<WatchedBitField.Companion.Video>,
            @JvmField val year: Int = -1,
            @JvmField val genres: List<String> = emptyList(),
            @JvmField val countries: List<String> = emptyList(),
        ) {
            /** Episode title of a video ("tt123:6:22"), or null. */
            fun episodeTitle(videoId: String): String? = videos.firstOrNull { it.id == videoId }?.title?.ifEmpty { null }
        }

        /**
         * The library item with the watched mark of this video set, or null if it is already marked.
         * @param videoIds the series' video ids in Stremio's order (empty for a movie)
         */
        @JvmStatic
        fun withWatchedMark(existing: JsonObject, id: MediaId, videoIds: List<String>, now: String): JsonObject? {
            val state = existing["state"].obj() ?: return null
            val newState: JsonObject = if (id.isSeries()) {
                val field = WatchedBitField.parse(state.str("watched").ifEmpty { null }, videoIds)
                if (field.get(id.videoId()) || !videoIds.contains(id.videoId())) return null
                field.set(id.videoId(), true)
                JsonObject(state + ("watched" to JsonPrimitive(field.toString())))
            } else {
                if (state.long("flaggedWatched", 0) > 0 || state.long("timesWatched", 0) > 0) return null
                JsonObject(state + ("flaggedWatched" to JsonPrimitive(1)) + ("timesWatched" to JsonPrimitive(1)))
            }
            return JsonObject(existing + ("state" to newState) + ("_mtime" to JsonPrimitive(now)))
        }

        /** Name, poster and episode list from Cinemeta (the source Stremio uses for the watched order). */
        @JvmStatic
        fun fullMeta(type: String, id: String): Meta? = try {
            val m = JsonUtil.parse(Http.getString("https://v3-cinemeta.strem.io/meta/$type/$id.json")).obj()?.get("meta").obj()
            if (m == null) null else Meta(
                m.str("name").ifEmpty { null },
                m.str("poster").ifEmpty { null },
                videos = parseVideos(m),
                year = Regex("(\\d{4})").find(m.str("releaseInfo", m.str("year")))?.groupValues?.get(1)?.toInt() ?: -1,
                genres = (m["genres"].arr() ?: m["genre"].arr())?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: emptyList(),
                countries = m.str("country").split(",").map { it.trim() }.filter { it.isNotEmpty() },
            )
        } catch (e: Exception) {
            null
        }

        private fun parseVideos(m: JsonObject): List<WatchedBitField.Companion.Video> =
            m["videos"].arr()?.mapNotNull { el ->
                    val v = el.obj() ?: return@mapNotNull null
                    val released = v.str("released").ifEmpty { null }?.let {
                        try { java.time.Instant.parse(it).toEpochMilli() } catch (e: Exception) { null }
                    }
                    WatchedBitField.Companion.Video(v.str("id"),
                        if (v.has("season")) v.long("season", 0) else null,
                        if (v.has("episode")) v.long("episode", 0) else if (v.has("number")) v.long("number", 0) else null,
                        released, v.str("name", v.str("title")).ifEmpty { null })
                } ?: emptyList()
    }
}
