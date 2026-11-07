package autosubs.core

/** Speed correction for subtitles made for a different frame rate (e.g. 25 fps PAL vs 23.976 fps). */
object FrameRate {

    /**
     * @param lastEndMs       end time of the last subtitle line
     * @param subFps          frame rate the subtitle was made for, or <= 0 if unknown
     * @param videoFps        frame rate of the video, or <= 0 if unknown
     * @param videoDurationMs duration of the video, or <= 0 if unknown
     * @return factor for subtitle times (1.0 = no correction)
     */
    @JvmStatic
    fun detectScale(lastEndMs: Long, subFps: Double, videoFps: Double, videoDurationMs: Long): Double {
        val durationKnown = videoDurationMs > 0 && lastEndMs > 0
        if (subFps > 0 && videoFps > 0) {
            val f = subFps / videoFps
            // Only trust the metadata if the subtitle still fits the video after scaling
            if (isKnownRatio(f) && (!durationKnown || lastEndMs * f <= videoDurationMs + 2000)) return f
        }
        // Subtitle runs well past the end of the video: probably made for a slower frame rate
        if (durationKnown && videoDurationMs > 10 * 60_000 && lastEndMs > videoDurationMs + 20_000) {
            for (f in doubleArrayOf(23.976 / 25.0, 24.0 / 25.0)) {
                if (lastEndMs * f <= videoDurationMs + 2000) return f
            }
        }
        return 1.0
    }

    // Only the ~4% PAL speed-up; 24 vs 23.976 fps metadata is too unreliable to act on
    private fun isKnownRatio(f: Double): Boolean =
        doubleArrayOf(25.0 / 23.976, 25.0 / 24.0).any { Math.abs(f - it) < 0.0004 || Math.abs(f - 1.0 / it) < 0.0004 }
}
