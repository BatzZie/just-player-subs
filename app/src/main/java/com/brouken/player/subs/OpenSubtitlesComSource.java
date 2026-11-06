package com.brouken.player.subs;

import android.util.Log;

import com.brouken.player.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Official OpenSubtitles.com REST API. Needs a (free) API key from opensubtitles.com/consumers.
 * Username/password are optional; logging in raises the daily download limit.
 */
public final class OpenSubtitlesComSource implements SubtitleSource {

    private static final String DEFAULT_HOST = "api.opensubtitles.com";

    private final String apiKey;
    private final String username;
    private final String password;

    // Login token is shared between instances (one login per app run)
    private static String token;
    private static String tokenUser;
    private static String host = DEFAULT_HOST;

    public OpenSubtitlesComSource(String apiKey, String username, String password) {
        this.apiKey = apiKey;
        this.username = username;
        this.password = password;
    }

    @Override
    public String name() {
        return "OpenSubtitles.com";
    }

    private Map<String, String> headers(boolean auth) {
        Map<String, String> h = new HashMap<>();
        h.put("Api-Key", apiKey);
        h.put("User-Agent", "JustPlayerSubs v" + BuildConfig.VERSION_NAME);
        h.put("Accept", "application/json");
        if (auth && token != null) h.put("Authorization", "Bearer " + token);
        return h;
    }

    @Override
    public List<SubtitleCandidate> search(MediaId id, String hash, long size, String fileName, List<String> languages)
            throws IOException {
        // Parameters must be sorted and lower case, otherwise the API answers with a redirect
        TreeMap<String, String> params = new TreeMap<>();
        String imdbNumber = id.imdbId.replaceFirst("^tt0*", "");
        if (id.isSeries()) {
            params.put("parent_imdb_id", imdbNumber);
            params.put("season_number", String.valueOf(id.season));
            params.put("episode_number", String.valueOf(id.episode));
        } else {
            params.put("imdb_id", imdbNumber);
        }
        List<String> langs = new ArrayList<>();
        for (String l : languages) {
            String iso1 = Languages.toIso1(l);
            if (iso1 != null && !langs.contains(iso1)) langs.add(iso1);
        }
        java.util.Collections.sort(langs);
        if (!langs.isEmpty()) params.put("languages", android.text.TextUtils.join(",", langs));
        if (hash != null) params.put("moviehash", hash);

        StringBuilder url = new StringBuilder("https://" + host + "/api/v1/subtitles");
        char sep = '?';
        for (Map.Entry<String, String> p : params.entrySet()) {
            url.append(sep).append(p.getKey()).append('=').append(android.net.Uri.encode(p.getValue(), ","));
            sep = '&';
        }

        Http.Response r = Http.request("GET", url.toString(), headers(false), null);
        if (r.code != 200) throw new IOException("OpenSubtitles.com search HTTP " + r.code + ": " + shorten(r.body));

        List<SubtitleCandidate> result = new ArrayList<>();
        try {
            JSONArray data = new JSONObject(r.body).optJSONArray("data");
            if (data == null) return result;
            for (int i = 0; i < data.length(); i++) {
                JSONObject a = data.getJSONObject(i).optJSONObject("attributes");
                if (a == null) continue;
                JSONArray files = a.optJSONArray("files");
                if (files == null || files.length() != 1) continue; // skip multi-CD
                JSONObject file = files.getJSONObject(0);
                SubtitleCandidate c = new SubtitleCandidate();
                c.source = this;
                c.fileId = file.optLong("file_id", -1);
                if (c.fileId < 0) continue;
                c.id = "osc:" + c.fileId;
                c.legacyId = a.has("legacy_subtitle_id") ? String.valueOf(a.optLong("legacy_subtitle_id")) : null;
                c.lang = Languages.canonical(a.optString("language"));
                c.releaseName = a.optString("release", file.optString("file_name"));
                c.hashMatch = a.optBoolean("moviehash_match", false);
                double fps = a.optDouble("fps", 0);
                c.fpsMilli = fps > 0 ? (int) Math.round(fps * 1000) : -1;
                c.machineTranslated = a.optBoolean("machine_translated", false) || a.optBoolean("ai_translated", false);
                c.release = ReleaseNameParser.parse(c.releaseName);
                result.add(c);
            }
        } catch (JSONException e) {
            throw new IOException("Bad OpenSubtitles.com response", e);
        }
        return result;
    }

    @Override
    public byte[] download(SubtitleCandidate candidate) throws IOException {
        ensureLogin();
        String body = "{\"file_id\":" + candidate.fileId + "}";
        Http.Response r = Http.request("POST", "https://" + host + "/api/v1/download", headers(true), body);
        if (r.code == 401 && token != null) {
            token = null;
            ensureLogin();
            r = Http.request("POST", "https://" + host + "/api/v1/download", headers(true), body);
        }
        if (r.code != 200) throw new IOException("OpenSubtitles.com download HTTP " + r.code + ": " + shorten(r.body));
        try {
            JSONObject json = new JSONObject(r.body);
            Log.i(AutoSubs.TAG, "OpenSubtitles.com downloads remaining: " + json.optString("remaining"));
            String link = json.getString("link");
            return Http.getBytes(link, 4 * 1024 * 1024);
        } catch (JSONException e) {
            throw new IOException("Bad download response", e);
        }
    }

    private void ensureLogin() {
        if (username.isEmpty() || password.isEmpty()) return;
        if (token != null && username.equals(tokenUser)) return;
        try {
            JSONObject body = new JSONObject();
            body.put("username", username);
            body.put("password", password);
            Http.Response r = Http.request("POST", "https://" + DEFAULT_HOST + "/api/v1/login", headers(false),
                    body.toString());
            if (r.code != 200) {
                Log.w(AutoSubs.TAG, "OpenSubtitles.com login failed: HTTP " + r.code);
                return;
            }
            JSONObject json = new JSONObject(r.body);
            token = json.optString("token", null);
            tokenUser = username;
            String baseUrl = json.optString("base_url", "");
            host = baseUrl.isEmpty() ? DEFAULT_HOST : baseUrl.replaceFirst("^https?://", "").replaceAll("/.*$", "");
            Log.i(AutoSubs.TAG, "OpenSubtitles.com login ok, host " + host);
        } catch (Exception e) {
            Log.w(AutoSubs.TAG, "OpenSubtitles.com login failed: " + e);
        }
    }

    private static String shorten(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
