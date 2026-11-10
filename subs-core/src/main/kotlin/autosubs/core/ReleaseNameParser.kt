package autosubs.core

import java.util.Locale

/** Parses names like "Show.S02E05.1080p.WEB.H264-GROUP.mkv" or "The Batman (2022).mkv". */
object ReleaseNameParser {

    private val EXTENSION = Regex("(?i)\\.(mkv|mp4|m4v|avi|ts|m2ts|webm|mov|wmv|mpg|mpeg|srt|ass|ssa|vtt|sub)$")
    // "-GROUP" at the end, optionally followed by a tag like "[EZTVx.to]"
    private val GROUP = Regex("-([A-Za-z0-9]{2,20})(?:\\[[^\\]]*\\])?\\s*$")
    private val NOT_A_GROUP = setOf("dl", "rip", "web", "x264", "x265", "h264", "h265", "hevc", "hd", "sd", "dts", "aac", "ac3")
    private val EPISODE_SXXEYY = Regex("(?i)\\bS(\\d{1,2})\\s?E(\\d{1,3})\\b")
    private val EPISODE_NXNN = Regex("(?i)\\b(\\d{1,2})x(\\d{2,3})\\b")
    // Anime: "[Group] Show S1 - 01 (1080p)" or "[Group] Show - 27 [1080p]"
    private val LEADING_GROUP = Regex("^\\[([^\\]]{1,40})\\]\\s*")
    private val EPISODE_ANIME_SEASON = Regex("(?i)\\b(?:S|Season )(\\d{1,2}) - (\\d{1,4})\\b")
    private val EPISODE_ANIME_ABSOLUTE = Regex(" - (\\d{1,4})(?:v\\d)?(?= |$)")
    private val YEAR = Regex("\\b(19\\d{2}|20\\d{2})\\b")
    private val QUALITY = Regex(
        "(?i)\\b(2160p|1080p|1080i|720p|576p|480p|4k|uhd|web ?dl|webrip|web|bluray|blu ray|bdrip|brrip|bdremux|remux" +
            "|hdtv|dvdrip|dvd|hdrip|x264|x265|h ?264|h ?265|hevc|repack|proper|multi|nordic|complete)\\b"
    )
    private val RESOLUTION = Regex("(?i)\\b(2160p|1080p|1080i|720p|576p|480p|4k|uhd)\\b")

    @JvmStatic
    fun parse(name: String?): ReleaseInfo {
        val info = ReleaseInfo()
        if (name == null) return info
        var base = EXTENSION.replaceFirst(name.trim(), "")

        val leading = LEADING_GROUP.find(base)
        if (leading != null) {
            info.group = leading.groupValues[1].trim()
            base = base.substring(leading.range.last + 1)
        } else {
            GROUP.find(base)?.let { if (it.groupValues[1].lowercase(Locale.ROOT) !in NOT_A_GROUP) info.group = it.groupValues[1] }
        }

        // Normalise separators and brackets to spaces
        val n = base.replace(Regex("[._\\[\\](){}]+"), " ").replace(Regex("\\s+"), " ").trim()

        var cut = n.length
        val e = EPISODE_SXXEYY.find(n) ?: EPISODE_NXNN.find(n)
        if (e != null) {
            info.season = e.groupValues[1].toInt()
            info.episode = e.groupValues[2].toInt()
            cut = minOf(cut, e.range.first)
        } else {
            val a = EPISODE_ANIME_SEASON.find(n)
            if (a != null) {
                info.season = a.groupValues[1].toInt()
                info.episode = a.groupValues[2].toInt()
                cut = minOf(cut, a.range.first)
            } else if (leading != null) {
                // Only with a leading [Group]: " - 27" is too common in other names
                val abs = EPISODE_ANIME_ABSOLUTE.find(n)
                val number = abs?.groupValues?.get(1)?.toInt() ?: -1
                if (abs != null && number in 1..1899) {
                    info.absoluteEpisode = number
                    cut = minOf(cut, abs.range.first)
                }
            }
        }

        val qualityStart = QUALITY.find(n)?.range?.first ?: n.length
        cut = minOf(cut, qualityStart)

        // Last year before the quality/episode markers, but never at position 0 (title "2012" etc.)
        var yearStart = -1
        for (y in YEAR.findAll(n)) {
            if (y.range.first > cut) break
            if (y.range.first == 0) continue
            info.year = y.groupValues[1].toInt()
            yearStart = y.range.first
        }
        if (yearStart > 0) cut = minOf(cut, yearStart)

        val title = n.substring(0, cut).replace(Regex("[\\s-]+$"), "").trim()
        info.title = title.ifEmpty { null }

        info.source = source(n)
        RESOLUTION.find(n)?.let {
            val res = it.groupValues[1].lowercase(Locale.ROOT)
            info.resolution = if (res == "4k" || res == "uhd") "2160p" else res
        }
        return info
    }

    /** Normalised source: BluRay, WEB, HDTV, DVD, HDRip, CAM or null. */
    @JvmStatic
    fun source(text: String?): String? {
        if (text == null) return null
        val t = " " + text.lowercase(Locale.ROOT).replace(Regex("[._\\-\\[\\]()]+"), " ") + " "
        return when {
            Regex(".*\\b(bluray|blu ray|bdrip|brrip|bdremux|remux|bd)\\b.*").matches(t) -> "BluRay"
            Regex(".*\\b(web ?dl|webrip|web|amzn|nf|atv|dsnp|hmax|hulu|pcok|skst|itunes|aptv)\\b.*").matches(t) -> "WEB"
            Regex(".*\\bhdtv\\b.*").matches(t) -> "HDTV"
            Regex(".*\\b(dvdrip|dvd|dvd5|dvd9)\\b.*").matches(t) -> "DVD"
            Regex(".*\\bhdrip\\b.*").matches(t) -> "HDRip"
            Regex(".*\\b(cam|hdcam|ts|telesync|hdts)\\b.*").matches(t) -> "CAM"
            else -> null
        }
    }
}
