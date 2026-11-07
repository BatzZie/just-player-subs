package autosubs.core

/**
 * Finds the offset between an online subtitle and a reference subtitle that is known to be in sync
 * (an embedded track of the video). Reference cue start times are collected while the video plays, or come
 * complete from the MKV index.
 *
 * Works on cue start times only, so the reference may be in another language or even a bitmap (PGS) track.
 * Two confidence levels: a provisional estimate early on, and a final one that needs more evidence.
 * Thresholds were tuned by simulation on real eng/nld subtitle pairs (noise, missing cues, unrelated references).
 */
class AutoSync {

    enum class Level { PROVISIONAL, FINAL }

    /**
     * @param matched    reference lines that have a line of the subtitle within 0.35 s at this offset
     * @param references number of reference lines
     */
    class Result(@JvmField val offsetMs: Long, @JvmField val level: Level, @JvmField val matched: Int, @JvmField val references: Int) {
        /** How well the subtitle fits the video (0..1), comparable between subtitles for the same reference. */
        fun fit(): Double = if (references == 0) 0.0 else matched.toDouble() / references
    }

    private val refStarts = ArrayList<Long>()
    private var lastEmpty = true
    private var lastStart = -1L
    private var previousCoarse = Double.NaN
    private var agreeing = 0

    /** Called with every cue change of the reference track. */
    fun onCues(empty: Boolean, timeMs: Long) {
        if (!empty && lastEmpty && (lastStart < 0 || Math.abs(timeMs - lastStart) > 200)) {
            refStarts.add(timeMs)
            lastStart = timeMs
        }
        lastEmpty = empty
    }

    /** A different online subtitle was chosen: earlier agreement doesn't count any more. */
    fun resetAgreement() {
        previousCoarse = Double.NaN
        agreeing = 0
    }

    fun collected(): Int = refStarts.size

    /** After a seek a cue may already be showing; don't count it as a new start. */
    fun onSeek() {
        lastEmpty = false
        lastStart = -1
    }

    /** @return estimate (display = subtitle time * scale + offset) or null if there isn't enough evidence yet */
    fun estimate(subStartsMs: LongArray, scale: Double): Result? {
        if (refStarts.size < FIRST_ATTEMPT || subStartsMs.isEmpty()) return null
        val subs = DoubleArray(subStartsMs.size) { subStartsMs[it] * scale }
        subs.sort()

        // For every candidate offset: how many reference cues have one of our cues within MATCH_MS
        val steps = (2 * MAX_OFFSET_MS / STEP_MS).toInt() + 1
        val matches = IntArray(steps)
        for (k in 0 until steps) {
            val d = (k * STEP_MS - MAX_OFFSET_MS).toDouble()
            var count = 0
            for (r in refStarts) {
                val i = lowerBound(subs, r - d - MATCH_MS)
                if (i < subs.size && subs[i] <= r - d + MATCH_MS) count++
            }
            matches[k] = count
        }
        var best = 0
        for (k in 1 until steps) if (matches[k] > matches[best]) best = k
        var second = 0
        val margin = (1500 / STEP_MS).toInt()
        for (k in 0 until steps) if (Math.abs(k - best) > margin) second = maxOf(second, matches[k])
        val coarse = (best * STEP_MS - MAX_OFFSET_MS).toDouble()
        val matched = matches[best]

        if (!previousCoarse.isNaN() && Math.abs(coarse - previousCoarse) <= 300) agreeing++ else agreeing = 0
        previousCoarse = coarse

        val n = refStarts.size
        var level: Level? = null
        if (matched >= maxOf(6, Math.ceil(n * 0.35).toInt())) {
            if (matched >= second * 2.0 || (agreeing >= 3 && matched >= second * 1.5)) level = Level.FINAL
            else if (matched >= second * 1.6 || (agreeing >= 2 && matched >= second * 1.3)) level = Level.PROVISIONAL
        }
        CoreLog.i("AutoSync: refs=$n matched=$matched second=$second agreeing=$agreeing coarse=${coarse.toLong()} -> $level")
        if (level == null) return null

        // Refine: median of the nearest differences around the winner
        val diffs = ArrayList<Double>()
        for (r in refStarts) {
            var bestDiff = Double.NaN
            var i = lowerBound(subs, r - coarse - MATCH_MS)
            while (i < subs.size && subs[i] <= r - coarse + MATCH_MS) {
                val d = r - subs[i]
                if (bestDiff.isNaN() || Math.abs(d - coarse) < Math.abs(bestDiff - coarse)) bestDiff = d
                i++
            }
            if (!bestDiff.isNaN()) diffs.add(bestDiff)
        }
        if (diffs.isEmpty()) return null
        diffs.sort()
        return Result(Math.round(diffs[diffs.size / 2]), level, matched, n)
    }

    companion object {
        const val FIRST_ATTEMPT = 8
        private const val MAX_OFFSET_MS = 120_000L
        private const val STEP_MS = 100L
        // About 60% of the lines of two independent subtitles start within 0.3 s of each other
        private const val MATCH_MS = 350L

        /** One-shot estimate from a complete list of reference start times (e.g. from the MKV index). */
        @JvmStatic
        fun estimateAll(refStartsMs: List<Long>, subStartsMs: LongArray, scale: Double): Result? {
            val s = AutoSync()
            for (t in refStartsMs.sorted()) {
                s.onCues(false, t)
                s.onCues(true, t + 1)
            }
            val r = s.estimate(subStartsMs, scale)
            return if (r != null && r.level == Level.FINAL) r else null
        }

        /**
         * Picks the subtitle that fits a complete reference best. Each entry: candidate id to its start times.
         * @return id and result of the best fitting subtitle, or null if none gives a clear match
         */
        @JvmStatic
        fun pickBest(refStartsMs: List<Long>, subtitles: Map<String, LongArray>, scale: Double): Pair<String, Result>? {
            var best: Pair<String, Result>? = null
            for ((id, starts) in subtitles) {
                val r = estimateAll(refStartsMs, starts, scale) ?: continue
                CoreLog.i("Fit $id: ${"%.2f".format(r.fit())} offset ${r.offsetMs} ms")
                if (best == null || r.fit() > best.second.fit()) best = id to r
            }
            return best
        }

        private fun lowerBound(a: DoubleArray, v: Double): Int {
            var lo = 0
            var hi = a.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (a[mid] < v) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }
}
