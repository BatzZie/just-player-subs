package com.brouken.player.subs;

import java.util.Locale;

/**
 * Language codes. OpenSubtitles mixes ISO 639-2/T ("nld") and 639-2/B ("fre") codes, so we compare on aliases.
 * Internally we always store the 639-2/T code as returned by {@link Locale#getISO3Language()}.
 */
public final class Languages {

    private static final String[][] ALIASES = {
            {"nld", "dut"}, {"deu", "ger"}, {"fra", "fre"}, {"ces", "cze"}, {"zho", "chi", "zht", "zhe"},
            {"ell", "gre"}, {"ron", "rum"}, {"slk", "slo"}, {"sqi", "alb"}, {"hye", "arm"}, {"eus", "baq"},
            {"fas", "per"}, {"kat", "geo"}, {"isl", "ice"}, {"mkd", "mac"}, {"msa", "may"}, {"mya", "bur"},
            {"cym", "wel"}, {"nor", "nob", "nno"}, {"srp", "scc"}, {"hrv", "scr"},
    };

    /** Choices offered in the language picker (639-2/T, "pob" = Brazilian Portuguese as used by OpenSubtitles). */
    public static final String[] COMMON = {
            "nld", "eng", "deu", "fra", "spa", "ita", "por", "pob", "swe", "nor", "dan", "fin", "isl", "pol", "ces",
            "slk", "hun", "ron", "bul", "hrv", "srp", "slv", "ell", "tur", "rus", "ukr", "est", "lav", "lit",
            "heb", "ara", "fas", "hin", "ind", "msa", "vie", "tha", "zho", "jpn", "kor",
    };

    private Languages() {}

    public static String canonical(String code) {
        if (code == null) return null;
        String c = code.toLowerCase(Locale.ROOT).trim();
        if (c.length() == 2 || c.contains("-") || c.contains("_")) {
            if (c.equals("pt-br") || c.equals("pt_br")) return "pob";
            try {
                c = new Locale(c.split("[-_]")[0]).getISO3Language();
            } catch (Exception ignored) {
            }
        }
        for (String[] group : ALIASES) {
            for (String alias : group) {
                if (alias.equals(c)) return group[0];
            }
        }
        return c;
    }

    public static boolean matches(String a, String b) {
        String ca = canonical(a);
        return ca != null && ca.equals(canonical(b));
    }

    public static String deviceLanguage() {
        try {
            String c = canonical(Locale.getDefault().getISO3Language());
            if (c != null && !c.isEmpty()) return c;
        } catch (Exception ignored) {
        }
        return "eng";
    }

    public static String displayName(String code) {
        if (code == null || code.isEmpty()) return "-";
        if (code.equals("pob")) {
            return new Locale("pt", "BR").getDisplayName();
        }
        String name = new Locale(code).getDisplayLanguage();
        if (name == null || name.isEmpty() || name.equalsIgnoreCase(code)) return code;
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
