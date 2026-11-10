package autosubs.core

/**
 * Audio in the original language: anime in Japanese, and optionally Asian titles or everything in the language
 * of its (first) country. Cinemeta has no language field, only genres and countries.
 */
object OriginalLanguage {
    const val OFF = "off"
    const val ANIME = "anime"
    const val ASIAN = "asian"
    const val ALL = "all"

    private val COUNTRY_LANGUAGE = mapOf(
        "Japan" to "ja", "South Korea" to "ko", "Korea" to "ko", "China" to "zh", "Hong Kong" to "zh", "Taiwan" to "zh",
        "Thailand" to "th", "India" to "hi", "Indonesia" to "id", "Philippines" to "tl", "Vietnam" to "vi",
        "United States" to "en", "United Kingdom" to "en", "Canada" to "en", "Australia" to "en", "Ireland" to "en",
        "New Zealand" to "en", "France" to "fr", "Germany" to "de", "Austria" to "de", "Spain" to "es",
        "Mexico" to "es", "Argentina" to "es", "Colombia" to "es", "Chile" to "es", "Italy" to "it",
        "Portugal" to "pt", "Brazil" to "pt", "Netherlands" to "nl", "Denmark" to "da", "Sweden" to "sv",
        "Norway" to "no", "Finland" to "fi", "Iceland" to "is", "Poland" to "pl", "Czech Republic" to "cs",
        "Czechia" to "cs", "Hungary" to "hu", "Greece" to "el", "Romania" to "ro", "Russia" to "ru",
        "Ukraine" to "uk", "Turkey" to "tr", "Israel" to "he", "Iran" to "fa", "Egypt" to "ar",
    )
    private val ASIAN_COUNTRIES = setOf(
        "Japan", "South Korea", "Korea", "China", "Hong Kong", "Taiwan", "Thailand", "India", "Indonesia",
        "Philippines", "Vietnam",
    )

    @JvmStatic
    fun isAnime(genres: List<String>, countries: List<String>): Boolean =
        genres.any { it.equals("Animation", ignoreCase = true) || it.equals("Anime", ignoreCase = true) } &&
            countries.firstOrNull() == "Japan"

    /** The audio language (ISO 639-1) to prefer for a title, or null to keep the normal setting. */
    @JvmStatic
    fun languageFor(mode: String?, genres: List<String>, countries: List<String>): String? {
        if (mode == null || mode == OFF) return null
        if (isAnime(genres, countries)) return "ja"
        val country = countries.firstOrNull() ?: return null
        return when (mode) {
            ASIAN -> if (country in ASIAN_COUNTRIES) COUNTRY_LANGUAGE[country] else null
            ALL -> COUNTRY_LANGUAGE[country]
            else -> null
        }
    }
}
