package com.brouken.player.subs;

import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Works out the IMDb id of what is playing: from the URL if it contains one, otherwise via Cinemeta. */
public final class MediaIdentifier {

    private static final String CINEMETA = "https://v3-cinemeta.strem.io";
    private static final Pattern IMDB_IN_URL = Pattern.compile("\\b(tt\\d{5,10})(?::(\\d{1,3}):(\\d{1,4}))?");
    private static final Pattern YEAR = Pattern.compile("(\\d{4})");

    private MediaIdentifier() {}

    /** Some addons (e.g. StremThru) put "tt123:2:1" in the stream URL. */
    public static MediaId fromUrl(String url, ReleaseInfo info) {
        if (url == null) return null;
        Matcher m = IMDB_IN_URL.matcher(Uri.decode(url));
        if (!m.find()) return null;
        if (m.group(2) != null) {
            return new MediaId(m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        }
        if (info != null && info.isEpisode()) {
            return new MediaId(m.group(1), info.season, info.episode);
        }
        return new MediaId(m.group(1), -1, -1);
    }

    public static MediaId lookup(ReleaseInfo info) {
        if (info == null || info.title == null) return null;
        String type = info.isEpisode() ? "series" : "movie";
        String id = search(type, info.title, info.year);
        if (id == null && info.year > 0) {
            // A year can be part of the title, e.g. "Blade Runner 2049"
            id = search(type, info.title + " " + info.year, -1);
        }
        if (id == null) return null;
        return info.isEpisode() ? new MediaId(id, info.season, info.episode) : new MediaId(id, -1, -1);
    }

    private static String search(String type, String title, int year) {
        String url = CINEMETA + "/catalog/" + type + "/top/search=" + Uri.encode(title) + ".json";
        try {
            JSONArray metas = new JSONObject(Http.getString(url)).optJSONArray("metas");
            if (metas == null) return null;
            String wanted = normalize(title);
            String bestId = null;
            int bestScore = 0;
            for (int i = 0; i < metas.length() && i < 20; i++) {
                JSONObject meta = metas.getJSONObject(i);
                String id = meta.optString("imdb_id", meta.optString("id"));
                if (!id.startsWith("tt")) continue;
                String name = normalize(meta.optString("name"));
                int score = 0;
                if (name.equals(wanted)) score += 10;
                else if (name.startsWith(wanted) || wanted.startsWith(name)) score += 3;
                else continue;
                int metaYear = parseYear(meta.optString("releaseInfo", meta.optString("year")));
                if (year > 0 && metaYear > 0) {
                    int diff = Math.abs(metaYear - year);
                    if (diff == 0) score += 6;
                    else if (diff == 1) score += 3;
                    else if (!type.equals("series")) score -= 5;
                }
                score -= i / 5; // slight preference for Cinemeta's own ordering (popularity)
                if (score > bestScore) {
                    bestScore = score;
                    bestId = id;
                }
            }
            Log.i(AutoSubs.TAG, "Cinemeta " + type + " '" + title + "' (" + year + ") -> " + bestId);
            return bestId;
        } catch (Exception e) {
            Log.w(AutoSubs.TAG, "Cinemeta search failed: " + e);
            return null;
        }
    }

    static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replace("&", "and").replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private static int parseYear(String s) {
        if (s == null) return -1;
        Matcher m = YEAR.matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }
}
