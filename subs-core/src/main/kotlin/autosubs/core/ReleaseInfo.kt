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

    fun isEpisode(): Boolean = season >= 0 && episode >= 0

    override fun toString(): String =
        "title=$title year=$year S${season}E$episode group=$group source=$source res=$resolution"
}
