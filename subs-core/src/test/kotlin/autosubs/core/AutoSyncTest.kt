package autosubs.core

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
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
