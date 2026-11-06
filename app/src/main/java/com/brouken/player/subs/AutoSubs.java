package com.brouken.player.subs;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

import com.brouken.player.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private static final long SYNC_MAX_MS = 8 * 60_000;

    private final Activity activity;
    private final AutoSubsPrefs prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "AutoSubs");
        t.setDaemon(true);
        return t;
    });

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
    private List<String> rankedLanguages = Collections.emptyList();
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

    // Auto sync against an embedded track
    private AutoSync sync;
    private boolean syncAuto;
    private long syncStartedAt;
    private int syncNextAttempt;

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
            return;
        }
        // Same video, new player instance (e.g. back from the settings screen)
        if (status == Status.DISABLED && prefs.isEnabled()) {
            searchAgain();
            return;
        }
        if ((status == Status.FOUND || status == Status.NONE) && !prefs.languages().equals(rankedLanguages)) {
            onLanguagesChanged();
            return;
        }
        if (active != null) {
            disableBuiltInText();
            startTicking();
        }
    }

    public void detach() {
        exitTimingMode();
        cancelSync();
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
        cancelSync();
        deactivate();
        status = Status.IDLE;
        fileName = null;
        fileKey = null;
        videoInfo = null;
        all = Collections.emptyList();
        ranked = Collections.emptyList();
        rankedLanguages = Collections.emptyList();
        trackCache.clear();
        pending = null;
        pendingTrack = null;
    }

    // ---------------------------------------------------------------- search

    private List<SubtitleSource> sources() {
        List<SubtitleSource> list = new ArrayList<>();
        list.add(new StremioOpenSubtitlesSource());
        String key = prefs.openSubtitlesApiKey();
        if (!key.isEmpty()) {
            list.add(new OpenSubtitlesComSource(key, prefs.openSubtitlesUsername(), prefs.openSubtitlesPassword()));
        }
        return list;
    }

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
        final List<String> languages = prefs.languages();
        final List<SubtitleSource> sources = sources();
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
                // Query all sources in parallel; one failing source doesn't stop the others
                List<Future<List<SubtitleCandidate>>> futures = new ArrayList<>();
                for (SubtitleSource s : sources) {
                    final MediaId fid = id;
                    final OpenSubtitlesHash fh = hash;
                    futures.add(executor.submit(() -> s.search(fid, fh == null ? null : fh.hash,
                            fh == null ? -1 : fh.size, name, languages)));
                }
                List<SubtitleCandidate> found = new ArrayList<>();
                Set<String> legacyIds = new HashSet<>();
                boolean anyOk = false;
                for (int i = 0; i < futures.size(); i++) {
                    try {
                        List<SubtitleCandidate> list = futures.get(i).get(20, TimeUnit.SECONDS);
                        anyOk = true;
                        int added = 0;
                        for (SubtitleCandidate c : list) {
                            // The same subtitle can come from both OpenSubtitles sources
                            if (c.legacyId != null && !legacyIds.add(c.legacyId)) continue;
                            found.add(c);
                            added++;
                        }
                        Log.i(TAG, sources.get(i).name() + ": " + list.size() + " results, " + added + " new");
                    } catch (Exception e) {
                        Log.w(TAG, sources.get(i).name() + " failed: " + e);
                    }
                }
                if (!anyOk) throw new java.io.IOException("all sources failed");
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
        Log.i(TAG, "Found " + found.size() + " subtitles, " + ranked.size() + " in " + rankedLanguages);
        for (int i = 0; i < Math.min(8, ranked.size()); i++) Log.i(TAG, "  #" + (i + 1) + " " + ranked.get(i));
        autoPick();
    }

    private void rerank() {
        rankedLanguages = prefs.languages();
        ranked = SubtitleRanker.rank(all, videoInfo, rankedLanguages);
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
                SubtitleTrack track = SubtitleTrack.parse(c.source.download(c));
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

        if (auto && prefs.preferEmbedded()) {
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

        cancelSync();
        active = c;
        activeTrack = track;
        long duration = player.getDuration();
        scale = track.detectScale(c.fpsMilli > 0 ? c.fpsMilli / 1000.0 : -1, videoFrameRate(),
                duration == C.TIME_UNSET ? -1 : duration);
        boolean userOffset = prefs.hasOffset(fileKey);
        offsetMs = prefs.offset(fileKey);
        Log.i(TAG, "Active: " + c + " cues=" + track.size() + " lastEnd=" + track.lastEndMs()
                + " duration=" + duration + " videoFps=" + videoFrameRate() + " scale=" + scale
                + " offset=" + offsetMs);

        disableBuiltInText();
        startTicking();

        String msg = activity.getString(R.string.autosubs_loaded, c.label());
        if (scale != 1.0) msg += " (" + activity.getString(R.string.autosubs_fps_corrected) + ")";
        toast(msg);

        if (prefs.autoSync() && !userOffset) {
            startSync(true);
        }
    }

    private void deactivate() {
        cancelSync();
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
                if (isForced(f) || f.language == null) continue;
                for (int rank = 0; rank < languages.size() && rank <= c.langRank; rank++) {
                    if (Languages.matches(f.language, languages.get(rank))) return f;
                }
            }
        }
        return null;
    }

    private static boolean isForced(Format f) {
        return (f.selectionFlags & C.SELECTION_FLAG_FORCED) != 0
                || (f.label != null && f.label.toLowerCase(Locale.ROOT).contains("forced"));
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

    // ---------------------------------------------------------------- auto sync

    /**
     * Temporarily enables an embedded subtitle track and listens to its cue timing (it is never shown: every
     * cue change is immediately overwritten with our own cues), then aligns our subtitle to it.
     */
    private void startSync(boolean auto) {
        if (player == null || activeTrack == null || sync != null) return;
        Tracks.Group refGroup = null;
        int refIndex = -1;
        int refRank = Integer.MAX_VALUE;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) continue;
                Format f = group.getTrackFormat(i);
                if (isForced(f)) continue;
                // Same language as ours gives the most similar cue splitting, then English, then anything
                int rank = Languages.matches(f.language, active.lang) ? 0 : Languages.matches(f.language, "eng") ? 1 : 2;
                if (rank < refRank) {
                    refRank = rank;
                    refGroup = group;
                    refIndex = i;
                }
            }
        }
        if (refGroup == null) {
            Log.i(TAG, "AutoSync: no embedded subtitle to compare with");
            if (!auto) toast(activity.getString(R.string.autosubs_sync_no_reference));
            return;
        }
        Format ref = refGroup.getTrackFormat(refIndex);
        Log.i(TAG, "AutoSync: reference " + ref.language + " " + ref.sampleMimeType + " " + ref.label);
        sync = new AutoSync();
        syncAuto = auto;
        syncStartedAt = SystemClock.elapsedRealtime();
        syncNextAttempt = AutoSync.WANTED;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(new TrackSelectionOverride(refGroup.getMediaTrackGroup(), refIndex))
                .build());
        toast(activity.getString(R.string.autosubs_sync_started));
        main.postDelayed(syncCheck, 2000);
    }

    private final Runnable syncCheck = new Runnable() {
        @Override
        public void run() {
            if (sync == null) return;
            if (sync.collected() >= syncNextAttempt) {
                Long offset = sync.estimate(activeTrack.startTimesMs(), scale);
                if (offset != null) {
                    finishSync(offset);
                    return;
                }
                syncNextAttempt += AutoSync.WANTED;
            }
            if (SystemClock.elapsedRealtime() - syncStartedAt > SYNC_MAX_MS) {
                Long offset = sync.collected() >= 6 ? sync.estimate(activeTrack.startTimesMs(), scale) : null;
                finishSync(offset);
                return;
            }
            main.postDelayed(this, 2000);
        }
    };

    private void finishSync(Long offset) {
        boolean auto = syncAuto;
        int refs = sync == null ? 0 : sync.collected();
        cancelSync();
        if (offset == null) {
            Log.i(TAG, "AutoSync failed after " + refs + " reference cues");
            toast(activity.getString(R.string.autosubs_sync_failed));
            return;
        }
        Log.i(TAG, "AutoSync: offset " + offsetMs + " -> " + offset + " ms (" + refs + " reference cues, auto=" + auto + ")");
        offsetMs = offset;
        prefs.setOffset(fileKey, offsetMs);
        render(true);
        toast(activity.getString(R.string.autosubs_sync_done, formatOffset(offsetMs)));
    }

    /** Stops listening to the reference track and hides built-in subtitles again. */
    private void cancelSync() {
        if (sync == null) return;
        sync = null;
        main.removeCallbacks(syncCheck);
        if (player != null) {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, active != null)
                    .build());
            textDisabledByUs = active != null;
        }
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
        if (sync != null) return;
        // The user picked a built-in subtitle in the regular track dialog: step aside
        if (active != null && textDisabledByUs && !parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) {
            Log.i(TAG, "Built-in subtitle selected by user, turning online subtitle off");
            textDisabledByUs = false;
            deactivate();
        }
    }

    @Override
    public void onCues(CueGroup cueGroup) {
        if (sync != null && player != null) {
            sync.onCues(cueGroup.cues.isEmpty(), player.getCurrentPosition());
        }
        // PlayerView has just drawn the built-in cues (its listener runs first); put ours back right away
        if (activeTrack != null) render(true);
    }

    @Override
    public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
        if (sync != null) sync.onSeek();
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
        cancelSync();
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

    // ---------------------------------------------------------------- menu (long press on subtitle button)

    /** Only what you need while watching; everything else is in the settings screen. */
    public void showMenu() {
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
            labels.add(activity.getString(sync != null ? R.string.autosubs_item_sync_running : R.string.autosubs_item_sync));
            actions.add(() -> {
                if (sync == null) startSync(false);
            });
        }
        if (status == Status.ERROR || status == Status.NONE) {
            labels.add(activity.getString(R.string.autosubs_item_search_again));
            actions.add(this::searchAgain);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.autosubs_menu_title) + " · " + statusText());
        if (labels.isEmpty()) {
            builder.setMessage(R.string.autosubs_menu_empty).setPositiveButton(android.R.string.ok, null);
        } else {
            builder.setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run());
        }
        builder.show();
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
                        if (c == active) return;
                        prefs.setChoice(fileKey, c.id);
                        // A different subtitle needs its own timing
                        prefs.removeOffset(fileKey);
                        select(c, false);
                    }
                })
                .show();
    }

    private void onLanguagesChanged() {
        deactivate();
        prefs.setChoice(fileKey, null);
        rerank();
        status = ranked.isEmpty() ? Status.NONE : Status.FOUND;
        Log.i(TAG, "Languages changed to " + rankedLanguages + ", " + ranked.size() + " candidates");
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
