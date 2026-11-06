package com.brouken.player.subs;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.text.Cue;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

import com.brouken.player.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Finds, loads and shows online subtitles for the playing video.
 * <p>
 * The subtitle is drawn by this class on the player's SubtitleView (not added as a Media3 track), so it can
 * arrive at any moment without reloading the stream, and timing changes apply instantly.
 * <p>
 * Hooks in PlayerActivity: attach() after setMediaItem, detach() on release, dispatchKeyEvent(), showMenu().
 */
public final class AutoSubs implements Player.Listener {

    public static final String TAG = "AutoSubs";

    private enum Status { IDLE, SEARCHING, FOUND, NONE, ERROR, DISABLED, UNSUPPORTED }

    private static final long TICK_MS = 100;
    private static final int MAX_SUBTITLE_BYTES = 4 * 1024 * 1024;

    private final Activity activity;
    private final AutoSubsPrefs prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "AutoSubs");
        t.setDaemon(true);
        return t;
    });
    private final SubtitleSource source = new StremioOpenSubtitlesSource();

    private Player player;
    private PlayerView playerView;

    // Per video
    private Uri mediaUri;
    private int generation;
    private Status status = Status.IDLE;
    private String fileName;
    private String fileKey;
    private ReleaseInfo videoInfo;
    private List<SubtitleCandidate> all = Collections.emptyList();
    private List<SubtitleCandidate> ranked = Collections.emptyList();
    private final Map<String, SubtitleTrack> trackCache = new HashMap<>();

    // Active subtitle
    private SubtitleCandidate active;
    private SubtitleTrack activeTrack;
    private double scale = 1.0;
    private long offsetMs;
    private boolean textDisabledByUs;

    // Waiting for the player to become ready (duration and frame rate are needed for activation)
    private SubtitleCandidate pending;
    private SubtitleTrack pendingTrack;
    private boolean pendingAuto;

    // Rendering
    private List<Cue> lastCues;
    private int ticksSinceSet;

    // Timing mode
    private boolean timingMode;
    private TextView overlay;

    public AutoSubs(Activity activity) {
        this.activity = activity;
        this.prefs = new AutoSubsPrefs(activity);
    }

    // ---------------------------------------------------------------- lifecycle

    public void attach(Player player, PlayerView playerView, Uri uri) {
        if (this.player != null && this.player != player) {
            this.player.removeListener(this);
        }
        this.player = player;
        this.playerView = playerView;
        textDisabledByUs = false;
        player.addListener(this);

        if (uri == null || !uri.equals(mediaUri)) {
            reset();
            mediaUri = uri;
            startSearch();
        } else if (active != null) {
            // Same video, new player instance (e.g. after returning to the app)
            disableBuiltInText();
            startTicking();
        }
    }

    public void detach() {
        exitTimingMode();
        stopTicking();
        clearCues();
        if (player != null) {
            player.removeListener(this);
        }
        player = null;
        textDisabledByUs = false;
    }

    private void reset() {
        generation++;
        exitTimingMode();
        deactivate();
        status = Status.IDLE;
        fileName = null;
        fileKey = null;
        videoInfo = null;
        all = Collections.emptyList();
        ranked = Collections.emptyList();
        trackCache.clear();
        pending = null;
        pendingTrack = null;
    }

    // ---------------------------------------------------------------- search

    private void startSearch() {
        if (mediaUri == null) return;
        String scheme = mediaUri.getScheme() == null ? "" : mediaUri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            status = Status.UNSUPPORTED;
            return;
        }
        fileName = mediaUri.getLastPathSegment();
        videoInfo = ReleaseNameParser.parse(fileName);
        fileKey = "name:" + fileName;
        Log.i(TAG, "Media: " + Redact.url(mediaUri));
        Log.i(TAG, "Parsed: " + videoInfo);
        if (!prefs.isEnabled()) {
            status = Status.DISABLED;
            return;
        }
        status = Status.SEARCHING;

        final int gen = generation;
        final String url = mediaUri.toString();
        final ReleaseInfo info = videoInfo;
        final String name = fileName;
        executor.execute(() -> {
            try {
                Future<OpenSubtitlesHash> hashFuture = executor.submit(() -> OpenSubtitlesHash.compute(url));
                MediaId id = MediaIdentifier.fromUrl(url, info);
                if (id == null) id = MediaIdentifier.lookup(info);
                OpenSubtitlesHash hash = null;
                try {
                    hash = hashFuture.get(20, TimeUnit.SECONDS);
                    Log.i(TAG, "Hash: " + hash.hash + " size " + hash.size);
                } catch (Exception e) {
                    hashFuture.cancel(true);
                    Log.w(TAG, "Hash failed: " + e);
                }
                Log.i(TAG, "Id: " + id);
                if (id == null) {
                    post(gen, () -> onSearchDone(Collections.emptyList(), null));
                    return;
                }
                List<SubtitleCandidate> found = source.search(id,
                        hash == null ? null : hash.hash, hash == null ? -1 : hash.size, name);
                final String key = hash == null ? null : "hash:" + hash.hash;
                post(gen, () -> onSearchDone(found, key));
            } catch (Exception e) {
                Log.w(TAG, "Search failed", e);
                post(gen, () -> status = Status.ERROR);
            }
        });
    }

    private void onSearchDone(List<SubtitleCandidate> found, String hashKey) {
        if (hashKey != null) fileKey = hashKey;
        all = found;
        rerank();
        status = ranked.isEmpty() ? Status.NONE : Status.FOUND;
        Log.i(TAG, "Found " + found.size() + " subtitles, " + ranked.size() + " in " + prefs.languages());
        for (int i = 0; i < Math.min(5, ranked.size()); i++) Log.i(TAG, "  #" + (i + 1) + " " + ranked.get(i));
        autoPick();
    }

    private void rerank() {
        ranked = SubtitleRanker.rank(all, videoInfo, prefs.languages());
    }

    private void autoPick() {
        if (ranked.isEmpty()) return;
        String remembered = prefs.choice(fileKey);
        if (AutoSubsPrefs.CHOICE_OFF.equals(remembered)) return;
        SubtitleCandidate c = find(remembered);
        boolean auto = c == null;
        if (c == null) c = ranked.get(0);
        select(c, auto);
    }

    private SubtitleCandidate find(String id) {
        if (id == null) return null;
        for (SubtitleCandidate c : ranked) if (id.equals(c.id)) return c;
        return null;
    }

    /** Downloads (if needed) and activates a subtitle. auto = picked by us, not by the user. */
    private void select(SubtitleCandidate c, boolean auto) {
        SubtitleTrack cached = trackCache.get(c.id);
        if (cached != null) {
            onTrackReady(c, cached, auto);
            return;
        }
        final int gen = generation;
        executor.execute(() -> {
            try {
                byte[] data = Http.getBytes(c.url, MAX_SUBTITLE_BYTES);
                SubtitleTrack track = SubtitleTrack.parse(data);
                if (track.size() == 0) throw new IllegalStateException("no cues");
                post(gen, () -> {
                    trackCache.put(c.id, track);
                    onTrackReady(c, track, auto);
                });
            } catch (Exception e) {
                Log.w(TAG, "Download failed for " + c.id + ": " + e);
                post(gen, () -> {
                    if (!auto) toast(activity.getString(R.string.autosubs_load_failed));
                    // Try the next one automatically
                    if (auto) {
                        int i = ranked.indexOf(c);
                        if (i >= 0 && i + 1 < ranked.size() && i < 3) select(ranked.get(i + 1), true);
                    }
                });
            }
        });
    }

    private void onTrackReady(SubtitleCandidate c, SubtitleTrack track, boolean auto) {
        if (player != null && player.getPlaybackState() == Player.STATE_READY) {
            activate(c, track, auto);
        } else {
            pending = c;
            pendingTrack = track;
            pendingAuto = auto;
        }
    }

    // ---------------------------------------------------------------- activation

    private void activate(SubtitleCandidate c, SubtitleTrack track, boolean auto) {
        pending = null;
        pendingTrack = null;
        if (player == null) return;

        if (auto) {
            Format embedded = betterEmbeddedTrack(c);
            if (embedded != null) {
                // Make sure the built-in one is actually shown (Just Player ignores "default" flags
                // when system captions are off)
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setPreferredTextLanguage(embedded.language)
                        .build());
                Log.i(TAG, "Using embedded subtitle " + embedded.language + " instead of " + c);
                toast(activity.getString(R.string.autosubs_embedded_used,
                        Languages.displayName(Languages.canonical(embedded.language))));
                return;
            }
        }

        active = c;
        activeTrack = track;
        long duration = player.getDuration();
        scale = track.detectScale(c.fpsMilli > 0 ? c.fpsMilli / 1000.0 : -1, videoFrameRate(),
                duration == C.TIME_UNSET ? -1 : duration);
        offsetMs = prefs.offset(fileKey);
        Log.i(TAG, "Active: " + c + " cues=" + track.size() + " lastEnd=" + track.lastEndMs()
                + " duration=" + duration + " videoFps=" + videoFrameRate() + " scale=" + scale
                + " offset=" + offsetMs);

        disableBuiltInText();
        startTicking();

        String msg = activity.getString(R.string.autosubs_loaded, c.label());
        if (scale != 1.0) msg += " (" + activity.getString(R.string.autosubs_fps_corrected) + ")";
        toast(msg);
    }

    private void deactivate() {
        active = null;
        activeTrack = null;
        stopTicking();
        clearCues();
        enableBuiltInText();
    }

    /** An embedded, non-forced text track in a language at least as preferred as the candidate's. */
    private Format betterEmbeddedTrack(SubtitleCandidate c) {
        List<String> languages = prefs.languages();
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < group.length; i++) {
                Format f = group.getTrackFormat(i);
                if ((f.selectionFlags & C.SELECTION_FLAG_FORCED) != 0) continue;
                if (f.label != null && f.label.toLowerCase(Locale.ROOT).contains("forced")) continue;
                if (f.language == null) continue;
                for (int rank = 0; rank < languages.size() && rank <= c.langRank; rank++) {
                    if (Languages.matches(f.language, languages.get(rank))) return f;
                }
            }
        }
        return null;
    }

    private float videoFrameRate() {
        if (player == null) return -1;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_VIDEO || !group.isSelected()) continue;
            for (int i = 0; i < group.length; i++) {
                if (group.isTrackSelected(i)) {
                    float fps = group.getTrackFormat(i).frameRate;
                    return fps > 0 ? fps : -1;
                }
            }
        }
        return -1;
    }

    private void disableBuiltInText() {
        if (player == null) return;
        textDisabledByUs = true;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
    }

    private void enableBuiltInText() {
        if (player == null || !textDisabledByUs) return;
        textDisabledByUs = false;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build());
    }

    // ---------------------------------------------------------------- Player.Listener

    @Override
    public void onPlaybackStateChanged(int state) {
        if (state == Player.STATE_READY && pending != null) {
            activate(pending, pendingTrack, pendingAuto);
        }
    }

    @Override
    public void onTrackSelectionParametersChanged(TrackSelectionParameters parameters) {
        // The user picked a built-in subtitle in the regular track dialog: step aside
        if (active != null && textDisabledByUs && !parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) {
            Log.i(TAG, "Built-in subtitle selected by user, turning online subtitle off");
            textDisabledByUs = false;
            deactivate();
        }
    }

    // ---------------------------------------------------------------- rendering

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render(false);
            main.postDelayed(this, TICK_MS);
        }
    };

    private void startTicking() {
        main.removeCallbacks(tick);
        lastCues = null;
        main.post(tick);
    }

    private void stopTicking() {
        main.removeCallbacks(tick);
    }

    private void render(boolean force) {
        if (player == null || playerView == null || activeTrack == null) return;
        SubtitleView view = playerView.getSubtitleView();
        if (view == null) return;
        List<Cue> cues = activeTrack.cuesAt(player.getCurrentPosition(), scale, offsetMs);
        // Re-set now and then in case the player cleared the view
        if (force || !cues.equals(lastCues) || ++ticksSinceSet >= 10) {
            view.setCues(cues);
            lastCues = cues;
            ticksSinceSet = 0;
        }
    }

    private void clearCues() {
        lastCues = null;
        if (playerView != null && playerView.getSubtitleView() != null) {
            playerView.getSubtitleView().setCues(Collections.emptyList());
        }
    }

    // ---------------------------------------------------------------- timing mode (remote control)

    /** @return true if the key was used for timing adjustment. */
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!timingMode) return false;
        int key = event.getKeyCode();
        long delta;
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT: delta = -500; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: delta = 500; break;
            case KeyEvent.KEYCODE_DPAD_UP: delta = 100; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: delta = -100; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (event.getAction() == KeyEvent.ACTION_UP) exitTimingMode();
                return true;
            default:
                return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            offsetMs += delta;
            render(true);
            updateOverlay();
        }
        return true;
    }

    private void enterTimingMode() {
        if (active == null || playerView == null) return;
        timingMode = true;
        playerView.hideController();
        FrameLayout frame = playerView.getOverlayFrameLayout();
        if (frame == null) return;
        if (overlay == null) {
            overlay = new TextView(activity);
            overlay.setTextColor(Color.WHITE);
            overlay.setBackgroundColor(0xCC000000);
            overlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            overlay.setGravity(Gravity.CENTER);
            int pad = dp(14);
            overlay.setPadding(pad * 2, pad, pad * 2, pad);
        }
        if (overlay.getParent() == null) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.topMargin = dp(40);
            frame.addView(overlay, lp);
        }
        updateOverlay();
    }

    private void exitTimingMode() {
        if (!timingMode) return;
        timingMode = false;
        if (overlay != null && overlay.getParent() instanceof FrameLayout) {
            ((FrameLayout) overlay.getParent()).removeView(overlay);
        }
        prefs.setOffset(fileKey, offsetMs);
        Log.i(TAG, "Offset saved: " + offsetMs + " ms");
    }

    private void updateOverlay() {
        if (overlay != null) {
            overlay.setText(activity.getString(R.string.autosubs_timing_overlay, formatOffset(offsetMs)));
        }
    }

    private static String formatOffset(long ms) {
        return String.format(Locale.getDefault(), "%+.1f s", ms / 1000.0);
    }

    // ---------------------------------------------------------------- menu

    /** Long press on the subtitle button. */
    public void showMenu(Runnable openSystemCaptionSettings) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        if (!ranked.isEmpty()) {
            labels.add(activity.getString(R.string.autosubs_item_choose,
                    active != null ? active.label() : activity.getString(R.string.autosubs_off)));
            actions.add(this::showCandidatePicker);
        }
        if (active != null) {
            labels.add(activity.getString(R.string.autosubs_item_timing, formatOffset(offsetMs)));
            actions.add(this::enterTimingMode);
        }
        labels.add(activity.getString(R.string.autosubs_item_language, Languages.displayName(prefs.language1())));
        actions.add(() -> showLanguagePicker(false));
        String l2 = prefs.language2();
        labels.add(activity.getString(R.string.autosubs_item_language2,
                l2.isEmpty() ? activity.getString(R.string.autosubs_none) : Languages.displayName(l2)));
        actions.add(() -> showLanguagePicker(true));
        labels.add(activity.getString(prefs.isEnabled() ? R.string.autosubs_item_auto_on : R.string.autosubs_item_auto_off));
        actions.add(() -> {
            prefs.setEnabled(!prefs.isEnabled());
            if (prefs.isEnabled() && status == Status.DISABLED) searchAgain();
        });
        if (status != Status.SEARCHING && status != Status.UNSUPPORTED) {
            labels.add(activity.getString(R.string.autosubs_item_search_again));
            actions.add(this::searchAgain);
        }
        if (openSystemCaptionSettings != null) {
            labels.add(activity.getString(R.string.autosubs_item_system));
            actions.add(openSystemCaptionSettings);
        }

        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.autosubs_menu_title) + " · " + statusText())
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private String statusText() {
        switch (status) {
            case SEARCHING: return activity.getString(R.string.autosubs_status_searching);
            case FOUND: return activity.getString(R.string.autosubs_status_found, ranked.size());
            case NONE: return activity.getString(R.string.autosubs_status_none);
            case ERROR: return activity.getString(R.string.autosubs_status_error);
            case DISABLED: return activity.getString(R.string.autosubs_status_disabled);
            case UNSUPPORTED: return activity.getString(R.string.autosubs_status_unsupported);
            default: return "";
        }
    }

    private void showCandidatePicker() {
        String[] labels = new String[ranked.size() + 1];
        labels[0] = activity.getString(R.string.autosubs_off);
        int checked = 0;
        for (int i = 0; i < ranked.size(); i++) {
            labels[i + 1] = ranked.get(i).label();
            if (ranked.get(i) == active) checked = i + 1;
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.autosubs_choose_title)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    if (which == 0) {
                        deactivate();
                        prefs.setChoice(fileKey, AutoSubsPrefs.CHOICE_OFF);
                    } else {
                        SubtitleCandidate c = ranked.get(which - 1);
                        prefs.setChoice(fileKey, c.id);
                        select(c, false);
                    }
                })
                .show();
    }

    private void showLanguagePicker(boolean second) {
        List<String> codes = new ArrayList<>();
        if (second) codes.add("");
        String device = Languages.deviceLanguage();
        codes.add(device);
        for (String c : Languages.COMMON) if (!c.equals(device)) codes.add(c);

        String current = second ? prefs.language2() : prefs.language1();
        String[] labels = new String[codes.size()];
        int checked = -1;
        for (int i = 0; i < codes.size(); i++) {
            String c = codes.get(i);
            labels[i] = c.isEmpty() ? activity.getString(R.string.autosubs_none) : Languages.displayName(c);
            if (c.equals(current)) checked = i;
        }
        new AlertDialog.Builder(activity)
                .setTitle(second ? R.string.autosubs_language2_title : R.string.autosubs_language_title)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    if (second) prefs.setLanguage2(codes.get(which));
                    else prefs.setLanguage1(codes.get(which));
                    onLanguagesChanged();
                })
                .show();
    }

    private void onLanguagesChanged() {
        if (status != Status.FOUND && status != Status.NONE) return;
        deactivate();
        prefs.setChoice(fileKey, null);
        rerank();
        status = ranked.isEmpty() ? Status.NONE : Status.FOUND;
        autoPick();
    }

    private void searchAgain() {
        Uri uri = mediaUri;
        reset();
        mediaUri = uri;
        startSearch();
    }

    // ---------------------------------------------------------------- helpers

    /** Runs on the main thread, unless a newer video has started in the meantime. */
    private void post(int gen, Runnable r) {
        main.post(() -> {
            if (gen == generation) r.run();
        });
    }

    private void toast(String text) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
