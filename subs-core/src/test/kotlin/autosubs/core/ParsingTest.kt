package autosubs.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseNameParserTest {
    private fun p(name: String) = ReleaseNameParser.parse(name).toString()

    @Test fun movieWithYearInBrackets() =
        assertEquals("title=The Batman year=2022 S-1E-1 group=null source=null res=null", p("The Batman (2022).mkv"))

    @Test fun episodeWithGroup() = assertEquals(
        "title=MobLand year=-1 S2E1 group=FLUX source=WEB res=2160p",
        p("MobLand.S02E01.I.Wanna.Be.Your.Dog.REPACK.2160p.ATV.WEB.DL.DDP5.1.Atmos.DV.HDR.H.265-FLUX.mkv"))

    @Test fun yearInTitle() = assertEquals(
        "title=Blade Runner 2049 year=2017 S-1E-1 group=EPSiLON source=BluRay res=2160p",
        p("Blade.Runner.2049.2017.2160p.UHD.BluRay.REMUX.HDR.HEVC.Atmos-EPSiLON.mkv"))

    @Test fun alternativeEpisodeFormat() =
        assertEquals("title=Show year=-1 S1E5 group=GRP source=HDTV res=720p", p("Show.1x05.720p.HDTV.x264-GRP.mkv"))

    @Test fun groupWithTag() =
        assertEquals("MeGusta", ReleaseNameParser.parse("MobLand.S02E01.1080p.HEVC.x265-MeGusta[EZTVx.to]").group)

    @Test fun animeWithSeason() = assertEquals(
        "title=Attack On Titan year=-1 S1E2 group=Salieri source=null res=1080p",
        p("[Salieri] Attack On Titan S1 - 02 (1080p) (HDR) [Dual Audio].mkv"))

    @Test fun animeAbsoluteEpisode() = assertEquals(
        "title=Shingeki no Kyojin year=-1 S-1E-1 abs=27 group=SubsPlease source=null res=1080p",
        p("[SubsPlease] Shingeki no Kyojin - 27 (1080p) [ABCD1234].mkv"))

    @Test fun dashWithoutLeadingGroupIsNoEpisode() =
        assertEquals(-1, ReleaseNameParser.parse("Mission - 2 Impossible.mkv").absoluteEpisode)

    @Test fun absoluteToSeasonEpisode() {
        val v = (1..2).flatMap { s -> (1..(if (s == 1) 25 else 12)).map { e -> WatchedBitField.Companion.Video("x:$s:$e", s.toLong(), e.toLong(), null) } } +
            WatchedBitField.Companion.Video("x:0:1", 0, 1, null)
        assertEquals("x:2:2", MediaIdentifier.absoluteToVideo(v.shuffled(), 27)!!.id)
    }

    @Test fun titleThatIsAYear() =
        assertEquals("title=2012 year=2009 S-1E-1 group=GRP source=BluRay res=1080p", p("2012.2009.1080p.BluRay.x264-GRP.mkv"))

    @Test fun webDlIsNotAGroup() {
        val r = ReleaseNameParser.parse("Mad.Max.Fury.Road.2015.1080p.WEB-DL.mkv")
        assertEquals("Mad Max Fury Road", r.title)
        assertNull(r.group)
        assertEquals("WEB", r.source)
    }
}

class UrlCodecTest {
    @Test fun roundTrip() {
        val s = "The Batman (2022) é.mkv"
        assertEquals(s, UrlCodec.decode(UrlCodec.encode(s)))
        assertEquals("tt1:2:3", UrlCodec.encode("tt1:2:3", ":"))
        assertEquals("a%20b", UrlCodec.encode("a b"))
    }

    @Test fun lastPathSegment() {
        assertEquals("The Batman (2022).mkv",
            UrlCodec.lastPathSegment("https://torrentio.example/resolve/realdebrid/TOKEN/hash/null/0/The%20Batman%20(2022).mkv"))
        assertEquals("x.mkv", UrlCodec.lastPathSegment("https://h/a/x.mkv?token=1#f"))
        assertNull(UrlCodec.lastPathSegment("https://h/"))
    }

    @Test fun imdbIdFromUrl() {
        val id = MediaIdentifier.fromUrl("https://stremthru.example/stremio/torz/eyJ/_/strem/tt31510819%3A2%3A1/rd/x/0/MobLand.mkv", null)
        assertEquals("series/tt31510819:2:1", id.toString())
        assertNull(MediaIdentifier.fromUrl("https://torrentio.example/resolve/realdebrid/TOKEN/abc/null/0/x.mkv", null))
    }
}

class LanguagesTest {
    @Test fun aliases() {
        assertEquals("nld", Languages.canonical("dut"))
        assertEquals("nld", Languages.canonical("nl"))
        assertEquals("deu", Languages.canonical("ger"))
        assertEquals("pob", Languages.canonical("pt-BR"))
        assertTrue(Languages.matches("fre", "fra"))
        assertFalse(Languages.matches("eng", "nld"))
    }

    @Test fun iso1() {
        assertEquals("nl", Languages.toIso1("dut"))
        assertEquals("en", Languages.toIso1("eng"))
        assertEquals("pt-br", Languages.toIso1("pob"))
    }
}

class SubtitleRankerTest {
    private fun cand(lang: String, release: String) = SubtitleCandidate().apply {
        this.lang = lang; releaseName = release; this.release = ReleaseNameParser.parse(release)
    }

    @Test fun languageFirstThenReleaseMatch() {
        val video = ReleaseNameParser.parse("Show.S01E02.1080p.WEB.H264-GRP.mkv")
        val all = listOf(
            cand("eng", "Show.S01E02.1080p.WEB.H264-GRP"),
            cand("nld", "Show.S01E02.720p.HDTV-OTHER"),
            cand("nld", "Show.S01E02.1080p.WEB.H264-GRP"),
            cand("fre", "Show.S01E02.1080p.WEB.H264-GRP"),
            cand("nld", "Show.S01E03.1080p.WEB.H264-GRP"),
        )
        val ranked = SubtitleRanker.rank(all, video, listOf("nld", "eng"))
        assertEquals(4, ranked.size)
        assertEquals("Show.S01E02.1080p.WEB.H264-GRP", ranked[0].releaseName)
        assertEquals("nld", ranked[0].lang)
        assertEquals("Show.S01E03.1080p.WEB.H264-GRP", ranked[2].releaseName) // wrong episode sinks
        assertEquals("eng", ranked[3].lang)
    }
}

class FrameRateTest {
    private val twoHours = 2 * 3600_000L

    @Test fun palSpeedUpFromMetadata() =
        assertEquals(25.0 / 23.976, FrameRate.detectScale((twoHours * 0.95).toLong(), 25.0, 23.976, twoHours), 1e-9)

    @Test fun noCorrectionForSameRate() = assertEquals(1.0, FrameRate.detectScale(twoHours - 60_000, 23.976, 23.976, twoHours))

    @Test fun ignoresSmallRatios() = assertEquals(1.0, FrameRate.detectScale(twoHours - 60_000, 24.0, 23.976, twoHours))

    @Test fun subtitleTooLongForVideo() =
        assertEquals(23.976 / 25.0, FrameRate.detectScale((twoHours * 1.04).toLong(), -1.0, -1.0, twoHours), 1e-9)

    @Test fun shortClipsAreLeftAlone() = assertEquals(1.0, FrameRate.detectScale(2_887_426, 23.976, -1.0, 30_000))
}

class SourceParsingTest {
    @Test fun addonResponse() {
        val json = """{"subtitles":[
            {"id":"3806284","url":"https://subs.example/1","lang":"nld","m":"i","fpsMilli":23976,
             "movieReleaseName":"Breaking Bad S01E01 Pilot DVDRip XviD ORPHEUS","releaseGroup":"ORPHEUS","releaseFormat":"DVD"},
            {"id":"3806284","url":"https://subs.example/1","lang":"nld","m":"i"},
            {"id":"9","url":"https://subs.example/9","lang":"eng","m":"h","subtitleFileName":"x.srt"},
            {"id":"10","url":"","lang":"eng"}]}"""
        val list = StremioOpenSubtitlesSource().parse(json)
        assertEquals(2, list.size)
        assertEquals("os3:3806284", list[0].id)
        assertEquals(23976, list[0].fpsMilli)
        assertEquals("DVD", list[0].release.source)
        assertTrue(list[1].hashMatch)
        assertEquals("x.srt", list[1].releaseName)
    }

    @Test fun openSubtitlesComResponse() {
        val json = """{"data":[
            {"attributes":{"language":"nl","release":"Movie.2020.1080p.WEB-DL-GRP","fps":23.976,"moviehash_match":true,
              "legacy_subtitle_id":123,"ai_translated":true,"files":[{"file_id":42,"file_name":"a.srt"}]}},
            {"attributes":{"language":"en","files":[{"file_id":1},{"file_id":2}]}}]}"""
        val list = OpenSubtitlesComSource("k", "", "", "test").parseSearch(json)
        assertEquals(1, list.size)
        val c = list[0]
        assertEquals("osc:42", c.id); assertEquals("nld", c.lang); assertEquals(23976, c.fpsMilli)
        assertTrue(c.hashMatch); assertTrue(c.machineTranslated); assertEquals("123", c.legacyId)
    }
}

class CinemetaMatchTest {
    private fun score(name: String, wanted: String) = MediaIdentifier.nameScore(MediaIdentifier.normalize(name), MediaIdentifier.normalize(wanted))

    @Test fun exactTitle() = assertEquals(10, score("The Batman", "the batman"))
    @Test fun shortLeftoverIsNoMatch() {
        assertEquals(0, score("Dvija", "DV"))
        assertEquals(0, score("Dvija", "Dvi"))
    }
    @Test fun muchLongerTitleIsNoMatch() = assertEquals(0, score("Fallout: The Ghoul Log", "Fallout"))
    @Test fun almostSameLengthIsPartial() = assertEquals(3, score("Spider-Man: Homecoming", "Spider-Man Homecomin"))
}
