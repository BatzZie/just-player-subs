package autosubs.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class StremioApiTest {
    private val now = "2026-11-07T15:00:00.000Z"

    private fun state(item: JsonObject) = item["state"].obj()!!

    @Test fun updatesExistingItemAndKeepsOtherFields() {
        val existing = JsonUtil.parse("""{"_id":"tt6226232","name":"Young Sheldon","type":"series","removed":false,"temp":false,
            "_ctime":"2024-01-01T00:00:00.000Z","_mtime":"2024-01-01T00:00:00.000Z","customField":42,
            "state":{"lastWatched":"2024-01-01T00:00:00.000Z","timeWatched":1000,"timeOffset":5,"overallTimeWatched":50000,
              "timesWatched":3,"flaggedWatched":0,"duration":1,"video_id":"tt6226232:6:16","watched":"tt6226232:6:16:5:eJyLBgAAeAB3","noNotif":false}}""").obj()
        val item = StremioApi.withProgress(existing, "tt6226232", "series", "tt6226232:6:17", null, null,
            positionMs = 304_513, durationMs = 1_198_400, watchedMs = 300_000, finished = false, now = now)
        val s = state(item)
        assertEquals("tt6226232:6:17", s.str("video_id"))
        assertEquals(304_513, s.long("timeOffset", -1))
        assertEquals(1_198_400, s.long("duration", -1))
        assertEquals(300_000, s.long("timeWatched", -1)) // other episode: starts again
        assertEquals(350_000, s.long("overallTimeWatched", -1))
        assertEquals(3, s.long("timesWatched", -1))
        assertEquals("tt6226232:6:16:5:eJyLBgAAeAB3", s.str("watched")) // Stremio's own bookkeeping untouched
        assertEquals(now, item.str("_mtime"))
        assertEquals("2024-01-01T00:00:00.000Z", item.str("_ctime"))
        assertEquals(JsonPrimitive(42), item["customField"])
        assertEquals(false, item.bool("removed", true))
    }

    @Test fun newItemShowsUpInContinueWatching() {
        val item = StremioApi.withProgress(null, "tt1877830", "movie", "tt1877830", "The Batman", "https://img/p.jpg",
            positionMs = 600_000, durationMs = 10_571_616, watchedMs = 600_000, finished = false, now = now)
        assertEquals("The Batman", item.str("name"))
        assertEquals("movie", item.str("type"))
        assertEquals(true, item.bool("removed"))
        assertEquals(true, item.bool("temp"))
        assertEquals(600_000, state(item).long("timeOffset", -1))
        assertEquals(now, item.str("_ctime"))
    }

    @Test fun finishedResetsOffset() {
        val item = StremioApi.withProgress(null, "tt1", "movie", "tt1", "X", null, 5_000_000, 5_100_000, 100_000, true, now)
        assertEquals(0, state(item).long("timeOffset", -1))
        assertEquals(1, state(item).long("timesWatched", -1))
    }
}
