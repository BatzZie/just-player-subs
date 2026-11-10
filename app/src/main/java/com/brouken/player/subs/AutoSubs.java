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
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
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
    /** Piecewise offsets (different cut); offsetMs is then a manual correction on top. */
    private autosubs.core.SegmentSync.Result segments;

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
    /** The active subtitle was picked automatically (not by the user), so a better fitting one may replace it. */
    private boolean activeAuto;
    private boolean bestFitChecked;

    private long attachedAt;
    private final LaunchTransition launchTransition;
    private final FrameRateSwitcher frameRate;

    /** Our FrameRateSwitcher handles "Auto frame rate matching" (Just Player's own way is skipped). */
    public static final boolean HANDLES_FRAME_RATE = true;
    private PlayerSkin skin;

    public void setSkin(PlayerSkin skin) {
        this.skin = skin;
        if (skin != null) skin.setAutoSubs(this);
    }

    /** Title parts from the file name, until Cinemeta answers. */
    private boolean titleFromMeta;
    private boolean resultGiven;

    /**
     * Puts a language in front of the preferred audio languages (the user's own setting stays as fallback).
     * Usually done before the tracks are known, so there is no audible switch. A track the user picked for this
     * file (Just Player remembers it per file) stays.
     */
    private void preferAudioLanguage(String language, String why) {
        if (player == null || language == null) return;
        TrackSelectionParameters params = player.getTrackSelectionParameters();
        for (TrackSelectionOverride o : params.overrides.values()) {
            if (o.getType() == C.TRACK_TYPE_AUDIO) {
                Log.i(TAG, "Audio " + language + " (" + why + ") not applied: a track was chosen for this file");
                return;
            }
        }
        List<String> langs = new ArrayList<>();
        langs.add(language);
        for (String l : params.preferredAudioLanguages) if (!langs.contains(l)) langs.add(l);
        if (langs.equals(params.preferredAudioLanguages)) return;
        Log.i(TAG, "Audio language " + language + " first (" + why + "): " + langs);
        player.setTrackSelectionParameters(params.buildUpon().setPreferredAudioLanguages(langs.toArray(new String[0])).build());
    }

    /** Called from the audio menu: remember the language for this series or movie. */
    void onAudioChosen(Format f) {
        if (mediaId == null || f == null || f.language == null || "und".equals(f.language)) return;
        prefs.setAudioChoice(mediaId.imdbId, f.language);
        Log.i(TAG, "Audio " + f.language + " remembered for " + mediaId.imdbId);
        toast(activity.getString(R.string.skin_audio_remembered, Languages.displayName(Languages.canonical(f.language))));
    }

    private void titleFromFileName() {
        if (titleFromMeta || skin == null || videoInfo == null || videoInfo.title == null) return;
        boolean ep = videoInfo.isEpisode();
        skin.setTitleParts(videoInfo.title, videoInfo.year, ep ? videoInfo.season : -1, ep ? videoInfo.episode : -1, null);
    }

    /** Correct name, year and episode title from Cinemeta (the file name is often abbreviated). */
    private void fetchTitle(MediaId id, int gen) {
        if (id == null) return;
        // A choice the user made for this series/movie wins over the original-language rule
        String chosen = prefs.audioChoice(id.imdbId);
        if (chosen != null) post(gen, () -> preferAudioLanguage(chosen, "your choice for this title"));
        executor.execute(() -> {
            autosubs.core.StremioApi.Companion.Meta meta = autosubs.core.StremioApi.fullMeta(id.type(), id.imdbId);
            if (meta == null || meta.name == null) return;
            if (chosen == null) {
                String original = autosubs.core.OriginalLanguage.languageFor(prefs.audioOriginal(), meta.genres, meta.countries);
                Log.i(TAG, "Genres " + meta.genres + ", countries " + meta.countries + " -> original audio " + original);
                if (original != null) post(gen, () -> preferAudioLanguage(original, "original language"));
            }
            if (skin == null) return;
            String episodeTitle = id.isSeries() ? meta.episodeTitle(id.videoId()) : null;
            Log.i(TAG, "Title from Cinemeta: " + meta.name + " " + meta.year + (episodeTitle != null ? " / " + episodeTitle : ""));
            post(gen, () -> {
                titleFromMeta = true;
                if (skin != null) skin.setTitleParts(meta.name, meta.year, id.season, id.episode, episodeTitle);
            });
        });
    }

    private String sinceAttach() {
        return "+" + (SystemClock.elapsedRealtime() - attachedAt) + " ms";
    }


    public AutoSubs(Activity activity) {
        this.activity = activity;
        this.prefs = new AutoSubsPrefs(activity);
        this.launchTransition = LaunchTransition.begin(activity);
        this.frameRate = new FrameRateSwitcher(activity);
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
        player.removeListener(launchTransition);
        player.addListener(launchTransition);
        if (skin != null) skin.onPlayerStarted(player);
        logDecoders(player);
        frameRate.attach(player, androidx.preference.PreferenceManager.getDefaultSharedPreferences(activity)
                .getBoolean(FrameRateSwitcher.KEY, false), uri == null || !uri.equals(mediaUri));

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

    /**
     * The player screen is closing. Android only stops it once the app below (Stremio) is idle, which can take
     * a while; pause now so the audio doesn't keep playing behind Stremio.
     */
    public void onFinish() {
        if (player != null) player.pause();
    }

    public void detach() {
        frameRate.detach();
        if (skin != null) skin.onPlayerStopped();
        rememberPosition();
        exitTimingMode();
        stopTicking();
        main.removeCallbacks(syncCheck);
        clearCues();
        if (player != null) {
            player.removeListener(this);
            player.removeListener(launchTransition);
        }
        launchTransition.reveal("player released");
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
        activeAuto = false;
        bestFitChecked = false;
        lastPositionMs = -1;
        lastDurationMs = -1;
        startPositionMs = -1;
        mediaId = null;
        resultGiven = false;
        titleFromMeta = false;
        pendingEmbeddedChoice = null;
        resumeChecked = false;
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
    private long startPositionMs = -1;
    private MediaId mediaId;

    private void rememberPosition() {
        if (player == null || mediaUri == null) return;
        if (startPositionMs < 0 && player.getPlaybackState() == Player.STATE_READY) startPositionMs = player.getCurrentPosition();
        long d = player.getDuration();
        if (d != C.TIME_UNSET && d > 0) lastDurationMs = d;
        if (player.isCurrentMediaItemSeekable()) lastPositionMs = player.getCurrentPosition();
    }

    /**
     * Completes the result Just Player sends back (MX Player format). Upstream only adds position/duration while the
     * player still exists; after onStop it is already released, so use the last known values. Also adds the VLC
     * keys, so callers that expect those get the position too.
     */
    /**
     * @return false when the result should not be sent: with Stremio sync active we write the progress to the
     * account ourselves, and a result from an unknown player only makes Stremio mark the video as watched.
     */
    public boolean completeResult(android.content.Intent intent, boolean finished) {
        rememberPosition();
        if (resultGiven) {
            // Just Player can call finish() again (e.g. a late "ended" while Stremio already starts the next
            // episode): one result per video, never a second "completed"
            Log.i(TAG, "Result already given for this video, no second result");
            return false;
        }
        resultGiven = true;
        if (finished && !(startPositionMs >= 0 && lastDurationMs > 0 && lastPositionMs >= lastDurationMs * FINISHED)) {
            // "Ended" without having played this video (no tracks, no duration): not a completed episode
            Log.i(TAG, "Ended without playback (position " + lastPositionMs + " of " + lastDurationMs + "), not completed");
            finished = false;
        }
        if (!finished) {
            if (!intent.hasExtra("position") && lastPositionMs >= 0) intent.putExtra("position", (int) lastPositionMs);
            if (!intent.hasExtra("duration") && lastDurationMs > 0) intent.putExtra("duration", (int) lastDurationMs);
        }
        Log.i(TAG, "Result: end_by=" + intent.getStringExtra("end_by") + " position=" + intent.getIntExtra("position", -1)
                + " duration=" + intent.getIntExtra("duration", -1));
        // Stremio is leading: stopping in the last 10% counts as finished, like with Stremio's own player
        // (CREDITS_THRESHOLD_COEF 0.9); Stremio then decides about the watched mark and the next episode.
        if (!finished && lastDurationMs > 0 && lastPositionMs >= lastDurationMs * FINISHED) finished = true;
        if (finished && prefs.stremioAuthKeyForSync() != null) {
            // Watched to the end: Stremio handles a completion result correctly (marks it watched and starts the
            // next episode). Don't write the account ourselves, that could undo Stremio's move to the next episode.
            intent.removeExtra("position");
            intent.removeExtra("duration");
            intent.removeExtra("extra_position");
            intent.removeExtra("extra_duration");
            intent.putExtra("end_by", "playback_completion");
            if (mediaId != null) prefs.setResume(mediaId.videoId(), 0, true);
            Log.i(TAG, "Result for the caller: playback completed (Stremio marks it watched itself)");
            ensureWatchedLater(prefs.stremioAuthKeyForSync(), mediaId);
            return true;
        }
        if (STREMIO_RESULT_WITHOUT_DURATION) {
            // Stremio (Android TV) decides "watched" with duration / position > 0.7 (inverted ratio), so any result
            // with a duration marks the video watched. Without a duration that check gives 0 and Stremio just stores
            // the position itself; its core marks watched at 70% of the duration it knows, or via end_by.
            intent.removeExtra("duration");
            intent.removeExtra("extra_duration");
            intent.removeExtra("extra_position");
            if (mediaId != null && lastPositionMs >= 0 && lastDurationMs > 0) {
                prefs.setResume(mediaId.videoId(), lastPositionMs, finished || lastPositionMs >= lastDurationMs * FINISHED);
            }
            Log.i(TAG, "Result for the caller: MX format without duration, position=" + intent.getIntExtra("position", -1)
                    + " end_by=" + intent.getStringExtra("end_by"));
            return true;
        }
        boolean syncing = reportToStremio(finished);
        // Tested: with any result (MX or VLC format) Stremio sometimes marks the video watched and jumps to the
        // next episode. Without a result it leaves the library alone; we write the progress to the account.
        if (syncing) Log.i(TAG, "Result not sent to the caller (Stremio sync is active)");
        return !syncing;
    }

    /**
     * Writes the watch progress to the user's Stremio library (optional, needs a login in the settings). Stremio
     * ignores the result of external players it doesn't know and can overwrite the library when it comes back to
     * the front, so the write is repeated a few times.
     */
    /**
     * Tested and rejected (7 Nov 2026): Stremio's TV app can't store the progress of an external player itself.
     * With position + duration its inverted check (duration / position > 0.7) marks the video watched and Unload
     * moves on to the next episode; without a duration, Seek stores duration 0 and Unload's 90% rule resets the
     * position to 0. So no result is sent and the progress is written to the account instead.
     */
    private static final boolean STREMIO_RESULT_WITHOUT_DURATION = false;

    /** Share of the duration from which a video counts as finished: Stremio's own credits threshold. */
    private static final double FINISHED = 0.9;

    /** Each stop (and each new start) gets a new token; older background writes stop when they see a newer one. */
    private static final java.util.concurrent.atomic.AtomicLong stremioSyncToken = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Safety net: Stremio marks a finished video watched itself, but its library sync right after (e.g. while it
     * autoplays the next episode) can overwrite that mark. Check the account a few times and set only the missing
     * mark. Not cancelled by the next start: that is exactly when the mark gets lost.
     */
    private static void ensureWatchedLater(String authKey, MediaId id) {
        if (authKey == null || id == null) return;
        Thread t = new Thread(() -> {
            autosubs.core.StremioApi api = new autosubs.core.StremioApi();
            long started = System.currentTimeMillis();
            for (long at : new long[]{10_000, 30_000, 60_000, 120_000}) {
                try {
                    long wait = started + at - System.currentTimeMillis();
                    if (wait > 0) Thread.sleep(wait);
                    String r = api.ensureWatched(authKey, id);
                    Log.i(TAG, "Stremio watched check " + id + " after " + (at / 1000) + "s: " + r
                            + " (" + api.describe(authKey, id) + ")");
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "Stremio watched check failed: " + e.getMessage());
                }
            }
        }, "StremioWatched");
        t.start();
    }

    private boolean reportToStremio(boolean finished) {
        final String authKey = prefs.stremioAuthKeyForSync();
        final MediaId id = mediaId;
        final long pos = lastPositionMs;
        final long dur = lastDurationMs;
        if (id != null && pos >= 0 && dur > 0) prefs.setResume(id.videoId(), pos, finished || pos >= dur * FINISHED);
        if (authKey == null) return false;
        if (id == null || dur <= 0 || pos < 0) {
            // Not identified: we can't write the position, but a result would make Stremio mark it watched
            // (its inverted check), which is worse than not saving the position.
            Log.i(TAG, "Stremio: video not identified, position not written and no result sent");
            return true;
        }
        final boolean done = finished || pos >= dur * FINISHED;
        final long watched = Math.max(0, pos - Math.max(0, startPositionMs));
        if (done) return false; // handled by the completion result (Stremio is leading)
        // Always write the real video and position, like Stremio's own player does: if Stremio doesn't know the
        // duration yet it treats any stop as finished and jumps to the next episode. Only ignore accidental starts.
        if (pos < 5_000) {
            Log.i(TAG, "Stremio: stopped at " + (pos / 1000) + " s, position not written");
            return true;
        }
        final long token = stremioSyncToken.incrementAndGet();
        Thread t = new Thread(() -> {
            autosubs.core.StremioApi api = new autosubs.core.StremioApi();
            long started = System.currentTimeMillis();
            // Stremio writes back its own (old) copy when it comes to the front: check and repair for 2 minutes,
            // but only while the library still points to this video.
            for (long at : new long[]{0, 10_000, 30_000, 60_000, 120_000}) {
                try {
                    long wait = started + at - System.currentTimeMillis();
                    if (wait > 0) Thread.sleep(wait);
                    if (stremioSyncToken.get() != token) {
                        Log.i(TAG, "Stremio sync for " + id + " at " + (pos / 1000) + "s superseded");
                        return;
                    }
                    if (at > 0) {
                        autosubs.core.StremioApi.Progress p = api.readProgress(authKey, id);
                        // Stremio's own unload may jump to the next episode at offset 0/1 ms (it treats a stop as
                        // finished when it doesn't know the duration): repair that. Real progress on another
                        // video (offset > 1 s) means it really moved on.
                        if (p != null && !id.videoId().equals(p.videoId) && p.offsetMs > 1000) {
                            Log.i(TAG, "Stremio library moved on to " + p.videoId + ", stop repairing");
                            return;
                        }
                        if (p != null && id.videoId().equals(p.videoId) && Math.abs(p.offsetMs - pos) < 1000) continue;
                        Log.i(TAG, "Stremio wrote " + (p == null ? "nothing" : p.videoId + " at " + (p.offsetMs / 1000) + "s")
                                + ", repairing to " + (pos / 1000) + "s");
                    }
                    if (!api.writePosition(authKey, id, pos, dur, at == 0 ? watched : 0)) {
                        // Stremio adds what it plays to the library itself: if it isn't there, our identification
                        // is probably wrong; never add a title to the user's library
                        Log.i(TAG, "Stremio: " + id + " is not in the library, position not written");
                        return;
                    }
                    Log.i(TAG, "Stremio position written: " + id + " at " + (pos / 1000) + "s of " + (dur / 1000) + "s");
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "Stremio progress failed: " + e.getMessage());
                }
            }
        }, "StremioSync");
        t.start();
        return true;
    }

    private boolean resumeChecked;

    /**
     * Stremio shows (and sends) its local copy of the progress until it syncs with the account, so right after
     * stopping it can send an older position. If we know a later one for this video, continue there.
     * A start from 0 is left alone: that can be a deliberate "play from the start".
     */
    private void checkResume() {
        if (resumeChecked || mediaId == null || player == null || startPositionMs < 0) return;
        resumeChecked = true;
        final MediaId id = mediaId;
        final long sent = startPositionMs;
        final String authKey = prefs.stremioAuthKeyForSync();
        if (authKey == null) {
            // Not logged in: only correct an older non-zero position (0 can be a deliberate start from the beginning)
            long saved = prefs.resume(id.videoId());
            if (sent > 0) seekToResume(saved, sent);
            return;
        }
        // Logged in: the account is leading. If you marked it unwatched in Stremio, it says 0 there and we respect that.
        final int gen = generation;
        executor.execute(() -> {
            try {
                autosubs.core.StremioApi.Progress p = new autosubs.core.StremioApi().readProgress(authKey, id);
                if (p == null || !id.videoId().equals(p.videoId)) return;
                final long offset = p.offsetMs;
                final long dur = p.durationMs;
                Log.i(TAG, "Resume: caller sent " + (sent / 1000) + "s, account says " + (offset / 1000) + "s");
                if (dur > 0 && offset >= dur * FINISHED) return;
                post(gen, () -> seekToResume(offset, sent));
            } catch (Exception e) {
                Log.i(TAG, "Resume check failed: " + e.getMessage());
            }
        });
    }

    /** Seeks to a later position than the caller sent, if playback is still near that start. */
    private void seekToResume(long target, long sent) {
        if (player == null || target <= sent + 10_000) return;
        if (player.getCurrentPosition() > sent + 15_000) return; // user already moved on
        Log.i(TAG, "Resume at " + (target / 1000) + "s (caller sent " + (sent / 1000) + "s)");
        player.seekTo(target);
        toast(activity.getString(R.string.autosubs_resumed, formatTime(target)));
    }

    private static String formatTime(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
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
        final boolean local = LocalFile.isLocal(mediaUri);
        if (!scheme.equals("http") && !scheme.equals("https") && !local) {
            status = Status.UNSUPPORTED;
            return;
        }
        fileName = local ? LocalFile.displayName(activity, mediaUri) : mediaUri.getLastPathSegment();
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
        final Uri uriForHash = mediaUri;
        executor.execute(() -> {
            try {
                // Local files: the OpenSubtitles hash is cheap and often finds the subtitle made for this exact file
                String hash = null;
                long size = -1;
                if (local) {
                    try {
                        size = LocalFile.size(activity, uriForHash);
                        hash = autosubs.core.OpenSubtitlesHash.compute(LocalFile.reader(activity, uriForHash), size);
                        Log.i(TAG, "Local file hash " + hash + " size " + size);
                    } catch (Exception e) {
                        Log.i(TAG, "No hash for local file: " + e);
                    }
                }
                MediaId id = local ? null : MediaIdentifier.fromUrl(url, info);
                if (id == null) id = MediaIdentifier.lookup(info);
                Log.i(TAG, "Id: " + id);
                final MediaId foundId = id;
                post(gen, () -> {
                    mediaId = foundId;
                    checkResume();
                });
                fetchTitle(foundId, gen);
                if (id == null) {
                    post(gen, () -> onSearchDone(Collections.emptyList()));
                    return;
                }
                List<Future<List<SubtitleCandidate>>> futures = new ArrayList<>();
                for (SubtitleSource s : sources) {
                    final MediaId fid = id;
                    final String fhash = hash;
                    final long fsize = size;
                    futures.add(executor.submit(() -> {
                        try {
                            return s.search(fid, fhash, fsize, name, languages);
                        } catch (java.io.IOException first) {
                            // e.g. the addon timing out once: try again
                            Log.w(TAG, s.name() + " failed (" + first + "), retrying");
                            return s.search(fid, fhash, fsize, name, languages);
                        }
                    }));
                }
                List<SubtitleCandidate> found = new ArrayList<>();
                Set<String> legacyIds = new HashSet<>();
                boolean anyOk = false;
                for (int i = 0; i < futures.size(); i++) {
                    try {
                        List<SubtitleCandidate> list = futures.get(i).get(30, TimeUnit.SECONDS);
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
        String remembered = prefs.choice(fileKey);
        if (remembered != null && remembered.startsWith(CHOICE_EMBEDDED)) {
            // The user chose a built-in subtitle for this video earlier
            embeddedChosen = true;
            pendingEmbeddedChoice = remembered.substring(CHOICE_EMBEDDED.length());
            applyEmbeddedChoice();
            return;
        }
        if (ranked.isEmpty()) return;
        if (AutoSubsPrefs.CHOICE_OFF.equals(remembered)) {
            userOff = true;
            return;
        }
        SubtitleCandidate c = find(remembered);
        boolean auto = c == null;
        if (c == null) c = ranked.get(0);
        select(c, auto);
    }

    private static final String CHOICE_EMBEDDED = "embedded:";
    private String pendingEmbeddedChoice;

    /** Key of a built-in track for the per-file memory: language and label. */
    private static String embeddedKey(Format f) {
        return (f.language == null ? "" : f.language) + "|" + (f.label == null ? "" : f.label);
    }

    /** Selects the remembered built-in track once the tracks are known. */
    private void applyEmbeddedChoice() {
        if (pendingEmbeddedChoice == null || player == null) return;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < group.length; i++) {
                if (embeddedKey(group.getTrackFormat(i)).equals(pendingEmbeddedChoice)) {
                    pendingEmbeddedChoice = null;
                    showEmbedded(group, i, false);
                    return;
                }
            }
        }
    }

    /** Shows a built-in subtitle track (instead of an online one). */
    private void showEmbedded(Tracks.Group group, int index, boolean remember) {
        if (player == null) return;
        deactivate();
        embeddedChosen = true;
        userOff = false;
        TrackSelectionParameters params = player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(), index))
                .build();
        // Still ours: we keep drawing (or not drawing) on top of the player's track
        owning = true;
        ownTextSelection = textSelection(params);
        player.setTrackSelectionParameters(params);
        Format f = group.getTrackFormat(index);
        if (remember) prefs.setChoice(fileKey, CHOICE_EMBEDDED + embeddedKey(f));
        if (skin != null) skin.setSubtitleLanguage(f.language != null ? f.language : "und");
        Log.i(TAG, "Built-in subtitle chosen: " + f.language + " " + f.label);
    }

    private void subtitlesOff() {
        deactivate();
        userOff = true;
        embeddedChosen = false;
        prefs.setChoice(fileKey, AutoSubsPrefs.CHOICE_OFF);
        clearCues();
    }

    /**
     * The subtitle menu (one press on the subtitle button): off, the built-in tracks, a line, the online subtitles
     * in the order of the language settings, and the actions.
     */
    List<SkinMenu.Item> subtitleMenu() {
        List<SkinMenu.Item> items = new ArrayList<>();
        boolean nothing = active == null && !embeddedChosen;
        items.add(SkinMenu.Item.option(activity.getString(R.string.autosubs_off), null, nothing, this::subtitlesOff));

        List<SkinMenu.Item> builtIn = new ArrayList<>();
        if (player != null) {
            int n = 0;
            for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
                if (group.getType() != C.TRACK_TYPE_TEXT) continue;
                for (int i = 0; i < group.length; i++) {
                    n++;
                    if (!group.isTrackSupported(i)) continue;
                    Format f = group.getTrackFormat(i);
                    String name = skin != null ? skin.trackName(f, n) : String.valueOf(f.language);
                    String detail = isForced(f) ? activity.getString(R.string.skin_menu_forced) : null;
                    boolean shown = active == null && embeddedChosen && group.isTrackSelected(i);
                    final Tracks.Group g = group;
                    final int index = i;
                    builtIn.add(SkinMenu.Item.option(name, detail, shown, () -> showEmbedded(g, index, true)));
                }
            }
        }
        if (!builtIn.isEmpty()) {
            items.add(SkinMenu.Item.header(activity.getString(R.string.skin_menu_builtin)));
            items.addAll(builtIn);
        }

        items.add(SkinMenu.Item.divider());
        items.add(SkinMenu.Item.header(activity.getString(R.string.autosubs_menu_title) + " · " + statusText()));
        for (int i = 0; i < ranked.size(); i++) {
            SubtitleCandidate c = ranked.get(i);
            String detail = i == 0 ? "★" : null;
            items.add(SkinMenu.Item.option(shortLabel(c), detail, c == active, () -> {
                if (c == active) return;
                prefs.setChoice(fileKey, c.id);
                // A different subtitle needs its own timing
                prefs.removeOffset(fileKey);
                select(c, false);
            }));
        }

        items.add(SkinMenu.Item.divider());
        if (active != null) {
            items.add(SkinMenu.Item.action(activity.getString(R.string.autosubs_item_timing, formatOffset(offsetMs)), this::enterTimingMode));
            items.add(SkinMenu.Item.action(activity.getString(syncWanted ? R.string.autosubs_item_sync_running : R.string.autosubs_item_sync), () -> {
                if (indexRefs != null) syncFromIndex(true);
                else if (!syncWanted) startSync(true);
            }));
        }
        if (status != Status.SEARCHING) items.add(SkinMenu.Item.action(activity.getString(R.string.autosubs_item_search_again), this::searchAgain));
        Runnable file = skin != null ? skin.openSubtitleFileAction() : null;
        if (file != null) items.add(SkinMenu.Item.action(activity.getString(R.string.skin_menu_subtitle_file), file));
        return items;
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

        if (auto && hasSideloadedSubtitle()) {
            // A subtitle file next to the video (or passed by the calling app) wins over an online one
            embeddedChosen = true;
            Log.i(TAG, "Side-loaded subtitle present, online subtitle not used");
            return;
        }
        if (auto && prefs.preferEmbedded()) {
            Format embedded = selectedEmbeddedText();
            int rank = embedded == null ? -1 : languageRank(embedded.language);
            if (rank >= 0 && rank <= c.langRank) {
                embeddedChosen = true;
                Log.i(TAG, "Using embedded subtitle " + embedded.language + " instead of " + c);
                toast(activity.getString(R.string.autosubs_embedded_used,
                        Languages.displayName(Languages.canonical(embedded.language))));
                if (skin != null) skin.setSubtitleLanguage(embedded.language);
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
        activeAuto = auto;
        segments = null;
        if (skin != null) skin.setSubtitleLanguage(c.lang);
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

        String msg = activity.getString(R.string.autosubs_loaded, shortLabel(c));
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
        if (skin != null) skin.setSubtitleLanguage(null);
        segments = null;
        active = null;
        activeTrack = null;
        syncWanted = false;
        main.removeCallbacks(syncCheck);
        stopTicking();
        clearCues();
    }

    private boolean hasSideloadedSubtitle() {
        androidx.media3.common.MediaItem item = player == null ? null : player.getCurrentMediaItem();
        return item != null && item.localConfiguration != null && !item.localConfiguration.subtitleConfigurations.isEmpty();
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
            if (skin != null) skin.setSubtitleLanguage(embedded.language);
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
        final Uri uri = mediaUri;
        final String lang = prefs.language1();
        executor.execute(() -> {
            long t0 = SystemClock.elapsedRealtime();
            try {
                List<MkvIndex.SubtitleTrackInfo> tracks = LocalFile.isLocal(uri)
                        ? MkvIndex.read(LocalFile.reader(activity, uri)) : MkvIndex.read(url);
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

    /** Minimum gain in fit before an automatically picked subtitle is replaced by a better fitting one. */
    private static final double BETTER_FIT = 0.08;

    /**
     * Aligns the active subtitle to the MKV index. When the subtitle was picked automatically, also compares the
     * best few candidates in the same language and switches to one that fits the video clearly better.
     */
    private void syncFromIndex(boolean manual) {
        if (activeTrack == null || indexRefs == null || player == null) return;
        final int gen = generation;
        final SubtitleCandidate current = active;
        final SubtitleTrack currentTrack = activeTrack;
        final double currentScale = scale;
        final List<Long> refs = indexRefs;
        final float videoFps = videoFrameRate();
        final long duration = player.getDuration() == C.TIME_UNSET ? -1 : player.getDuration();
        if (!syncWanted) {
            syncWanted = true;
            syncStartedAt = SystemClock.elapsedRealtime();
        }
        syncManual = manual;

        final List<SubtitleCandidate> alternatives = new ArrayList<>();
        alternatives.add(current);
        if (!manual && activeAuto && !bestFitChecked) {
            for (SubtitleCandidate c : ranked) {
                if (alternatives.size() >= 3) break;
                if (c != current && c.langRank == current.langRank) alternatives.add(c);
            }
        }
        bestFitChecked = true;
        final Map<String, SubtitleTrack> known = new HashMap<>(trackCache);
        known.put(current.id, currentTrack);

        executor.execute(() -> {
            long t0 = SystemClock.elapsedRealtime();
            Map<String, SubtitleTrack> tracks = new HashMap<>();
            Map<String, Double> scales = new HashMap<>();
            Map<String, AutoSync.Result> results = new HashMap<>();
            Map<String, autosubs.core.SegmentSync.Result> segResults = new HashMap<>();
            Map<String, autosubs.core.SegmentSync.Result> fineResults = new HashMap<>();
            for (SubtitleCandidate c : alternatives) {
                SubtitleTrack t = known.get(c.id);
                if (t == null) {
                    try {
                        t = SubtitleTrack.parse(c.source.download(c));
                    } catch (Exception e) {
                        Log.i(TAG, "Fit check: download failed for " + c.id + ": " + e);
                        continue;
                    }
                }
                if (t.size() == 0) continue;
                double sc = c == current ? currentScale
                        : t.detectScale(c.fpsMilli > 0 ? c.fpsMilli / 1000.0 : -1, videoFps, duration);
                // With the complete index the frame rate ratio can be measured instead of trusted
                kotlin.Pair<Double, AutoSync.Result> measured = AutoSync.estimateWithScale(refs, t.startTimesMs(), sc);
                if (measured == null) {
                    // Dense or differently split reference: check whether all parts of the video agree
                    measured = AutoSync.estimateConsistent(refs, t.startTimesMs(), sc);
                }
                AutoSync.Result r = measured == null ? null : measured.getSecond();
                if (measured != null) sc = measured.getFirst();
                Log.i(TAG, "Fit " + String.format(Locale.ROOT, "%.2f", r == null ? 0.0 : r.fit()) + " offset "
                        + (r == null ? "-" : r.offsetMs + " ms") + " scale " + String.format(Locale.ROOT, "%.5f", sc) + ": " + c);
                tracks.put(c.id, t);
                scales.put(c.id, sc);
                if (r != null) results.put(c.id, r);
                // A good single offset can still be a few hundred ms off after ad breaks (other edit): fine-tune
                if (r != null && r.fit() >= 0.4) {
                    autosubs.core.SegmentSync.Result fine = autosubs.core.FineSync.refine(refs, t.startTimesMs(), sc, r.offsetMs);
                    if (fine != null) {
                        Log.i(TAG, "Fine sync " + fine + ": " + c);
                        fineResults.put(c.id, fine);
                    }
                }
                // No clear single offset: maybe a different cut (recap, extra scene) -> offsets per part
                if (r == null || r.fit() < 0.5) {
                    autosubs.core.SegmentSync.Result seg = autosubs.core.SegmentSync.estimate(refs, t.startTimesMs(), sc);
                    if (seg != null && (r == null || seg.fit() > r.fit())) {
                        Log.i(TAG, "Piecewise fit " + String.format(Locale.ROOT, "%.2f", seg.fit()) + " " + seg + ": " + c);
                        segResults.put(c.id, seg);
                    }
                }
            }
            SubtitleCandidate best = null;
            double bestFit = 0;
            for (SubtitleCandidate c : alternatives) {
                double f = fitOf(results.get(c.id), segResults.get(c.id));
                if (f > 0 && (best == null || f > bestFit)) {
                    best = c;
                    bestFit = f;
                }
            }
            final SubtitleCandidate chosen = best;
            dumpSyncData(refs, alternatives, tracks, scales);
            Log.i(TAG, "Index sync of " + alternatives.size() + " subtitle(s) took " + (SystemClock.elapsedRealtime() - t0) + " ms");
            post(gen, () -> {
                if (activeTrack != currentTrack) return;
                for (Map.Entry<String, SubtitleTrack> e : tracks.entrySet()) trackCache.put(e.getKey(), e.getValue());
                AutoSync.Result cur = results.get(current.id);
                double curFit = fitOf(cur, segResults.get(current.id));
                if (chosen != null && chosen != current && fitOf(results.get(chosen.id), segResults.get(chosen.id)) >= curFit + BETTER_FIT) {
                    AutoSync.Result r = results.get(chosen.id);
                    autosubs.core.SegmentSync.Result seg = segResults.get(chosen.id);
                    switchTo(chosen, tracks.get(chosen.id), scales.get(chosen.id), seg != null || r == null ? 0 : r.offsetMs);
                    if (seg != null) applySegments(seg);
                    else if (fineResults.get(chosen.id) != null) applySegments(fineResults.get(chosen.id));
                } else if (segResults.get(current.id) != null) {
                    applySegments(segResults.get(current.id));
                } else if (cur != null) {
                    Double measuredScale = scales.get(current.id);
                    if (measuredScale != null && measuredScale != scale) {
                        Log.i(TAG, "Frame rate correction: scale " + scale + " -> " + measuredScale);
                        scale = measuredScale;
                    }
                    if (fineResults.get(current.id) != null) applySegments(fineResults.get(current.id));
                    else finishSync(cur.offsetMs);
                } else if (manual) {
                    startSync(true); // fall back to watching along
                }
            });
        });
    }

    /**
     * Writes the sync reference and candidate subtitle times (no URLs or tokens) to the app's external files dir,
     * readable over adb, so sync problems can be analysed on a computer.
     */
    private void dumpSyncData(List<Long> refs, List<SubtitleCandidate> alternatives, Map<String, SubtitleTrack> tracks,
                              Map<String, Double> scales) {
        try {
            java.io.File dir = new java.io.File(activity.getExternalFilesDir(null), "sync-debug");
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            String name = (mediaId != null ? mediaId.videoId() : String.valueOf(fileName)).replaceAll("[^A-Za-z0-9._-]", "_");
            StringBuilder sb = new StringBuilder("{\"file\":").append(org.json.JSONObject.quote(String.valueOf(fileName)))
                    .append(",\"refs\":").append(new org.json.JSONArray(refs)).append(",\"subs\":{");
            boolean first = true;
            for (SubtitleCandidate c : alternatives) {
                SubtitleTrack t = tracks.get(c.id);
                if (t == null) continue;
                if (!first) sb.append(',');
                first = false;
                long[] starts = t.startTimesMs();
                org.json.JSONArray a = new org.json.JSONArray();
                for (long v : starts) a.put(v);
                sb.append(org.json.JSONObject.quote(c.id + " " + c.releaseName)).append(":{\"scale\":")
                        .append(scales.get(c.id)).append(",\"starts\":").append(a).append('}');
            }
            sb.append("}}");
            java.io.File out = new java.io.File(dir, name + ".json");
            try (java.io.FileOutputStream os = new java.io.FileOutputStream(out)) {
                os.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            Log.i(TAG, "Sync data written to " + out.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "Sync data dump failed: " + e);
        }
    }

    private static double fitOf(AutoSync.Result single, autosubs.core.SegmentSync.Result seg) {
        double f = single == null ? 0 : single.fit();
        if (seg != null) f = Math.max(f, seg.fit());
        return f;
    }

    /** Different cut: offsets per part of the video. A manual timing change still applies on top. */
    private void applySegments(autosubs.core.SegmentSync.Result seg) {
        syncWanted = false;
        main.removeCallbacks(syncCheck);
        segments = seg;
        offsetMs = 0;
        prefs.setOffset(fileKey, 0, false);
        Log.i(TAG, "Piecewise sync active: " + seg);
        render(true);
        toast(activity.getString(R.string.autosubs_sync_segments, seg.segments.size()));
    }

    /** Replaces the automatically picked subtitle by one that fits the video better, already in sync. */
    private void switchTo(SubtitleCandidate c, SubtitleTrack track, double newScale, long offset) {
        Log.i(TAG, "Better fitting subtitle: " + c + " (was " + active + "), offset " + offset + " ms");
        syncWanted = false;
        main.removeCallbacks(syncCheck);
        active = c;
        activeTrack = track;
        activeAuto = true;
        segments = null;
        if (skin != null) skin.setSubtitleLanguage(c.lang);
        scale = newScale;
        offsetMs = offset;
        prefs.setOffset(fileKey, offsetMs, false);
        sync.resetAgreement();
        render(true);
        toast(activity.getString(R.string.autosubs_loaded, shortLabel(c)));
    }

    // ---------------------------------------------------------------- Player.Listener

    private static boolean dolbyVisionCapsLogged;
    private Player decoderLogPlayer;

    /** Logs which video/audio decoder is used, and once what the Dolby Vision decoders report (for DV checks). */
    private void logDecoders(Player player) {
        if (!(player instanceof androidx.media3.exoplayer.ExoPlayer) || player == decoderLogPlayer) return;
        decoderLogPlayer = player;
        ((androidx.media3.exoplayer.ExoPlayer) player).addAnalyticsListener(new androidx.media3.exoplayer.analytics.AnalyticsListener() {
            @Override
            public void onVideoDecoderInitialized(@androidx.annotation.NonNull EventTime eventTime, @androidx.annotation.NonNull String decoderName,
                                                  long initializedTimestampMs, long initializationDurationMs) {
                Log.i(TAG, "Video decoder: " + decoderName);
            }

            @Override
            public void onVideoInputFormatChanged(@androidx.annotation.NonNull EventTime eventTime, @androidx.annotation.NonNull Format format,
                                                  @androidx.annotation.Nullable androidx.media3.exoplayer.DecoderReuseEvaluation evaluation) {
                Log.i(TAG, "Video format: " + format.sampleMimeType + " " + format.codecs + " " + format.width + "x" + format.height
                        + " " + format.frameRate + "fps");
            }

            @Override
            public void onAudioDecoderInitialized(@androidx.annotation.NonNull EventTime eventTime, @androidx.annotation.NonNull String decoderName,
                                                  long initializedTimestampMs, long initializationDurationMs) {
                Log.i(TAG, "Audio decoder: " + decoderName);
            }
        });
        if (dolbyVisionCapsLogged) return;
        dolbyVisionCapsLogged = true;
        executor.execute(() -> {
            try {
                for (android.media.MediaCodecInfo info : new android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).getCodecInfos()) {
                    if (info.isEncoder()) continue;
                    for (String type : info.getSupportedTypes()) {
                        if (!"video/dolby-vision".equalsIgnoreCase(type)) continue;
                        StringBuilder sb = new StringBuilder();
                        for (android.media.MediaCodecInfo.CodecProfileLevel pl : info.getCapabilitiesForType(type).profileLevels) {
                            sb.append(" p").append(Integer.numberOfTrailingZeros(pl.profile))
                                    .append("/l").append(Integer.numberOfTrailingZeros(pl.level) + 1);
                        }
                        Log.i(TAG, "Dolby Vision decoder " + info.getName() + ":" + sb);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Codec list: " + e);
            }
        });
    }

    private final java.util.ArrayDeque<Long> recoveries = new java.util.ArrayDeque<>();
    private static final int MAX_RECOVERIES = 3;
    private static final long RECOVERY_WINDOW_MS = 5 * 60_000;

    /**
     * Playback errors that are worth a retry: glitches in a decoder or the audio output, and network errors of
     * the stream. Errors that would repeat (unsupported format, DRM, missing file) are left to the player.
     */
    private static boolean isRecoverable(int code) {
        switch (code) {
            case PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND:
            case PlaybackException.ERROR_CODE_IO_NO_PERMISSION:
            case PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED:
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED:
            case PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED:
                return false;
            default:
                // DRM errors (6000-6999) never recover by retrying
                return code < 6000 || code >= 7000;
        }
    }

    /**
     * Restarts playback at the same position after a recoverable error, at most 3 times in 5 minutes.
     * @return true if the error is handled (the player shouldn't show it or stop)
     */
    public boolean recover(PlaybackException error) {
        if (player == null || mediaUri == null || !isRecoverable(error.errorCode)) return false;
        long now = SystemClock.elapsedRealtime();
        while (!recoveries.isEmpty() && now - recoveries.peekFirst() > RECOVERY_WINDOW_MS) recoveries.pollFirst();
        if (recoveries.size() >= MAX_RECOVERIES) {
            Log.w(TAG, "Playback error, not recovering (" + recoveries.size() + " times in 5 min): " + error.getErrorCodeName());
            return false;
        }
        recoveries.addLast(now);
        long position = player.getCurrentPosition();
        Log.w(TAG, "Playback error " + error.getErrorCodeName() + " at " + (position / 1000) + "s, restarting there ("
                + recoveries.size() + "/" + MAX_RECOVERIES + "): " + error.getMessage()
                + (error.getCause() != null ? " / " + error.getCause() : ""));
        final Player p = player;
        main.post(() -> {
            if (player != p) return;
            p.seekTo(position);
            p.prepare();
            p.setPlayWhenReady(true);
            toast(activity.getString(R.string.autosubs_recovered));
        });
        return true;
    }

    @Override
    public void onPlaybackStateChanged(int state) {
        Log.i(TAG, "Player state " + state + " " + sinceAttach());
        if (state == Player.STATE_READY && startPositionMs < 0 && player != null) {
            startPositionMs = player.getCurrentPosition();
            checkResume();
        }
        if (state == Player.STATE_READY && !indexRequested && owning) fetchIndex();
        if (state == Player.STATE_READY && pending != null) {
            activate(pending, pendingTrack, pendingAuto);
        }
    }

    @Override
    public void onTracksChanged(Tracks tracks) {
        Log.i(TAG, "Tracks known (" + tracks.getGroups().size() + " groups), text=" + selectedEmbeddedText() + " " + sinceAttach());
        if (skin != null) {
            titleFromFileName();
            skin.setTracks(tracks);
        }
        applyEmbeddedChoice();
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
        long pos = player.getCurrentPosition();
        List<Cue> cues = activeTrack == null ? Collections.emptyList()
                : activeTrack.cuesAt(pos, scale, offsetMs + (segments != null ? segments.offsetAt(pos) : 0));
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
        if (!timingMode) return skin != null && skin.dispatchKeyEvent(event);
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

    private TextView notice;
    private final Runnable hideNotice = () -> {
        if (notice != null) notice.animate().alpha(0f).setDuration(300).withEndAction(() -> notice.setVisibility(View.GONE)).start();
    };

    /**
     * Small message top right in the player (Android's toast covered the subtitles). Falls back to a toast when
     * there is no player view.
     */
    private void toast(String text) {
        FrameLayout frame = playerView == null ? null : playerView.getOverlayFrameLayout();
        if (frame == null) {
            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
            return;
        }
        if (notice == null) {
            notice = new TextView(activity);
            notice.setTextColor(Color.WHITE);
            notice.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            notice.setMaxLines(2);
            notice.setEllipsize(android.text.TextUtils.TruncateAt.END);
            notice.setMaxWidth(activity.getResources().getDisplayMetrics().widthPixels * 2 / 5);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xA6000000);
            bg.setCornerRadius(dp(10));
            notice.setBackground(bg);
            notice.setPadding(dp(12), dp(7), dp(12), dp(7));
            notice.setVisibility(View.GONE);
        }
        if (notice.getParent() != frame) {
            if (notice.getParent() instanceof ViewGroup) ((ViewGroup) notice.getParent()).removeView(notice);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            lp.topMargin = dp(20);
            lp.rightMargin = dp(24);
            frame.addView(notice, lp);
        }
        notice.setText(text);
        notice.animate().cancel();
        if (notice.getVisibility() != View.VISIBLE) {
            notice.setAlpha(0f);
            notice.setVisibility(View.VISIBLE);
        }
        notice.animate().alpha(1f).setDuration(150).start();
        main.removeCallbacks(hideNotice);
        main.postDelayed(hideNotice, 3500);
    }

    /** "NL · 1080p WEB · NTb" */
    private static String shortLabel(SubtitleCandidate c) {
        List<String> parts = new ArrayList<>();
        String iso1 = Languages.toIso1(c.lang);
        parts.add(iso1 != null ? iso1.toUpperCase(Locale.ROOT) : String.valueOf(c.lang));
        String what = ((c.release.resolution != null ? c.release.resolution + " " : "")
                + (c.release.source != null ? c.release.source : "")).trim();
        if (!what.isEmpty()) parts.add(what);
        if (c.release.group != null) parts.add(c.release.group);
        if (parts.size() == 1 && c.releaseName != null) parts.add(c.releaseName);
        return android.text.TextUtils.join(" · ", parts);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
