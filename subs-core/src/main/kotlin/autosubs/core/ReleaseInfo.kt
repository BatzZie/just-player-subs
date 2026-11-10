package autosubs.core

/** What could be parsed from a release / file name. Fields are null or -1 when unknown. */
class ReleaseInfo {
    @JvmField var title: String? = null
    @JvmField var year: Int = -1
    @JvmField var season: Int = -1
    @JvmField var episode: Int = -1
    @JvmField var group: String? = null
    @JvmField var source: String? = null
    @JvmField var resolution: String? = null
    /** Anime style absolute episode number ("Show - 27"), without a season; -1 if unknown. */
    @JvmField var absoluteEpisode: Int = -1

    fun isEpisode(): Boolean = season >= 0 && episode >= 0
    fun isSeries(): Boolean = isEpisode() || absoluteEpisode > 0

    override fun toString(): String =
        "title=$title year=$year S${season}E$episode" + (if (absoluteEpisode > 0) " abs=$absoluteEpisode" else "") + " group=$group source=$source res=$resolution"
}
