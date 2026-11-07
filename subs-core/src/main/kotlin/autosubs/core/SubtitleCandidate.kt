package autosubs.core

/** One subtitle found online. */
class SubtitleCandidate {
    @JvmField var id: String = ""
    @JvmField var url: String = ""
    @JvmField var source: SubtitleSource? = null
    /** Source specific (OpenSubtitles.com file id). */
    @JvmField var fileId: Long = -1
    /** Source specific id used to avoid duplicates between sources. */
    @JvmField var legacyId: String? = null
    /** Machine or AI translated. */
    @JvmField var machineTranslated: Boolean = false
    @JvmField var lang: String? = null
    @JvmField var releaseName: String? = null
    @JvmField var hashMatch: Boolean = false
    /** Frame rate the subtitle was made for, in fps * 1000, or -1. */
    @JvmField var fpsMilli: Int = -1
    @JvmField var release: ReleaseInfo = ReleaseInfo()

    // Filled in by SubtitleRanker
    @JvmField var langRank: Int = 0
    @JvmField var score: Int = 0

    fun label(): String {
        val name = if (!releaseName.isNullOrEmpty()) releaseName else id
        return "[" + Languages.displayName(lang) + "] " + (if (hashMatch) "✓ " else "") + name +
            (source?.let { "  · " + it.name() } ?: "")
    }

    override fun toString(): String =
        (source?.let { it.name() + " " } ?: "") + lang + " score=" + score + (if (hashMatch) " HASH" else "") +
            " fps=" + fpsMilli + " " + releaseName
}
