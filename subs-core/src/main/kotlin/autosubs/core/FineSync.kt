package autosubs.core

import kotlin.math.abs

/**
 * Fine-tuning after a good single offset: a subtitle made for another edit of the same episode (WEB vs BluRay)
 * is often a few hundred ms off after each ad break. Measures, line by line, how far the subtitle still is from
 * the reference (MKV index), splits the video where that remainder clearly changes, and gives each part its own
 * offset. Young Sheldon S07E02 went from 0.5 s late at the end to within 0.1 s.
 */
object FineSync {
    private const val MATCH_MS = 1500L
    private const val MIN_STEP_MS = 150L
    private const val MIN_LINES = 10
    private const val MAX_LINES = 900
    /** Cost of one extra part, in ms of summed error: a step must explain clearly more than noise. */
    private const val PENALTY = 3000.0
    private const val MIN_CORRECTION_MS = 80L

    private class Pair(val timeMs: Long, val residualMs: Long)

    /**
     * @param offsetMs the single offset found (display = subtitle * scale + offset)
     * @return absolute offsets per part, or null if the single offset is already right everywhere
     */
    @JvmStatic
    fun refine(refStartsMs: List<Long>, subStartsMs: LongArray, scale: Double, offsetMs: Long): SegmentSync.Result? {
        val refs = refStartsMs.sorted()
        if (refs.size < 2 * MIN_LINES || subStartsMs.isEmpty()) return null
        val pairs = ArrayList<Pair>()
        for (s in subStartsMs.sorted()) {
            val t = Math.round(s * scale) + offsetMs
            val r = nearest(refs, t) ?: continue
            if (abs(r - t) <= MATCH_MS) pairs.add(Pair(t, r - t))
        }
        if (pairs.size < 2 * MIN_LINES || pairs.size > MAX_LINES) return null
        val x = DoubleArray(pairs.size) { pairs[it].residualMs.toDouble() }

        // Optimal partitioning: least total error (sum of distances to each part's median) plus a penalty per part
        val n = x.size
        val best = DoubleArray(n + 1) { Double.MAX_VALUE }
        val from = IntArray(n + 1)
        best[0] = 0.0
        val sorted = DoubleArray(n)
        for (i in 0 until n) {
            if (best[i] == Double.MAX_VALUE) continue
            var len = 0
            for (j in i until n) {
                // insert x[j] into sorted[0..len)
                var k = len
                while (k > 0 && sorted[k - 1] > x[j]) { sorted[k] = sorted[k - 1]; k-- }
                sorted[k] = x[j]
                len++
                if (len < MIN_LINES) continue
                val c = best[i] + cost(sorted, len) + PENALTY
                if (c < best[j + 1]) {
                    best[j + 1] = c
                    from[j + 1] = i
                }
            }
        }
        val cuts = ArrayList<IntArray>()
        var end = n
        while (end > 0) {
            val st = from[end]
            cuts.add(0, intArrayOf(st, end))
            end = st
        }
        // Neighbours that ended up close to each other are one part
        var merged = true
        while (merged && cuts.size > 1) {
            merged = false
            for (i in 0 until cuts.size - 1) {
                if (abs(median(x, cuts[i]) - median(x, cuts[i + 1])) < MIN_STEP_MS) {
                    cuts[i] = intArrayOf(cuts[i][0], cuts[i + 1][1])
                    cuts.removeAt(i + 1)
                    merged = true
                    break
                }
            }
        }
        val result = ArrayList<SegmentSync.Segment>()
        for ((k, seg) in cuts.withIndex()) {
            val start = if (k == 0) 0L else (pairs[seg[0] - 1].timeMs + pairs[seg[0]].timeMs) / 2
            result.add(SegmentSync.Segment(start, offsetMs + Math.round(median(x, seg) / 10.0) * 10))
        }
        if (result.size == 1 && abs(result[0].offsetMs - offsetMs) < MIN_CORRECTION_MS) return null
        var matched = 0
        for ((k, seg) in cuts.withIndex()) {
            val o = (result[k].offsetMs - offsetMs).toDouble()
            for (i in seg[0] until seg[1]) if (abs(x[i] - o) <= 250) matched++
        }
        CoreLog.i("FineSync: ${pairs.size} lines, segments " + result.joinToString(" ") { "${it.fromMs / 1000}s:${it.offsetMs}" })
        return SegmentSync.Result(result, matched, pairs.size)
    }

    /** Sum of distances to the median of a sorted array. */
    private fun cost(sorted: DoubleArray, len: Int): Double {
        val m = if (len % 2 == 1) sorted[len / 2] else (sorted[len / 2 - 1] + sorted[len / 2]) / 2
        var c = 0.0
        for (i in 0 until len) c += abs(sorted[i] - m)
        return c
    }

    private fun median(x: DoubleArray, range: IntArray): Double {
        val v = x.copyOfRange(range[0], range[1]).also { it.sort() }
        if (v.isEmpty()) return 0.0
        return if (v.size % 2 == 1) v[v.size / 2] else (v[v.size / 2 - 1] + v[v.size / 2]) / 2
    }

    private fun nearest(sorted: List<Long>, t: Long): Long? {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < t) lo = mid + 1 else hi = mid
        }
        var best: Long? = null
        for (i in intArrayOf(lo - 1, lo)) {
            if (i in sorted.indices && (best == null || abs(sorted[i] - t) < abs(best - t))) best = sorted[i]
        }
        return best
    }
}
