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

import com.brouken.player.BuildConfig;
import com.brouken.player.R;

import autosubs.core.AutoSync;
import autosubs.core.CoreLog;
import autosubs.core.Languages;
import autosubs.core.MediaId;
import autosubs.core.MediaIdentifier;
import autosubs.core.MkvIndex;
import autosubs.core.OpenSubtitlesComSource;
import autosubs.core.ReleaseInfo;
import autosubs.core.ReleaseNameParser;
import autosubs.core.StremioOpenSubtitlesSource;
import autosubs.core.SubtitleCandidate;
import autosubs.core.SubtitleRanker;
import autosubs.core.SubtitleSource;

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
 * How it works without slowing playback down:
 * <ul>
 * <li>Before playback starts, an embedded text track (preferred language, else English) is requested, so the
 * player never has to re-buffer for a track change later.</li>
 * <li>AutoSubs owns the SubtitleView: it draws the online subtitle itself, and every cue change of the embedded
 * track is immediately overwritten (PlayerView's listener runs first). The embedded track is only shown when it is
 * the better choice.</li>
 * <li>The embedded track's timing is recorded from the first second and used to auto sync the online subtitle.</li>
 * </ul>
 * Hooks in PlayerActivity: attach() after setMediaItem, detach() on release, dispatchKeyEvent(), showMenu().
 */
public final class AutoSubs implements Player.Listener {

    public static final String TAG = "AutoSubs";

    private enum Status { IDLE, SEARCHING, FOUND, NONE, ERROR, DISABLED, UNSUPPORTED }

    private static final long TICK_MS = 100;
    private static final long SYNC_CHECK_MS = 1000;
    private static final long SYNC_MAX_MS = 10 * 60_000;

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

    // Display ownership
    /** We chose the text track and decide what is shown in the SubtitleView. */
    private boolean owning;
    /** Text track selection we set; anything else means the user picked a track himself. */
    private String ownTextSelection;
    /** The embedded subtitle is the better choice: let its cues through. */
    private boolean embeddedChosen;
    /** The user turned online subtitles off for this video. */
    private boolean userOff;

    // Active online subtitle
    private SubtitleCandidate active;
    private SubtitleTrack activeTrack;
    private double scale = 1.0;
    private long offsetMs;

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

    // Auto sync
    private AutoSync sync = new AutoSync();
    private boolean syncWanted;
    private boolean syncManual;
    private long syncStartedAt;
    private int syncNextAttempt;
    private boolean syncProvisional;

    // Sync reference from the MKV index (null = not available yet)
    private boolean indexRequested;
    private List<Long> indexRefs;

    private long attachedAt;

    private String sinceAttach() {
        return "+" + (SystemClock.elapsedRealtime() - attachedAt) + " ms";
    }

    @Override
    public void onIsLoadingChanged(boolean isLoading) {
        Log.d(TAG, "Player loading=" + isLoading + " " + sinceAttach());
    }

    public AutoSubs(Activity activity) {
        this.activity = activity;
        this.prefs = new AutoSubsPrefs(activity);
        Net.init();
        CoreLog.setLogger((level, message) -> {
            if (level == 'W') Log.w(TAG, message);
            else Log.i(TAG, message);
        });
    }

    // ---------------------------------------------------------------- lifecycle

    public void attach(Player player, PlayerView playerView, Uri uri) {
        if (this.player != null && this.player != player) {
            this.player.removeListener(this);
        }
        this.player = player;
        this.playerView = playerView;
        attachedAt = SystemClock.elapsedRealtime();
        player.addListener(this);

        if (uri == null || !uri.equals(mediaUri)) {
            reset();
            mediaUri = uri;
            startSearch();
            if (status == Status.SEARCHING) takeTextTrack();
            return;
        }
        // Same video, new player instance (e.g. back from the settings screen); still before prepare()
        if (owning) takeTextTrack();
        if (status == Status.DISABLED && prefs.isEnabled()) {
            searchAgain();
            return;
        }
        if ((status == Status.FOUND || status == Status.NONE) && !prefs.languages().equals(rankedLanguages)) {
            onLanguagesChanged();
            return;
        }
        if (active != null) {
            startTicking();
            if (syncWanted) scheduleSyncCheck();
        }
    }

    public void detach() {
        rememberPosition();
        exitTimingMode();
        stopTicking();
        main.removeCallbacks(syncCheck);
        clearCues();
        if (player != null) {
            player.removeListener(this);
        }
        player = null;
    }

    private void reset() {
        generation++;
        exitTimingMode();
        stopTicking();
        main.removeCallbacks(syncCheck);
        clearCues();
        active = null;
        activeTrack = null;
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
        owning = false;
        ownTextSelection = null;
        embeddedChosen = false;
        userOff = false;
        sync = new AutoSync();
        syncWanted = false;
        syncManual = false;
        syncProvisional = false;
        indexRequested = false;
        indexRefs = null;
        lastPositionMs = -1;
        lastDurationMs = -1;
    }

    /** Ask the player for an embedded text track up front (preferred languages, then English as sync reference). */
    private void takeTextTrack() {
        if (player == null) return;
        List<String> langs = new ArrayList<>();
        for (String l : prefs.languages()) {
            String iso1 = Languages.toIso1(l);
            if (iso1 != null && !langs.contains(iso1)) langs.add(iso1);
        }
        if (!langs.contains("en")) langs.add("en");
        TrackSelectionParameters params = player.getTrackSelectionParameters().buildUpon()
                .setPreferredTextLanguages(langs.toArray(new String[0]))
                .setSelectUndeterminedTextLanguage(true)
                .build();
        owning = true;
        ownTextSelection = textSelection(params);
        player.setTrackSelectionParameters(params);
    }

    private static String textSelection(TrackSelectionParameters p) {
        StringBuilder sb = new StringBuilder();
        sb.append(p.preferredTextLanguages).append('|')
                .append(p.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)).append('|')
                .append(p.ignoredTextSelectionFlags).append('|');
        for (TrackSelectionOverride o : p.overrides.values()) {
            if (o.getType() == C.TRACK_TYPE_TEXT) sb.append(o.mediaTrackGroup.id).append(o.trackIndices);
        }
        return sb.toString();
    }

    /**
     * Just Player probes ~10 URLs next to a network video (name.srt, name.en.srt, ...). Debrid resolvers like
     * Torrentio redirect every such URL to the video itself, so each probe opens a full download of the movie and
     * Real-Debrid's connection limit stalls the real playback for 10-20 s. AutoSubs searches online instead.
     */
    public boolean replacesSidecarSearch(Uri uri) {
        if (uri == null || !prefs.isEnabled()) return false;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean skip = scheme.equals("http") || scheme.equals("https");
        if (skip) Log.i(TAG, "Skipping sidecar subtitle probes for stream");
        return skip;
    }

    // ---------------------------------------------------------------- result for the calling app (Stremio)

    private long lastPositionMs = -1;
    private long lastDurationMs = -1;

    private void rememberPosition() {
        if (player == null || mediaUri == null) return;
        long d = player.getDuration();
        if (d != C.TIME_UNSET && d > 0) lastDurationMs = d;
        if (player.isCurrentMediaItemSeekable()) lastPositionMs = player.getCurrentPosition();
    }

    /**
     * Completes the result Just Player sends back (MX Player format). Upstream only adds position/duration while the
     * player still exists; after onStop it is already released, so use the last known values. Also adds the VLC
     * keys, so callers that expect those get the position too.
     */
    public void completeResult(android.content.Intent intent, boolean finished) {
        rememberPosition();
        if (!finished) {
            if (!intent.hasExtra("position") && lastPositionMs >= 0) intent.putExtra("position", (int) lastPositionMs);
            if (!intent.hasExtra("duration") && lastDurationMs > 0) intent.putExtra("duration", (int) lastDurationMs);
        }
        if (intent.hasExtra("position")) intent.putExtra("extra_position", (long) intent.getIntExtra("position", 0));
        if (intent.hasExtra("duration")) intent.putExtra("extra_duration", (long) intent.getIntExtra("duration", 0));
        Log.i(TAG, "Result: end_by=" + intent.getStringExtra("end_by") + " position=" + intent.getIntExtra("position", -1)
                + " duration=" + intent.getIntExtra("duration", -1));
    }

    // ---------------------------------------------------------------- search

    private List<SubtitleSource> sources() {
        List<SubtitleSource> list = new ArrayList<>();
        list.add(new StremioOpenSubtitlesSource());
        String key = prefs.openSubtitlesApiKey();
        if (!key.isEmpty()) {
            list.add(new OpenSubtitlesComSource(key, prefs.openSubtitlesUsername(), prefs.openSubtitlesPassword(),
                    "JustPlayerSubs v" + BuildConfig.VERSION_NAME));
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
        final long started = SystemClock.elapsedRealtime();
        // No file hash: it needs extra requests on the same debrid link while the player is starting up
        executor.execute(() -> {
            try {
                MediaId id = MediaIdentifier.fromUrl(url, info);
                if (id == null) id = MediaIdentifier.lookup(info);
                Log.i(TAG, "Id: " + id);
                if (id == null) {
                    post(gen, () -> onSearchDone(Collections.emptyList()));
                    return;
                }
                List<Future<List<SubtitleCandidate>>> futures = new ArrayList<>();
                for (SubtitleSource s : sources) {
                    final MediaId fid = id;
                    futures.add(executor.submit(() -> s.search(fid, null, -1, name, languages)));
                }
                List<SubtitleCandidate> found = new ArrayList<>();
                Set<String> legacyIds = new HashSet<>();
                boolean anyOk = false;
                for (int i = 0; i < futures.size(); i++) {
                    try {
                        List<SubtitleCandidate> list = futures.get(i).get(15, TimeUnit.SECONDS);
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
                Log.i(TAG, "Search took " + (SystemClock.elapsedRealtime() - started) + " ms");
                post(gen, () -> onSearchDone(found));
            } catch (Exception e) {
                Log.w(TAG, "Search failed", e);
                post(gen, () -> {
                    status = Status.ERROR;
                    updateEmbeddedChoice();
                });
            }
        });
    }

    private void onSearchDone(List<SubtitleCandidate> found) {
        all = found;
        rerank();
        status = ranked.isEmpty() ? Status.NONE : Status.FOUND;
        Log.i(TAG, "Found " + found.size() + " subtitles, " + ranked.size() + " in " + rankedLanguages);
        for (int i = 0; i < Math.min(8, ranked.size()); i++) Log.i(TAG, "  #" + (i + 1) + " " + ranked.get(i));
        updateEmbeddedChoice();
        autoPick();
    }

    private void rerank() {
        rankedLanguages = prefs.languages();
        ranked = SubtitleRanker.rank(all, videoInfo, rankedLanguages);
    }

    private void autoPick() {
        if (ranked.isEmpty()) return;
        String remembered = prefs.choice(fileKey);
        if (AutoSubsPrefs.CHOICE_OFF.equals(remembered)) {
            userOff = true;
            return;
        }
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
            Format embedded = selectedEmbeddedText();
            int rank = embedded == null ? -1 : languageRank(embedded.language);
            if (rank >= 0 && rank <= c.langRank) {
                embeddedChosen = true;
                Log.i(TAG, "Using embedded subtitle " + embedded.language + " instead of " + c);
                toast(activity.getString(R.string.autosubs_embedded_used,
                        Languages.displayName(Languages.canonical(embedded.language))));
                return;
            }
        }

        if (!owning) {
            // Chosen from the menu after the user had picked a built-in track: draw over it again
            owning = true;
            ownTextSelection = textSelection(player.getTrackSelectionParameters());
        }
        boolean switching = active != null && active != c;
        active = c;
        activeTrack = track;
        embeddedChosen = false;
        userOff = false;
        long duration = player.getDuration();
        scale = track.detectScale(c.fpsMilli > 0 ? c.fpsMilli / 1000.0 : -1, videoFrameRate(),
                duration == C.TIME_UNSET ? -1 : duration);
        boolean userOffset = prefs.hasUserOffset(fileKey);
        offsetMs = prefs.offset(fileKey);
        Log.i(TAG, "Active: " + c + " cues=" + track.size() + " lastEnd=" + track.lastEndMs()
                + " duration=" + duration + " videoFps=" + videoFrameRate() + " scale=" + scale
                + " offset=" + offsetMs + " referenceCues=" + sync.collected());
        startTicking();

        String msg = activity.getString(R.string.autosubs_loaded, c.label());
        if (scale != 1.0) msg += " (" + activity.getString(R.string.autosubs_fps_corrected) + ")";
        toast(msg);

        if (switching) sync.resetAgreement();
        if (prefs.autoSync() && !userOffset) {
            // Playback based sync runs as a fallback; the index result (usually within a second) replaces it
            startSync(false);
            if (indexRefs != null) syncFromIndex(false);
        }
    }

    private void deactivate() {
        active = null;
        activeTrack = null;
        syncWanted = false;
        main.removeCallbacks(syncCheck);
        stopTicking();
        clearCues();
    }

    /** The embedded text track the player selected, if it isn't a forced-only track. */
    private Format selectedEmbeddedText() {
        if (player == null) return null;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT || !group.isSelected()) continue;
            for (int i = 0; i < group.length; i++) {
                if (group.isTrackSelected(i)) {
                    Format f = group.getTrackFormat(i);
                    return isForced(f) ? null : f;
                }
            }
        }
        return null;
    }

    private int languageRank(String language) {
        if (language == null) return -1;
        List<String> languages = prefs.languages();
        for (int i = 0; i < languages.size(); i++) {
            if (Languages.matches(language, languages.get(i))) return i;
        }
        return -1;
    }

    /** Without an online subtitle, show the embedded one if it is in a wanted language. */
    private void updateEmbeddedChoice() {
        if (!owning || active != null || userOff || embeddedChosen) return;
        if (status == Status.SEARCHING || pending != null) return;
        if (status == Status.FOUND && !ranked.isEmpty()) return;
        Format embedded = selectedEmbeddedText();
        if (embedded != null && languageRank(embedded.language) >= 0) {
            embeddedChosen = true;
            Log.i(TAG, "No online subtitle, showing embedded " + embedded.language);
        }
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

    // ---------------------------------------------------------------- auto sync

    private void startSync(boolean manual) {
        if (activeTrack == null) return;
        if (manual && selectedEmbeddedText() == null && sync.collected() == 0) {
            toast(activity.getString(R.string.autosubs_sync_no_reference));
            return;
        }
        syncWanted = true;
        syncManual = manual;
        syncProvisional = false;
        syncStartedAt = SystemClock.elapsedRealtime();
        syncNextAttempt = Math.max(AutoSync.FIRST_ATTEMPT, sync.collected());
        sync.resetAgreement();
        if (manual) toast(activity.getString(R.string.autosubs_sync_started));
        scheduleSyncCheck();
    }

    private void scheduleSyncCheck() {
        main.removeCallbacks(syncCheck);
        main.post(syncCheck);
    }

    private final Runnable syncCheck = new Runnable() {
        @Override
        public void run() {
            if (!syncWanted || activeTrack == null) return;
            while (sync.collected() >= syncNextAttempt) {
                syncNextAttempt = sync.collected() + 2;
                AutoSync.Result r = sync.estimate(activeTrack.startTimesMs(), scale);
                if (r == null) continue;
                if (r.level == AutoSync.Level.FINAL) {
                    finishSync(r.offsetMs);
                    return;
                }
                // Provisional: apply quietly, confirm later
                if (Math.abs(r.offsetMs - offsetMs) > 150) {
                    Log.i(TAG, "AutoSync: provisional offset " + offsetMs + " -> " + r.offsetMs);
                    offsetMs = r.offsetMs;
                    syncProvisional = true;
                    render(true);
                }
            }
            if (SystemClock.elapsedRealtime() - syncStartedAt > SYNC_MAX_MS) {
                Log.i(TAG, "AutoSync gave up after " + sync.collected() + " reference cues, provisional=" + syncProvisional);
                syncWanted = false;
                if (syncManual || syncProvisional) {
                    toast(activity.getString(R.string.autosubs_sync_failed));
                }
                return;
            }
            main.postDelayed(this, SYNC_CHECK_MS);
        }
    };

    private void finishSync(long offset) {
        syncWanted = false;
        main.removeCallbacks(syncCheck);
        long change = offset - offsetMs;
        Log.i(TAG, "AutoSync: final offset " + offset + " ms after " + sync.collected() + " reference cues, "
                + ((SystemClock.elapsedRealtime() - syncStartedAt) / 1000) + " s");
        offsetMs = offset;
        prefs.setOffset(fileKey, offsetMs, false);
        render(true);
        if (syncManual || Math.abs(change) >= 300) {
            toast(activity.getString(R.string.autosubs_sync_done, formatOffset(offsetMs)));
        }
    }

    // ---------------------------------------------------------------- MKV index sync

    /** Reads all embedded subtitle start times from the file's index (two small range requests). */
    private void fetchIndex() {
        indexRequested = true;
        if (mediaUri == null || fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".mkv")) return;
        final int gen = generation;
        final String url = mediaUri.toString();
        final String lang = prefs.language1();
        executor.execute(() -> {
            long t0 = SystemClock.elapsedRealtime();
            try {
                List<MkvIndex.SubtitleTrackInfo> tracks = MkvIndex.read(url);
                MkvIndex.SubtitleTrackInfo best = null;
                int bestScore = Integer.MIN_VALUE;
                for (MkvIndex.SubtitleTrackInfo t : tracks) {
                    Log.i(TAG, "MKV subtitle track " + t);
                    if (t.forced || t.startsMs.size() < 20) continue;
                    // Text tracks have one cue per line; bitmap (PGS) tracks also have "clear" events
                    int score = (t.isText() ? 100 : 0)
                            + (Languages.matches(t.language, lang) ? 10 : Languages.matches(t.language, "eng") ? 5 : 0)
                            + Math.min(t.startsMs.size(), 3000) / 1000;
                    if (score > bestScore) {
                        bestScore = score;
                        best = t;
                    }
                }
                final List<Long> refs = best == null ? null : best.startsMs;
                Log.i(TAG, "MKV index read in " + (SystemClock.elapsedRealtime() - t0) + " ms, reference: " + best);
                post(gen, () -> {
                    indexRefs = refs;
                    if (refs != null && active != null && syncWanted) syncFromIndex(syncManual);
                });
            } catch (Exception e) {
                Log.i(TAG, "No MKV index: " + e);
            }
        });
    }

    private void syncFromIndex(boolean manual) {
        if (activeTrack == null || indexRefs == null) return;
        final int gen = generation;
        final SubtitleTrack track = activeTrack;
        final double s = scale;
        final List<Long> refs = indexRefs;
        if (!syncWanted) {
            syncWanted = true;
            syncStartedAt = SystemClock.elapsedRealtime();
        }
        syncManual = manual;
        executor.execute(() -> {
            long t0 = SystemClock.elapsedRealtime();
            AutoSync.Result r = AutoSync.estimateAll(refs, track.startTimesMs(), s);
            Log.i(TAG, "Index sync: " + (r == null ? "no clear match" : r.offsetMs + " ms") + " in "
                    + (SystemClock.elapsedRealtime() - t0) + " ms");
            post(gen, () -> {
                if (activeTrack != track) return;
                if (r != null) finishSync(r.offsetMs);
                else if (manual) startSync(true); // fall back to watching along
            });
        });
    }

    // ---------------------------------------------------------------- Player.Listener

    @Override
    public void onPlaybackStateChanged(int state) {
        Log.i(TAG, "Player state " + state + " " + sinceAttach());
        if (state == Player.STATE_READY && !indexRequested && owning) fetchIndex();
        if (state == Player.STATE_READY && pending != null) {
            activate(pending, pendingTrack, pendingAuto);
        }
    }

    @Override
    public void onTracksChanged(Tracks tracks) {
        Log.i(TAG, "Tracks known (" + tracks.getGroups().size() + " groups), text=" + selectedEmbeddedText() + " " + sinceAttach());
        updateEmbeddedChoice();
    }

    @Override
    public void onTrackSelectionParametersChanged(TrackSelectionParameters parameters) {
        if (!owning || textSelection(parameters).equals(ownTextSelection)) return;
        // The user picked (or switched off) a built-in subtitle in the regular track dialog: step aside
        Log.i(TAG, "Text track changed by user, online subtitle off");
        owning = false;
        deactivate();
    }

    @Override
    public void onCues(CueGroup cueGroup) {
        if (!owning || player == null) return;
        // presentationTimeUs is the exact cue time; the player position is only a fallback
        long position = player.getCurrentPosition();
        long t = cueGroup.presentationTimeUs != C.TIME_UNSET ? cueGroup.presentationTimeUs / 1000 : position;
        if (Math.abs(t - position) > 5000) t = position;
        sync.onCues(cueGroup.cues.isEmpty(), t);
        // PlayerView has just drawn the embedded cues (its listener runs first); replace them unless wanted
        if (!embeddedChosen) render(true);
    }

    @Override
    public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) sync.onSeek();
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

    /** Shows our cues, or nothing when no online subtitle is active (embedded cues are hidden). */
    private void render(boolean force) {
        if (player == null || playerView == null) return;
        SubtitleView view = playerView.getSubtitleView();
        if (view == null) return;
        List<Cue> cues = activeTrack == null ? Collections.emptyList()
                : activeTrack.cuesAt(player.getCurrentPosition(), scale, offsetMs);
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
        syncWanted = false;
        main.removeCallbacks(syncCheck);
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
        prefs.setOffset(fileKey, offsetMs, true);
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
            labels.add(activity.getString(syncWanted ? R.string.autosubs_item_sync_running : R.string.autosubs_item_sync));
            actions.add(() -> {
                if (indexRefs != null) syncFromIndex(true);
                else if (!syncWanted) startSync(true);
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
                        userOff = true;
                        embeddedChosen = false;
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
        embeddedChosen = false;
        userOff = false;
        prefs.setChoice(fileKey, null);
        rerank();
        status = ranked.isEmpty() ? Status.NONE : Status.FOUND;
        Log.i(TAG, "Languages changed to " + rankedLanguages + ", " + ranked.size() + " candidates");
        updateEmbeddedChoice();
        autoPick();
    }

    private void searchAgain() {
        Uri uri = mediaUri;
        reset();
        mediaUri = uri;
        startSearch();
        if (status == Status.SEARCHING) takeTextTrack();
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
