package autosubs.core

/** Filters on the wanted languages and sorts best first. */
object SubtitleRanker {

    @JvmStatic
    fun rank(all: List<SubtitleCandidate>, video: ReleaseInfo?, languages: List<String>): List<SubtitleCandidate> {
        val out = ArrayList<SubtitleCandidate>()
        for (c in all) {
            val rank = languages.indexOfFirst { Languages.matches(c.lang, it) }
            if (rank < 0) continue
            c.langRank = rank
            c.score = score(c, video)
            out.add(c)
        }
        // Stable sort keeps the source's order (download count) for equal scores
        out.sortWith(compareBy<SubtitleCandidate> { it.langRank }.thenByDescending { it.score })
        return out
    }

    @JvmStatic
    fun score(c: SubtitleCandidate, video: ReleaseInfo?): Int {
        var score = 0
        if (c.hashMatch) score += 100
        if (c.machineTranslated) score -= 15
        val r = c.release
        if (video != null) {
            if (video.group != null && r.group != null && video.group.equals(r.group, ignoreCase = true)) score += 30
            if (video.source != null && video.source == r.source) score += 10
            else if (video.source != null && r.source != null && video.source != r.source) score -= 2
            if (video.resolution != null && video.resolution == r.resolution) score += 3
            if (video.isEpisode() && r.isEpisode() && (video.season != r.season || video.episode != r.episode)) score -= 50
        }
        if (r.source == "CAM") score -= 20
        return score
    }
}
