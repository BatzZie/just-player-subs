package autosubs.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchedBitFieldTest {
    private fun ids(n: Int) = (1..n).map { "tt1:1:$it" }

    @Test fun stremioTestVectorDecodes() {
        // From stremio-core's tests: eJyTZwAAAEAAIA== -> bytes [31, 0] (first five videos watched)
        assertEquals(listOf<Byte>(31, 0), WatchedBitField.unpack("eJyTZwAAAEAAIA==").toList())
        val w = WatchedBitField.parse("tt1:1:5:5:eJyTZwAAAEAAIA==", ids(10))
        assertTrue((1..5).all { w.get("tt1:1:$it") })
        assertFalse(w.get("tt1:1:6"))
    }

    @Test fun shiftsWhenEpisodesWereInserted() {
        // Same data, but two specials now sort before the anchor: bits move two places
        val videos = listOf("tt1:0:1", "tt1:0:2") + ids(10)
        val w = WatchedBitField.parse("tt1:1:5:5:eJyTZwAAAEAAIA==", videos)
        assertFalse(w.get("tt1:0:1"))
        assertTrue(w.get("tt1:1:1"))
        assertTrue(w.get("tt1:1:5"))
        assertFalse(w.get("tt1:1:6"))
    }

    @Test fun unsetAndRoundTrip() {
        val w = WatchedBitField.parse("tt1:1:5:5:eJyTZwAAAEAAIA==", ids(10))
        w.set("tt1:1:5", false)
        w.set("tt1:1:8", true)
        val s = w.toString()
        assertTrue(s.startsWith("tt1:1:8:8:"), s)
        val again = WatchedBitField.parse(s, ids(10))
        assertFalse(again.get("tt1:1:5"))
        assertTrue(again.get("tt1:1:4"))
        assertTrue(again.get("tt1:1:8"))
    }

    @Test fun emptyAndUnknownAnchor() {
        assertEquals("tt1:1:1:1:" + WatchedBitField.pack(ByteArray(2)), WatchedBitField.parse(null, ids(10)).toString())
        assertFalse(WatchedBitField.parse("tt9:1:1:1:eJyTZwAAAEAAIA==", ids(3)).get("tt1:1:1"))
    }

    @Test fun stremioOrder() {
        val v = listOf(
            WatchedBitField.Companion.Video("b", 1, 2, null),
            WatchedBitField.Companion.Video("s", 0, 1, null),
            WatchedBitField.Companion.Video("a", 1, 1, null),
        )
        assertEquals(listOf("s", "a", "b"), WatchedBitField.sortedVideoIds(v))
    }
}
