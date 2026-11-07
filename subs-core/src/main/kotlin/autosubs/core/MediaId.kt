package autosubs.core

/** Stremio style content id: tt123 for movies, tt123:S:E for episodes. */
class MediaId(@JvmField val imdbId: String, @JvmField val season: Int, @JvmField val episode: Int) {
    fun isSeries(): Boolean = season >= 0 && episode >= 0
    fun type(): String = if (isSeries()) "series" else "movie"
    fun videoId(): String = if (isSeries()) "$imdbId:$season:$episode" else imdbId
    override fun toString(): String = type() + "/" + videoId()
}
