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
    fun reportProgress(authKey: String, id: MediaId, positionMs: Long, durationMs: Long, watchedMs: Long, finished: Boolean): Boolean {
        val existing = getLibraryItem(authKey, id.imdbId)
        var name: String? = null
        var poster: String? = null
        if (existing == null) {
            val m = meta(id.type(), id.imdbId)
            name = m.first
            poster = m.second
        }
        putLibraryItem(authKey, withProgress(existing, id.imdbId, id.type(), id.videoId(), name, poster,
            positionMs, durationMs, watchedMs, finished, now()))
        return existing == null
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
                put("timesWatched", oldState.long("timesWatched", 0) + if (finished) 1 else 0)
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

        /** Name and poster from Cinemeta, for items that are not in the library yet. */
        @JvmStatic
        fun meta(type: String, id: String): Pair<String?, String?> = try {
            val m = JsonUtil.parse(Http.getString("https://v3-cinemeta.strem.io/meta/$type/$id.json")).obj()?.get("meta").obj()
            (m?.str("name")?.ifEmpty { null }) to (m?.str("poster")?.ifEmpty { null })
        } catch (e: Exception) {
            null to null
        }
    }
}
