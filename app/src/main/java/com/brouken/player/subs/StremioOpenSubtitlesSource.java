package com.brouken.player.subs;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The public OpenSubtitles v3 Stremio addon. No API key needed; downloads are already converted to UTF-8. */
public final class StremioOpenSubtitlesSource implements SubtitleSource {

    private static final String BASE = "https://opensubtitles-v3.strem.io";

    @Override
    public String name() {
        return "OpenSubtitles";
    }

    @Override
    public byte[] download(SubtitleCandidate candidate) throws IOException {
        return Http.getBytes(candidate.url, 4 * 1024 * 1024);
    }

    @Override
    public List<SubtitleCandidate> search(MediaId id, String hash, long size, String fileName, List<String> languages)
            throws IOException {
        StringBuilder extra = new StringBuilder();
        if (fileName != null) append(extra, "filename", fileName);
        if (hash != null && size > 0) {
            append(extra, "videoHash", hash);
            append(extra, "videoSize", String.valueOf(size));
        }
        String url = BASE + "/subtitles/" + id.type() + "/" + Uri.encode(id.videoId(), ":")
                + (extra.length() > 0 ? "/" + extra : "") + ".json";

        List<SubtitleCandidate> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            JSONArray subs = new JSONObject(Http.getString(url)).optJSONArray("subtitles");
            if (subs == null) return result;
            for (int i = 0; i < subs.length(); i++) {
                JSONObject s = subs.getJSONObject(i);
                SubtitleCandidate c = new SubtitleCandidate();
                c.legacyId = s.optString("id");
                c.id = "os3:" + c.legacyId;
                c.source = this;
                c.url = s.optString("url");
                c.lang = Languages.canonical(s.optString("lang"));
                if (c.url.isEmpty() || !seen.add(c.legacyId.isEmpty() ? c.url : c.legacyId)) continue;
                c.releaseName = s.optString("movieReleaseName", s.optString("subtitleFileName"));
                // "m" tells how the addon matched: "i" = by IMDb id, "h" = by file hash
                c.hashMatch = "h".equals(s.optString("m"));
                c.fpsMilli = s.optInt("fpsMilli", -1);
                c.release = ReleaseNameParser.parse(c.releaseName);
                if (c.release.group == null) {
                    String g = s.optString("releaseGroup", "");
                    if (!g.isEmpty() && !g.equals("null")) c.release.group = g;
                }
                if (c.release.source == null) {
                    c.release.source = ReleaseNameParser.source(s.optString("releaseFormat", ""));
                }
                result.add(c);
            }
        } catch (JSONException e) {
            throw new IOException("Bad addon response", e);
        }
        return result;
    }

    private static void append(StringBuilder sb, String key, String value) {
        if (sb.length() > 0) sb.append('&');
        sb.append(key).append('=').append(Uri.encode(value));
    }
}
