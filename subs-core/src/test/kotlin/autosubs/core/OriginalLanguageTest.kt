package autosubs.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OriginalLanguageTest {
    private val anime = listOf("Animation", "Action")
    private val japan = listOf("Japan")

    @Test fun animeIsJapaneseInEveryModeButOff() {
        for (mode in listOf(OriginalLanguage.ANIME, OriginalLanguage.ASIAN, OriginalLanguage.ALL)) {
            assertEquals("ja", OriginalLanguage.languageFor(mode, anime, japan))
        }
        assertEquals("ja", OriginalLanguage.languageFor(OriginalLanguage.ANIME, anime, listOf("Japan", "United States")))
        assertNull(OriginalLanguage.languageFor(OriginalLanguage.OFF, anime, japan))
    }

    @Test fun westernAnimationIsNoAnime() =
        assertNull(OriginalLanguage.languageFor(OriginalLanguage.ANIME, anime, listOf("United States")))

    @Test fun koreanDrama() {
        val drama = listOf("Drama")
        val korea = listOf("South Korea")
        assertNull(OriginalLanguage.languageFor(OriginalLanguage.ANIME, drama, korea))
        assertEquals("ko", OriginalLanguage.languageFor(OriginalLanguage.ASIAN, drama, korea))
        assertNull(OriginalLanguage.languageFor(OriginalLanguage.ASIAN, drama, listOf("France")))
        assertEquals("fr", OriginalLanguage.languageFor(OriginalLanguage.ALL, drama, listOf("France")))
        assertNull(OriginalLanguage.languageFor(OriginalLanguage.ALL, drama, emptyList()))
    }
}
