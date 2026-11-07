package com.brouken.player.subs;

import autosubs.core.Languages;

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

        addStremioSettings(fragment, screen, ctx);
        addSkinSettings(screen, ctx);

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

    /** Stremio account: writes watch progress to the library (Stremio ignores results from unknown players). */
    private static void addStremioSettings(PreferenceFragmentCompat fragment, PreferenceScreen screen, Context ctx) {
        PreferenceCategory category = new PreferenceCategory(ctx);
        category.setTitle(R.string.stremio_category);
        screen.addPreference(category);
        AutoSubsPrefs prefs = new AutoSubsPrefs(ctx);

        Preference account = new Preference(ctx);
        account.setTitle(R.string.stremio_account);
        Runnable updateSummary = () -> {
            String email = prefs.stremioEmail();
            account.setSummary(email.isEmpty() ? ctx.getString(R.string.stremio_account_summary)
                    : ctx.getString(R.string.stremio_logged_in, email));
        };
        updateSummary.run();
        account.setOnPreferenceClickListener(p -> {
            if (!prefs.stremioEmail().isEmpty()) {
                new androidx.appcompat.app.AlertDialog.Builder(fragment.requireActivity())
                        .setTitle(R.string.stremio_account)
                        .setMessage(ctx.getString(R.string.stremio_logged_in, prefs.stremioEmail()))
                        .setPositiveButton(R.string.stremio_logout, (d, w) -> {
                            prefs.setStremioLogin(null, null);
                            updateSummary.run();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            } else {
                showStremioLogin(fragment, prefs, updateSummary);
            }
            return true;
        });
        add(category, account);

        SwitchPreferenceCompat sync = new SwitchPreferenceCompat(ctx);
        sync.setKey(AutoSubsPrefs.KEY_STREMIO_SYNC);
        sync.setTitle(R.string.stremio_sync);
        sync.setSummary(R.string.stremio_sync_summary);
        sync.setDefaultValue(true);
        add(category, sync);
    }

    /**
     * Login in two small steps (e-mail, then password): with a remote control it is hard or impossible to move
     * between two input fields while the on-screen keyboard is open.
     */
    private static void showStremioLogin(PreferenceFragmentCompat fragment, AutoSubsPrefs prefs, Runnable done) {
        android.app.Activity activity = fragment.requireActivity();
        askText(activity, R.string.stremio_email, prefs.stremioEmail(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, R.string.stremio_next,
                email -> askText(activity, R.string.stremio_password, "",
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, R.string.stremio_login,
                        password -> login(activity, prefs, email.trim(), password, done)));
    }

    private interface TextCallback {
        void onText(String text);
    }

    /** One input field per dialog; the keyboard's Next/Done key confirms. */
    private static void askText(android.app.Activity activity, int title, String initial, int inputType, int button,
                                TextCallback callback) {
        int pad = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        android.widget.FrameLayout box = new android.widget.FrameLayout(activity);
        box.setPadding(pad, pad / 2, pad, 0);
        android.widget.EditText input = new android.widget.EditText(activity);
        input.setInputType(inputType);
        input.setSingleLine(true);
        input.setText(initial);
        input.setSelection(input.getText().length());
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        box.addView(input);
        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(R.string.stremio_login_message)
                .setView(box)
                .setPositiveButton(button, (d, w) -> callback.onText(input.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        input.setOnEditorActionListener((v, actionId, event) -> {
            dialog.dismiss();
            callback.onText(input.getText().toString());
            return true;
        });
        dialog.setOnShowListener(d -> input.requestFocus());
        dialog.show();
    }

    private static void login(android.app.Activity activity, AutoSubsPrefs prefs, String email, String password, Runnable done) {
        if (email.isEmpty() || password.isEmpty()) return;
        new Thread(() -> {
            String error = null;
            try {
                autosubs.core.StremioApi.Login login = new autosubs.core.StremioApi().login(email, password);
                prefs.setStremioLogin(login.authKey, login.email);
            } catch (Exception ex) {
                error = ex.getMessage();
            }
            final String err = error;
            activity.runOnUiThread(() -> {
                done.run();
                android.widget.Toast.makeText(activity, err == null
                        ? activity.getString(R.string.stremio_logged_in, prefs.stremioEmail())
                        : activity.getString(R.string.stremio_login_failed, err),
                        android.widget.Toast.LENGTH_LONG).show();
            });
        }, "StremioLogin").start();
    }

    /** Separate section for the look of the player controls (PlayerSkin). */
    private static void addSkinSettings(PreferenceScreen screen, Context ctx) {
        PreferenceCategory category = new PreferenceCategory(ctx);
        category.setTitle(R.string.skin_category);
        screen.addPreference(category);

        SwitchPreferenceCompat enabled = new SwitchPreferenceCompat(ctx);
        enabled.setKey(PlayerSkin.KEY_ENABLED);
        enabled.setTitle(R.string.skin_enabled);
        enabled.setSummary(R.string.skin_enabled_summary);
        enabled.setDefaultValue(true);
        add(category, enabled);

        ListPreference accent = new ListPreference(ctx);
        accent.setKey(PlayerSkin.KEY_ACCENT);
        accent.setTitle(R.string.skin_accent);
        accent.setDialogTitle(R.string.skin_accent);
        accent.setEntries(ctx.getResources().getStringArray(R.array.skin_accent_names));
        accent.setEntryValues(new String[]{"#7B5CFA", "#3D8BFF", "#1FB6C9", "#2ECC71", "#FFB020", "#FF7A3D", "#FF4D5E", "#FF4FA3", "#FFFFFF"});
        accent.setDefaultValue(PlayerSkin.DEFAULT_ACCENT);
        accent.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        add(category, accent);

        ListPreference time = new ListPreference(ctx);
        time.setKey(PlayerSkin.KEY_TIME);
        time.setTitle(R.string.skin_time);
        time.setDialogTitle(R.string.skin_time);
        time.setEntries(ctx.getResources().getStringArray(R.array.skin_time_names));
        time.setEntryValues(new String[]{PlayerSkin.TIME_TOTAL, PlayerSkin.TIME_REMAINING, PlayerSkin.TIME_END});
        time.setDefaultValue(PlayerSkin.TIME_TOTAL);
        time.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        add(category, time);
    }

    private static void add(PreferenceCategory category, Preference p) {
        category.addPreference(p);
    }

    private static String tail(String s) {
        return s.length() <= 4 ? "" : s.substring(s.length() - 4);
    }
}
