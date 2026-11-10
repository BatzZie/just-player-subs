package autosubs.core

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FineSyncTest {
    private fun subtitle(seed: Int): LongArray {
        val r = Random(seed)
        var t = 10_000L
        return LongArray(500) { t += 1500 + r.nextLong(3000); t }
    }

    /** Reference = subtitle + offset, with a jump of [step] ms after [at] and some line noise. */
    private fun reference(sub: LongArray, offset: Long, at: Long, step: Long, seed: Int): List<Long> {
        val r = Random(seed)
        return sub.filter { r.nextDouble() < 0.85 }.map { it + offset + (if (it >= at) step else 0) + r.nextLong(-120, 121) }
    }

    @Test fun findsAStepAfterAnAdBreak() {
        val sub = subtitle(1)
        val refs = reference(sub, 1000, 600_000, 300, 2)
        val r = FineSync.refine(refs, sub, 1.0, 1000)
        assertNotNull(r)
        assertEquals(2, r.segments.size, r.toString())
        assertTrue(abs(r.offsetAt(100_000) - 1000) <= 40, r.toString())
        assertTrue(abs(r.offsetAt(900_000) - 1300) <= 40, r.toString())
        assertTrue(abs(r.segments[1].fromMs - 601_000) < 10_000, r.toString())
    }

    @Test fun findsAShortBlockInTheMiddle() {
        val r1 = Random(5)
        var t0 = 10_000L
        val sub = LongArray(400) { t0 += 2500 + r1.nextLong(3000); t0 }
        val r0 = Random(6)
        // 1.1 s late for one minute early in the video, otherwise right
        val refs = sub.filter { r0.nextDouble() < 0.85 }.map { it + 500 + (if (it in 60_000..120_000) -1100 else 0) + r0.nextLong(-120, 121) }
        val r = FineSync.refine(refs, sub, 1.0, 500)
        assertNotNull(r)
        assertTrue(abs(r.offsetAt(90_000) - (500 - 1100)) <= 100, r.toString())
        assertTrue(abs(r.offsetAt(30_000) - 500) <= 60, r.toString())
        assertTrue(abs(r.offsetAt(600_000) - 500) <= 60, r.toString())
    }

    @Test fun leavesAGoodOffsetAlone() {
        val sub = subtitle(3)
        assertNull(FineSync.refine(reference(sub, -500, 0, 0, 4), sub, 1.0, -500))
        for (seed in 10 until 30) {
            val s = subtitle(seed)
            assertNull(FineSync.refine(reference(s, 0, 0, 0, seed + 100), s, 1.0, 0), "noise split for seed $seed")
        }
    }

    /** Real data from the Shield (sync-debug files): fine-tuning must never make the fit worse. */
    @Test fun realEpisodes() {
        val dir = java.io.File(System.getProperty("autosubs.testdata") ?: "", "sync-debug")
        val files = dir.listFiles()?.filter { it.name.endsWith(".json") }?.sorted()
        if (files.isNullOrEmpty()) { println("SKIPPED: no sync-debug test data"); return }
        for (f in files) {
            val d = JsonUtil.parse(f.readText()).obj()!!
            val refs = d["refs"].arr()!!.map { it.toString().toLong() }
            for ((name, v) in d["subs"].obj()!!) {
                val starts = v.obj()!!["starts"].arr()!!.map { it.toString().toLong() }.toLongArray()
                val sc0 = v.obj()!!["scale"].toString().toDouble()
                val m = AutoSync.estimateWithScale(refs, starts, sc0) ?: AutoSync.estimateConsistent(refs, starts, sc0) ?: continue
                if (m.second.fit() < 0.4) continue
                val sc = m.first
                val off = m.second.offsetMs
                val fine = FineSync.refine(refs, starts, sc, off)
                val before = error(refs, starts, sc) { off }
                val after = if (fine == null) before else error(refs, starts, sc) { fine.offsetAt(it) }
                val mBefore = matches(refs, starts, sc) { off }
                val mAfter = if (fine == null) mBefore else matches(refs, starts, sc) { fine.offsetAt(it) }
                println("${f.name} ${name.take(40)}: offset $off, ${fine ?: "no change"}; lines within 0.2 s $mBefore -> $mAfter; mean minute ${before.second} -> ${after.second} ms")
                assertTrue(mAfter >= mBefore - 2, "${f.name} $name: fewer lines in sync")
            }
        }
    }

    /** Lines that start within 0.2 s of a reference line (the whole pattern, not line by line). */
    private fun matches(refs: List<Long>, starts: LongArray, sc: Double, offset: (Long) -> Long): Int {
        val sorted = refs.sorted()
        return starts.count { s ->
            val base = Math.round(s * sc)
            val t = base + offset(base)
            val i = sorted.binarySearch(t).let { if (it < 0) -it - 1 else it }
            listOfNotNull(sorted.getOrNull(i - 1), sorted.getOrNull(i)).any { abs(it - t) <= 200 }
        }
    }

    /** Worst and mean |median remainder| per minute. */
    private fun error(refs: List<Long>, starts: LongArray, sc: Double, offset: (Long) -> Long): kotlin.Pair<Long, Long> {
        val sorted = refs.sorted()
        val perMinute = HashMap<Long, MutableList<Long>>()
        for (s in starts) {
            val base = Math.round(s * sc)
            val t = base + offset(base)
            val i = sorted.binarySearch(t).let { if (it < 0) -it - 1 else it }
            val r = listOfNotNull(sorted.getOrNull(i - 1), sorted.getOrNull(i)).minByOrNull { abs(it - t) } ?: continue
            if (abs(r - t) <= 700) perMinute.getOrPut(t / 60_000) { ArrayList() }.add(r - t)
        }
        val medians = perMinute.values.filter { it.size >= 8 }.map { l -> l.sorted()[l.size / 2].let { abs(it) } }
        if (medians.isEmpty()) return kotlin.Pair(0L, 0L)
        return kotlin.Pair(medians.max(), medians.sum() / medians.size)
    }
}
