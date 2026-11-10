package autosubs.core

import kotlin.math.abs

/**
 * Fine-tuning after a good single offset: a subtitle made for another edit of the same episode (WEB vs BluRay)
 * is often a few hundred ms off after each ad break. Measures, line by line, how far the subtitle still is from
 * the reference (MKV index), splits the video where that remainder clearly changes, and gives each part its own
 * offset. Young Sheldon S07E02 went from 0.5 s late at the end to within 0.1 s.
 */
object FineSync {
    private const val MATCH_MS = 700L
    private const val MIN_STEP_MS = 150L
    private const val MIN_LINES = 10
    private const val MAX_SEGMENTS = 8
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
        if (pairs.size < 2 * MIN_LINES) return null

        // Binary segmentation on the medians of the remainders
        val bounds = ArrayList<Int>() // start indexes of segments
        bounds.add(0)
        split(pairs, 0, pairs.size, bounds)
        bounds.sort()
        // Merge neighbours that ended up close to each other
        val segs = ArrayList<IntArray>()
        for (i in bounds.indices) segs.add(intArrayOf(bounds[i], if (i + 1 < bounds.size) bounds[i + 1] else pairs.size))
        var merged = true
        while (merged && segs.size > 1) {
            merged = false
            for (i in 0 until segs.size - 1) {
                if (abs(median(pairs, segs[i]) - median(pairs, segs[i + 1])) < MIN_STEP_MS) {
                    segs[i] = intArrayOf(segs[i][0], segs[i + 1][1])
                    segs.removeAt(i + 1)
                    merged = true
                    break
                }
            }
        }
        val result = ArrayList<SegmentSync.Segment>()
        for ((k, seg) in segs.withIndex()) {
            val from = if (k == 0) 0L else (pairs[seg[0] - 1].timeMs + pairs[seg[0]].timeMs) / 2
            val corrected = offsetMs + Math.round(median(pairs, seg) / 10.0) * 10
            result.add(SegmentSync.Segment(from, corrected))
        }
        if (result.size == 1 && abs(result[0].offsetMs - offsetMs) < MIN_CORRECTION_MS) return null
        val matched = pairs.count { p ->
            val o = result.last { it.fromMs <= p.timeMs || it === result[0] }.offsetMs - offsetMs
            abs(p.residualMs - o) <= 250
        }
        CoreLog.i("FineSync: ${pairs.size} lines, segments " + result.joinToString(" ") { "${it.fromMs / 1000}s:${it.offsetMs}" })
        return SegmentSync.Result(result, matched, pairs.size)
    }

    private fun split(pairs: List<Pair>, from: Int, to: Int, bounds: MutableList<Int>) {
        if (bounds.size >= MAX_SEGMENTS || to - from < 2 * MIN_LINES) return
        var bestAt = -1
        var bestGain = 0.0
        val whole = cost(pairs, from, to)
        for (at in from + MIN_LINES..to - MIN_LINES) {
            val left = median(pairs, intArrayOf(from, at))
            val right = median(pairs, intArrayOf(at, to))
            if (abs(left - right) < MIN_STEP_MS) continue
            val gain = whole - cost(pairs, from, at) - cost(pairs, at, to)
            if (gain > bestGain) {
                bestGain = gain
                bestAt = at
            }
        }
        if (bestAt < 0) return
        // The step must explain the lines clearly: per line of the smaller part ~120 ms less error, and most of
        // its lines must really sit on their own side of the step (not a few outliers pulling the median)
        val smaller = if (bestAt - from < to - bestAt) intArrayOf(from, bestAt) else intArrayOf(bestAt, to)
        val other = if (smaller[0] == from) intArrayOf(bestAt, to) else intArrayOf(from, bestAt)
        val own = median(pairs, smaller)
        val theirs = median(pairs, other)
        val onOwnSide = (smaller[0] until smaller[1]).count { abs(pairs[it].residualMs - own) < abs(pairs[it].residualMs - theirs) }
        if (bestGain < 120.0 * (smaller[1] - smaller[0]) || onOwnSide < 0.75 * (smaller[1] - smaller[0])) return
        bounds.add(bestAt)
        split(pairs, from, bestAt, bounds)
        split(pairs, bestAt, to, bounds)
    }

    /** Sum of absolute deviations from the median (robust against lines split differently). */
    private fun cost(pairs: List<Pair>, from: Int, to: Int): Double {
        val m = median(pairs, intArrayOf(from, to))
        var c = 0.0
        for (i in from until to) c += minOf(abs(pairs[i].residualMs - m), 400.0)
        return c
    }

    private fun median(pairs: List<Pair>, range: IntArray): Double {
        val v = (range[0] until range[1]).map { pairs[it].residualMs }.sorted()
        if (v.isEmpty()) return 0.0
        return if (v.size % 2 == 1) v[v.size / 2].toDouble() else (v[v.size / 2 - 1] + v[v.size / 2]) / 2.0
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
