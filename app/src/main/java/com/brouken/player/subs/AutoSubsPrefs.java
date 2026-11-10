package com.brouken.player.subs;

import autosubs.core.Languages;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings (shown in the regular settings screen, stored in the default preferences with an "autosubs_" prefix)
 * and per-file memory (offset, chosen subtitle) in a separate file.
 */
final class AutoSubsPrefs {

    static final String KEY_ENABLED = "autosubs_enabled";
    static final String KEY_LANG1 = "autosubs_lang1";
    static final String KEY_LANG2 = "autosubs_lang2";
    static final String KEY_PREFER_EMBEDDED = "autosubs_prefer_embedded";
    static final String KEY_AUTO_SYNC = "autosubs_auto_sync";
    static final String KEY_OS_API_KEY = "autosubs_os_api_key";
    static final String KEY_OS_USERNAME = "autosubs_os_username";
    static final String KEY_OS_PASSWORD = "autosubs_os_password";
    static final String CHOICE_OFF = "off";
    static final String KEY_AUDIO_ORIGINAL = "audio_original";
    static final String KEY_STREMIO_SYNC = "stremio_sync";
    static final String KEY_STREMIO_AUTH = "stremio_auth_key";
    static final String KEY_STREMIO_EMAIL = "stremio_email";

    private final SharedPreferences settings;
    private final SharedPreferences files;

    AutoSubsPrefs(Context context) {
        settings = PreferenceManager.getDefaultSharedPreferences(context);
        files = context.getSharedPreferences("autosubs", Context.MODE_PRIVATE);
    }

    boolean isEnabled() {
        return settings.getBoolean(KEY_ENABLED, true);
    }

    boolean preferEmbedded() {
        return settings.getBoolean(KEY_PREFER_EMBEDDED, true);
    }

    boolean autoSync() {
        return settings.getBoolean(KEY_AUTO_SYNC, true);
    }

    static String defaultLanguage1() {
        return Languages.deviceLanguage();
    }

    String language1() {
        return settings.getString(KEY_LANG1, defaultLanguage1());
    }

    /** Empty string = no second language. */
    String language2() {
        String def = "eng".equals(language1()) ? "" : "eng";
        return settings.getString(KEY_LANG2, def);
    }

    List<String> languages() {
        List<String> list = new ArrayList<>();
        list.add(language1());
        String l2 = language2();
        if (!l2.isEmpty() && !Languages.matches(l2, language1())) list.add(l2);
        return list;
    }

    /** Login key for the Stremio account, or null when not logged in or sync is off. */
    String stremioAuthKeyForSync() {
        if (!settings.getBoolean(KEY_STREMIO_SYNC, true)) return null;
        String k = settings.getString(KEY_STREMIO_AUTH, "");
        return k.isEmpty() ? null : k;
    }

    String stremioEmail() {
        return settings.getString(KEY_STREMIO_EMAIL, "");
    }

    void setStremioLogin(String authKey, String email) {
        if (authKey == null) settings.edit().remove(KEY_STREMIO_AUTH).remove(KEY_STREMIO_EMAIL).apply();
        else settings.edit().putString(KEY_STREMIO_AUTH, authKey).putString(KEY_STREMIO_EMAIL, email).apply();
    }

    String openSubtitlesApiKey() {
        return settings.getString(KEY_OS_API_KEY, "").trim();
    }

    String openSubtitlesUsername() {
        return settings.getString(KEY_OS_USERNAME, "").trim();
    }

    String openSubtitlesPassword() {
        return settings.getString(KEY_OS_PASSWORD, "");
    }

    long offset(String fileKey) {
        return fileKey == null ? 0 : files.getLong("offset:" + fileKey, 0);
    }

    /** True only for an offset the user set in timing mode; automatic results may be recomputed. */
    boolean hasUserOffset(String fileKey) {
        return fileKey != null && files.getBoolean("offsetUser:" + fileKey, false);
    }

    void setOffset(String fileKey, long offsetMs, boolean byUser) {
        if (fileKey == null) return;
        files.edit().putLong("offset:" + fileKey, offsetMs).putBoolean("offsetUser:" + fileKey, byUser).apply();
    }

    void removeOffset(String fileKey) {
        if (fileKey != null) files.edit().remove("offset:" + fileKey).remove("offsetUser:" + fileKey).apply();
    }

    /** Last position where the user stopped this video ("tt123:S:E"), with the time it was saved. */
    void setResume(String videoId, long positionMs, boolean finished) {
        if (videoId == null) return;
        if (finished) files.edit().remove("resume:" + videoId).apply();
        else files.edit().putString("resume:" + videoId, positionMs + "," + System.currentTimeMillis()).apply();
    }

    /** @return saved position, or -1 if none (or older than 30 days) */
    long resume(String videoId) {
        if (videoId == null) return -1;
        String v = files.getString("resume:" + videoId, null);
        if (v == null) return -1;
        try {
            String[] parts = v.split(",");
            long saved = Long.parseLong(parts[1]);
            if (System.currentTimeMillis() - saved > 30L * 24 * 3600_000L) return -1;
            return Long.parseLong(parts[0]);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Original-language audio: off, anime, asian or all (autosubs.core.OriginalLanguage). */
    String audioOriginal() {
        return settings.getString(KEY_AUDIO_ORIGINAL, autosubs.core.OriginalLanguage.ANIME);
    }

    /** Audio language the user chose for a series or movie (by IMDb id), or null. */
    String audioChoice(String imdbId) {
        return imdbId == null ? null : files.getString("audio:" + imdbId, null);
    }

    void setAudioChoice(String imdbId, String language) {
        if (imdbId != null && language != null) files.edit().putString("audio:" + imdbId, language).apply();
    }

    String choice(String fileKey) {
        return fileKey == null ? null : files.getString("choice:" + fileKey, null);
    }

    void setChoice(String fileKey, String choice) {
        if (fileKey == null) return;
        if (choice == null) files.edit().remove("choice:" + fileKey).apply();
        else files.edit().putString("choice:" + fileKey, choice).apply();
    }
}
