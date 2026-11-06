package com.brouken.player.subs;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.text.InputType;

import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.brouken.player.R;

import java.util.ArrayList;
import java.util.List;

/** Adds the "Online subtitles" section to the settings screen. One call from SettingsFragment. */
public final class AutoSubsSettings {

    private AutoSubsSettings() {}

    public static void addTo(PreferenceFragmentCompat fragment) {
        PreferenceScreen screen = fragment.getPreferenceScreen();
        if (screen == null) return;
        Context ctx = fragment.requireContext();

        PreferenceCategory category = new PreferenceCategory(ctx);
        category.setTitle(R.string.autosubs_menu_title);
        category.setIconSpaceReserved(false);
        screen.addPreference(category);

        SwitchPreferenceCompat enabled = new SwitchPreferenceCompat(ctx);
        enabled.setKey(AutoSubsPrefs.KEY_ENABLED);
        enabled.setTitle(R.string.autosubs_pref_enabled);
        enabled.setSummary(R.string.autosubs_pref_enabled_summary);
        enabled.setDefaultValue(true);
        add(category, enabled);

        // Language lists
        String device = Languages.deviceLanguage();
        List<String> codes = new ArrayList<>();
        codes.add(device);
        for (String c : Languages.COMMON) if (!c.equals(device)) codes.add(c);
        List<String> names = new ArrayList<>();
        for (String c : codes) names.add(Languages.displayName(c));

        ListPreference lang1 = new ListPreference(ctx);
        lang1.setKey(AutoSubsPrefs.KEY_LANG1);
        lang1.setTitle(R.string.autosubs_pref_lang1);
        lang1.setDialogTitle(R.string.autosubs_pref_lang1);
        lang1.setEntries(names.toArray(new String[0]));
        lang1.setEntryValues(codes.toArray(new String[0]));
        lang1.setDefaultValue(AutoSubsPrefs.defaultLanguage1());
        lang1.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        add(category, lang1);

        List<String> codes2 = new ArrayList<>(codes);
        List<String> names2 = new ArrayList<>(names);
        codes2.add(0, "");
        names2.add(0, ctx.getString(R.string.autosubs_none));
        ListPreference lang2 = new ListPreference(ctx);
        lang2.setKey(AutoSubsPrefs.KEY_LANG2);
        lang2.setTitle(R.string.autosubs_pref_lang2);
        lang2.setDialogTitle(R.string.autosubs_pref_lang2);
        lang2.setEntries(names2.toArray(new String[0]));
        lang2.setEntryValues(codes2.toArray(new String[0]));
        lang2.setDefaultValue("eng".equals(AutoSubsPrefs.defaultLanguage1()) ? "" : "eng");
        lang2.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        add(category, lang2);

        SwitchPreferenceCompat embedded = new SwitchPreferenceCompat(ctx);
        embedded.setKey(AutoSubsPrefs.KEY_PREFER_EMBEDDED);
        embedded.setTitle(R.string.autosubs_pref_prefer_embedded);
        embedded.setSummary(R.string.autosubs_pref_prefer_embedded_summary);
        embedded.setDefaultValue(true);
        add(category, embedded);

        SwitchPreferenceCompat autoSync = new SwitchPreferenceCompat(ctx);
        autoSync.setKey(AutoSubsPrefs.KEY_AUTO_SYNC);
        autoSync.setTitle(R.string.autosubs_pref_auto_sync);
        autoSync.setSummary(R.string.autosubs_pref_auto_sync_summary);
        autoSync.setDefaultValue(true);
        add(category, autoSync);

        EditTextPreference apiKey = new EditTextPreference(ctx);
        apiKey.setKey(AutoSubsPrefs.KEY_OS_API_KEY);
        apiKey.setTitle(R.string.autosubs_pref_os_api_key);
        apiKey.setDialogMessage(R.string.autosubs_pref_os_api_key_help);
        apiKey.setSummaryProvider(p -> {
            String v = ((EditTextPreference) p).getText();
            return v == null || v.trim().isEmpty()
                    ? ctx.getString(R.string.autosubs_pref_os_api_key_summary) : "••••" + tail(v.trim());
        });
        add(category, apiKey);

        EditTextPreference user = new EditTextPreference(ctx);
        user.setKey(AutoSubsPrefs.KEY_OS_USERNAME);
        user.setTitle(R.string.autosubs_pref_os_username);
        user.setSummaryProvider(p -> {
            String v = ((EditTextPreference) p).getText();
            return v == null || v.trim().isEmpty() ? ctx.getString(R.string.autosubs_pref_os_username_summary) : v;
        });
        add(category, user);

        EditTextPreference password = new EditTextPreference(ctx);
        password.setKey(AutoSubsPrefs.KEY_OS_PASSWORD);
        password.setTitle(R.string.autosubs_pref_os_password);
        password.setOnBindEditTextListener(e ->
                e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
        password.setSummaryProvider(p -> {
            String v = ((EditTextPreference) p).getText();
            return v == null || v.isEmpty() ? "" : "••••••";
        });
        add(category, password);

        Preference system = new Preference(ctx);
        system.setTitle(R.string.autosubs_item_system);
        system.setSummary(R.string.autosubs_pref_system_summary);
        system.setOnPreferenceClickListener(p -> {
            try {
                fragment.startActivity(new Intent(Settings.ACTION_CAPTIONING_SETTINGS));
            } catch (Exception ignored) {
            }
            return true;
        });
        add(category, system);
    }

    private static void add(PreferenceCategory category, Preference p) {
        p.setIconSpaceReserved(false);
        category.addPreference(p);
    }

    private static String tail(String s) {
        return s.length() <= 4 ? "" : s.substring(s.length() - 4);
    }
}
