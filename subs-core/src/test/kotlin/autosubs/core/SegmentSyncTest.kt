package autosubs.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SegmentSyncTest {

    /** Dialogue every 1-7 s over [lengthMs]. */
    private fun subtitle(seed: Int, lengthMs: Long): LongArray {
        val r = Random(seed)
        var t = 20_000L
        val out = ArrayList<Long>()
        while (t < lengthMs) { t += 1000 + r.nextLong(6000); out.add(t) }
        return out.toLongArray()
    }

    /** Reference = subtitle shifted per part, with noise, missing and extra lines. */
    private fun reference(sub: LongArray, offsetFor: (Long) -> Long, seed: Int): List<Long> {
        val r = Random(seed)
        val out = ArrayList<Long>()
        for (s in sub) {
            if (r.nextDouble() < 0.35) continue
            out.add(s + offsetFor(s) + (r.nextDouble() * 400 - 200).toLong())
            if (r.nextDouble() < 0.15) out.add(s + offsetFor(s) + 700 + r.nextLong(1500))
        }
        return out
    }

    @Test fun recapMissingInOneVersion() {
        // Episode of 20 minutes; from 9:00 (subtitle time) on the video is 44 s later (extra recap/scene)
        val sub = subtitle(1, 20 * 60_000L)
        val ref = reference(sub, { s -> if (s < 9 * 60_000L) 400L else 44_400L }, 2)
        assertNull(AutoSync.estimateAll(ref, sub, 1.0)?.takeIf { it.fit() > 0.8 }, "one offset can't explain this well")
        val r = assertNotNull(SegmentSync.estimate(ref, sub, 1.0))
        assertEquals(2, r.segments.size, r.toString())
        assertTrue(Math.abs(r.offsetAt(60_000) - 400) < 150, r.toString())
        assertTrue(Math.abs(r.offsetAt(15 * 60_000L) - 44_400) < 150, r.toString())
        // boundary near 9:00 subtitle time = 9:00.4 video time
        val boundary = r.segments[1].fromMs
        assertTrue(Math.abs(boundary - (9 * 60_000L + 400)) < 15_000, "boundary at ${boundary / 1000}s")
        assertTrue(r.fit() > 0.6, r.toString())
    }

    @Test fun twoChanges() {
        val sub = subtitle(3, 45 * 60_000L)
        val ref = reference(sub, { s -> when { s < 10 * 60_000L -> 0L; s < 30 * 60_000L -> -1_300L; else -> 2_100L } }, 4)
        val r = assertNotNull(SegmentSync.estimate(ref, sub, 1.0))
        assertEquals(3, r.segments.size, r.toString())
        assertTrue(Math.abs(r.offsetAt(40 * 60_000L) - 2_100) < 150, r.toString())
    }

    @Test fun constantOffsetGivesNoSegments() {
        val sub = subtitle(5, 40 * 60_000L)
        assertNull(SegmentSync.estimate(reference(sub, { 1_850L }, 6), sub, 1.0))
    }

    @Test fun unrelatedReferenceGivesNothing() {
        val sub = subtitle(7, 40 * 60_000L)
        assertNull(SegmentSync.estimate(subtitle(8, 40 * 60_000L).toList(), sub, 1.0))
    }
}
