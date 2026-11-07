package autosubs.core

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoSyncTest {

    /** Synthetic dialogue: a line every 1-8 s for two hours. */
    private fun subtitle(seed: Int): LongArray {
        val r = Random(seed)
        var t = 60_000L
        return LongArray(1200) { t += 1000 + r.nextLong(7000); t }
    }

    /** Reference: the same lines with timing noise, some missing, some extra (different author). */
    private fun reference(sub: LongArray, offset: Long, seed: Int): List<Long> {
        val r = Random(seed)
        val out = ArrayList<Long>()
        for (s in sub) {
            if (r.nextDouble() < 0.35) continue
            out.add(s + offset + (r.nextDouble() * 400 - 200).toLong())
            if (r.nextDouble() < 0.15) out.add(s + offset + 700 + r.nextLong(1500))
        }
        return out
    }

    @Test fun findsOffsetFromCompleteReference() {
        for ((i, offset) in listOf(0L, 1850L, -1960L, 44_100L, -11_000L).withIndex()) {
            val sub = subtitle(i)
            val r = AutoSync.estimateAll(reference(sub, offset, 100 + i), sub, 1.0)
            assertNotNull(r, "offset $offset")
            assertTrue(Math.abs(r.offsetMs - offset) < 150, "offset $offset estimated ${r.offsetMs}")
        }
    }

    @Test fun unrelatedReferenceGivesNoFinalResult() {
        var finals = 0
        for (seed in 0 until 20) {
            val sub = subtitle(seed)
            val unrelated = subtitle(1000 + seed).toList()
            if (AutoSync.estimateAll(unrelated, sub, 1.0) != null) finals++
        }
        assertTrue(finals == 0, "$finals of 20 unrelated references gave a final offset")
    }

    @Test fun playbackModeNeedsEnoughCues() {
        val sub = subtitle(7)
        val sync = AutoSync()
        val ref = reference(sub, 2500, 8)
        for (t in ref.take(5)) { sync.onCues(false, t); sync.onCues(true, t + 1000) }
        assertNull(sync.estimate(sub, 1.0), "5 cues must not be enough")
        var result: AutoSync.Result? = null
        for (t in ref.drop(5).take(200)) {
            sync.onCues(false, t); sync.onCues(true, t + 1000)
            if (sync.collected() % 2 == 0) sync.estimate(sub, 1.0)?.let { if (it.level == AutoSync.Level.FINAL && result == null) result = it }
        }
        assertNotNull(result)
        assertTrue(Math.abs(result!!.offsetMs - 2500) < 200, "estimated ${result!!.offsetMs}")
    }

    // ---- real subtitles (optional local test data, see build.gradle)

    private val dir = File(System.getProperty("autosubs.testdata") ?: "")

    private fun srtStarts(name: String): LongArray? {
        val f = File(dir, name)
        if (!f.exists()) return null
        val re = Regex("(\\d\\d):(\\d\\d):(\\d\\d)[,.](\\d\\d\\d) -->")
        return re.findAll(f.readText()).map {
            val (h, m, s, ms) = it.destructured
            ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + ms.toLong()
        }.sorted().toList().toLongArray()
    }

    private fun checkReal(refName: String, subName: String, expected: Long, tolerance: Long) {
        val ref = srtStarts(refName)
        val sub = srtStarts(subName)
        if (ref == null || sub == null) {
            println("SKIPPED: test data $refName / $subName not found in $dir")
            return
        }
        val r = AutoSync.estimateAll(ref.toList(), sub, 1.0)
        assertNotNull(r)
        println("$refName vs $subName: ${r.offsetMs} ms (expected ~$expected)")
        assertTrue(Math.abs(r.offsetMs - expected) <= tolerance, "estimated ${r.offsetMs}, expected $expected")
    }

    @Test fun realBatmanEnglishVsDutch() = checkReal("batman_eng_evo.srt", "batman_nld_evo.srt", -10_900, 300)

    @Test fun realMoblandEnglishVsDutch() = checkReal("mobland_s02e01_eng.srt", "mobland_s02e01_nld.srt", -150, 300)

    @Test fun realBatmanMkvIndexVsDutch() {
        val f = File(dir, "batman_mkv_cues.json")
        val sub = srtStarts("batman_nld_evo.srt")
        if (!f.exists() || sub == null) {
            println("SKIPPED: batman_mkv_cues.json not found")
            return
        }
        val tracks = JsonUtil.parse(f.readText()).obj()!!
        val track5 = tracks["5"].arr()!!.map { it.toString().toLong() }
        val r = AutoSync.estimateAll(track5, sub, 1.0)
        assertNotNull(r)
        println("Batman MKV index (track 5) vs Dutch: ${r.offsetMs} ms (expected about -1900)")
        assertTrue(Math.abs(r.offsetMs + 1900) <= 200, "estimated ${r.offsetMs}")
    }
}

class PickBestTest {
    @Test fun choosesTheSubtitleThatFitsBest() {
        val r = Random(5)
        var t = 30_000L
        val reference = LongArray(900) { t += 900 + r.nextLong(6000); t }
        // good: same lines, small noise, other offset; poor: a different cut with only every third line in sync
        val good = reference.map { it + 1200 + r.nextLong(-150, 150) }.toLongArray()
        val poor = reference.mapIndexed { i, v -> if (i % 3 == 0) v + 1200 else v + 1200 + 2000 + r.nextLong(3000) }.toLongArray()
        val result = AutoSync.pickBest(reference.toList(), mapOf("poor" to poor, "good" to good), 1.0)
        kotlin.test.assertEquals("good", result?.first)
        assertTrue(Math.abs(result!!.second.offsetMs + 1200) < 100, "offset ${result.second.offsetMs}")
        assertTrue(result.second.fit() > 0.9)
    }
}

class FrameRateMeasureTest {
    private fun subtitle(seed: Int, lengthMs: Long): LongArray {
        val r = Random(seed)
        var t = 20_000L
        val out = ArrayList<Long>()
        while (t < lengthMs) { t += 1000 + r.nextLong(6000); out.add(t) }
        return out.toLongArray()
    }

    @Test fun measures24vs23976() {
        // Subtitle timed for 24 fps, video 23.976: drifts ~1.1 s over 19 minutes
        val sub = subtitle(11, 19 * 60_000L)
        val r = Random(12)
        val ref = sub.filter { r.nextDouble() > 0.3 }.map { (it * (24.0 / 23.976)).toLong() + 400 + (r.nextDouble() * 300 - 150).toLong() }
        val plain = AutoSync.estimateAll(ref, sub, 1.0)
        val measured = assertNotNull(AutoSync.estimateWithScale(ref, sub, 1.0))
        assertTrue(Math.abs(measured.first - 24.0 / 23.976) < 1e-9, "scale ${measured.first}")
        assertTrue(measured.second.fit() > (plain?.fit() ?: 0.0) + 0.1, "fit ${measured.second.fit()} vs ${plain?.fit()}")
        assertTrue(Math.abs(measured.second.offsetMs - 400) < 150, "offset ${measured.second.offsetMs}")
    }

    @Test fun keepsScaleWhenItAlreadyFits() {
        val sub = subtitle(13, 40 * 60_000L)
        val r = Random(14)
        val ref = sub.filter { r.nextDouble() > 0.3 }.map { it + 1_850 + (r.nextDouble() * 300 - 150).toLong() }
        val measured = assertNotNull(AutoSync.estimateWithScale(ref, sub, 1.0))
        assertEquals(1.0, measured.first)
    }
}
