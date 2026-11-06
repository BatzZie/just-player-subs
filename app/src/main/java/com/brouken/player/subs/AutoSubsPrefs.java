package com.brouken.player.subs;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** Settings and per-file memory (offset, chosen subtitle). Kept apart from upstream Prefs. */
final class AutoSubsPrefs {

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_LANG1 = "lang1";
    private static final String KEY_LANG2 = "lang2";
    static final String CHOICE_OFF = "off";

    private final SharedPreferences sp;

    AutoSubsPrefs(Context context) {
        sp = context.getSharedPreferences("autosubs", Context.MODE_PRIVATE);
    }

    boolean isEnabled() {
        return sp.getBoolean(KEY_ENABLED, true);
    }

    void setEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    String language1() {
        return sp.getString(KEY_LANG1, Languages.deviceLanguage());
    }

    /** Empty string = no second language. */
    String language2() {
        String def = "eng".equals(language1()) ? "" : "eng";
        return sp.getString(KEY_LANG2, def);
    }

    void setLanguage1(String code) {
        sp.edit().putString(KEY_LANG1, code).apply();
    }

    void setLanguage2(String code) {
        sp.edit().putString(KEY_LANG2, code).apply();
    }

    List<String> languages() {
        List<String> list = new ArrayList<>();
        list.add(language1());
        String l2 = language2();
        if (!l2.isEmpty() && !Languages.matches(l2, language1())) list.add(l2);
        return list;
    }

    long offset(String fileKey) {
        return fileKey == null ? 0 : sp.getLong("offset:" + fileKey, 0);
    }

    void setOffset(String fileKey, long offsetMs) {
        if (fileKey == null) return;
        if (offsetMs == 0) sp.edit().remove("offset:" + fileKey).apply();
        else sp.edit().putLong("offset:" + fileKey, offsetMs).apply();
    }

    String choice(String fileKey) {
        return fileKey == null ? null : sp.getString("choice:" + fileKey, null);
    }

    void setChoice(String fileKey, String choice) {
        if (fileKey == null) return;
        if (choice == null) sp.edit().remove("choice:" + fileKey).apply();
        else sp.edit().putString("choice:" + fileKey, choice).apply();
    }
}
