package autosubs.core

import kotlin.math.abs

/**
 * Fine-tuning after a good single offset: a subtitle made for another edit of the same episode (WEB vs BluRay)
 * is often a few hundred ms off after each ad break. Measures, on the pattern of the surrounding lines, how far
 * each part still is from the reference (MKV index), splits the video where that clearly changes (optimal
 * partitioning) and gives each part its own offset.
 */
object FineSync {
    private const val LOCAL_WINDOW_MS = 15_000L
    private const val LOCAL_MIN_LINES = 6
    private const val LOCAL_RANGE_MS = 3000L
    private const val LOCAL_STEP_MS = 20L
    private const val LOCAL_MATCH_MS = 200L
    private const val BOUNDARY_SEARCH_MS = 20_000L
    private const val MIN_GAP_MS = 300L
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
        // The remainder of each line, measured on the pattern of its neighbours (about 30 s): where lines are 1-2 s
        // apart, matching line by line can pair each line with the previous one and look right while it is a
        // whole line off (Young Sheldon S07E02's cold open was 2.2 s off this way)
        val times = subStartsMs.sorted().map { Math.round(it * scale) + offsetMs }
        val pairs = ArrayList<Pair>()
        var lo = 0
        var hi = 0
        for (i in times.indices) {
            val t = times[i]
            while (lo < i && times[lo] < t - LOCAL_WINDOW_MS) lo++
            while (hi < times.size && times[hi] <= t + LOCAL_WINDOW_MS) hi++
            val n = hi - lo
            if (n < LOCAL_MIN_LINES) continue
            var bestD = 0L
            var bestC = -1
            val steps = (2 * LOCAL_RANGE_MS / LOCAL_STEP_MS).toInt() + 1
            val counts = IntArray(steps)
            for (k in 0 until steps) {
                val d = k * LOCAL_STEP_MS - LOCAL_RANGE_MS
                var c = 0
                for (j in lo until hi) {
                    val r = nearest(refs, times[j] + d) ?: continue
                    if (abs(r - times[j] - d) <= LOCAL_MATCH_MS) c++
                }
                counts[k] = c
                if (c > bestC) {
                    bestC = c
                    bestD = d
                }
            }
            var second = 0
            for (k in 0 until steps) {
                val d = k * LOCAL_STEP_MS - LOCAL_RANGE_MS
                if (abs(d - bestD) > 500) second = maxOf(second, counts[k])
            }
            if (bestC < maxOf(4, (n * 0.45).toInt()) || bestC < second * 1.3) continue
            // The single offset is the default: another one must fit this part clearly better
            if (abs(bestD) > 400) {
                var atZero = 0
                for (k in 0 until steps) if (abs(k * LOCAL_STEP_MS - LOCAL_RANGE_MS) <= 200) atZero = maxOf(atZero, counts[k])
                if (bestC < atZero * 1.5 + 1) bestD = 0
            }
            // Precise remainder: median distance of the matched lines at that offset
            val ds = ArrayList<Long>()
            for (j in lo until hi) {
                val r = nearest(refs, times[j] + bestD) ?: continue
                if (abs(r - times[j] - bestD) <= LOCAL_MATCH_MS) ds.add(r - times[j])
            }
            if (ds.isEmpty()) continue
            ds.sort()
            pairs.add(Pair(t, ds[ds.size / 2]))
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
        val offsets = cuts.map { offsetMs + Math.round(median(x, it) / 10.0) * 10 }
        // Exact boundaries: the measurement above looks at ~30 s around each line, so a boundary can be a few
        // seconds off. Line by line near each boundary, see which of the two offsets puts it on a reference line,
        // and switch between the last line of one part and the first line of the next (in video time).
        val result = ArrayList<SegmentSync.Segment>()
        result.add(SegmentSync.Segment(0L, offsets[0]))
        for (k in 1 until cuts.size) {
            val a = offsets[k - 1]
            val b = offsets[k]
            val rough = (pairs[cuts[k][0] - 1].timeMs + pairs[cuts[k][0]].timeMs) / 2
            val lo = times.indexOfFirst { it >= rough - BOUNDARY_SEARCH_MS }.let { if (it < 0) times.size else it }
            val hi = times.indexOfLast { it <= rough + BOUNDARY_SEARCH_MS } + 1
            var bestSplit = times.indexOfFirst { it >= rough }.let { if (it < 0) times.size else it }
            if (hi > lo) {
                // score(split) = lines before it that fit with a + lines from it on that fit with b
                val fitsA = BooleanArray(hi - lo) { fits(refs, times[lo + it] - offsetMs + a) }
                val fitsB = BooleanArray(hi - lo) { fits(refs, times[lo + it] - offsetMs + b) }
                var score = fitsB.count { it }
                var bestScore = -1
                var bestGap = -1L
                for (split in lo..hi) {
                    if (split > lo) {
                        if (fitsA[split - 1 - lo]) score++
                        if (fitsB[split - 1 - lo]) score--
                    }
                    // The pause between the two lines at this split (as shown); a cut in the edit is usually at a
                    // scene change, so on a tie the longest pause wins
                    val gap = if (split <= 0 || split >= times.size) Long.MAX_VALUE / 4
                        else (times[split] - offsetMs + b) - (times[split - 1] - offsetMs + a)
                    // Never show a line before the one before it
                    if (gap < MIN_GAP_MS) continue
                    if (score > bestScore || (score == bestScore && gap > bestGap)) {
                        bestScore = score
                        bestGap = gap
                        bestSplit = split
                    }
                }
            }
            val from = when {
                bestSplit <= 0 -> 0L
                bestSplit >= times.size -> times.last() - offsetMs + a + 1
                else -> {
                    val lastA = times[bestSplit - 1] - offsetMs + a
                    val firstB = times[bestSplit] - offsetMs + b
                    if (firstB > lastA) (lastA + firstB) / 2 else firstB
                }
            }
            result.add(SegmentSync.Segment(maxOf(from, result.last().fromMs + 1), b))
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

    private fun fits(refs: List<Long>, displayMs: Long): Boolean {
        val r = nearest(refs, displayMs) ?: return false
        return abs(r - displayMs) <= LOCAL_MATCH_MS
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
