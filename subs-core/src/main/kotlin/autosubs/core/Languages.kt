package autosubs.core

import java.util.Locale

/**
 * Language codes. OpenSubtitles mixes ISO 639-2/T ("nld") and 639-2/B ("fre") codes, so we compare on aliases.
 * Internally we always store the 639-2/T code as returned by [Locale.getISO3Language].
 */
object Languages {

    private val ALIASES = arrayOf(
        arrayOf("nld", "dut"), arrayOf("deu", "ger"), arrayOf("fra", "fre"), arrayOf("ces", "cze"),
        arrayOf("zho", "chi", "zht", "zhe"), arrayOf("ell", "gre"), arrayOf("ron", "rum"), arrayOf("slk", "slo"),
        arrayOf("sqi", "alb"), arrayOf("hye", "arm"), arrayOf("eus", "baq"), arrayOf("fas", "per"), arrayOf("kat", "geo"),
        arrayOf("isl", "ice"), arrayOf("mkd", "mac"), arrayOf("msa", "may"), arrayOf("mya", "bur"), arrayOf("cym", "wel"),
        arrayOf("nor", "nob", "nno"), arrayOf("srp", "scc"), arrayOf("hrv", "scr"),
    )

    /** Choices offered in the language picker (639-2/T, "pob" = Brazilian Portuguese as used by OpenSubtitles). */
    @JvmField
    val COMMON = arrayOf(
        "nld", "eng", "deu", "fra", "spa", "ita", "por", "pob", "swe", "nor", "dan", "fin", "isl", "pol", "ces",
        "slk", "hun", "ron", "bul", "hrv", "srp", "slv", "ell", "tur", "rus", "ukr", "est", "lav", "lit",
        "heb", "ara", "fas", "hin", "ind", "msa", "vie", "tha", "zho", "jpn", "kor",
    )

    @JvmStatic
    fun canonical(code: String?): String? {
        if (code == null) return null
        var c = code.lowercase(Locale.ROOT).trim()
        if (c.length == 2 || c.contains("-") || c.contains("_")) {
            if (c == "pt-br" || c == "pt_br") return "pob"
            try {
                c = Locale(c.split(Regex("[-_]"))[0]).isO3Language
            } catch (ignored: Exception) {
            }
        }
        for (group in ALIASES) if (c in group) return group[0]
        return c
    }

    /** Two letter code as used by the OpenSubtitles.com API ("nl", "pt-br", ...). */
    @JvmStatic
    fun toIso1(code: String?): String? {
        val c = canonical(code) ?: return null
        when (c) {
            "pob" -> return "pt-br"
            "por" -> return "pt-pt"
            "zho" -> return "zh-cn"
        }
        for (iso1 in Locale.getISOLanguages()) {
            try {
                if (canonical(Locale(iso1).isO3Language) == c) return iso1
            } catch (ignored: Exception) {
            }
        }
        return null
    }

    @JvmStatic
    fun matches(a: String?, b: String?): Boolean {
        val ca = canonical(a)
        return ca != null && ca == canonical(b)
    }

    @JvmStatic
    fun deviceLanguage(): String {
        try {
            val c = canonical(Locale.getDefault().isO3Language)
            if (!c.isNullOrEmpty()) return c
        } catch (ignored: Exception) {
        }
        return "eng"
    }

    @JvmStatic
    fun displayName(code: String?): String {
        if (code.isNullOrEmpty()) return "-"
        if (code == "pob") return Locale("pt", "BR").displayName
        val name = Locale(code).displayLanguage
        if (name.isNullOrEmpty() || name.equals(code, ignoreCase = true)) return code
        return name.substring(0, 1).uppercase() + name.substring(1)
    }
}
