package com.brouken.player.subs;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.widget.ImageViewCompat;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.Util;
import androidx.media3.ui.TimeBar;
import androidx.media3.ui.DefaultTimeBar;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.preference.PreferenceManager;

import com.brouken.player.R;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Modern look for Just Player's controls, applied on top of its own layout (so upstream layouts stay untouched):
 * no big play button in the middle, all buttons in one row bottom left (play/pause first), times left and right
 * of a thin time bar in an accent color, a rounded accent highlight on the focused button, soft gradients instead
 * of black bars and a small title with video/audio info top left. Accent color and time format are settings.
 */
public final class PlayerSkin {

    static final String KEY_ENABLED = "skin_enabled";
    static final String KEY_ACCENT = "skin_accent";
    static final String KEY_TIME = "skin_time";
    static final String KEY_TITLE = "skin_title";
    static final String TITLE_CODE_EPISODE = "code_episode";
    static final String TITLE_FILENAME = "filename";
    static final String TITLE_NONE = "none";
    /** Title formats: S = show, C = S06E22, E = episode title, | = second line. Order as in the settings list. */
    static final String[][] TITLE_FORMATS = {
            {TITLE_CODE_EPISODE, "S C E"},
            {"episode", "S E"},
            {"episode_code", "S E C"},
            {"code", "S C"},
            {"code_show_episode", "C S E"},
            {"code_show", "C S"},
            {"code_ep", "C E"},
            {"ep_code", "E C"},
            {"episode_only", "E"},
            {"two_lines", "S|C E"},
            {"two_lines_ep_code", "S|E C"},
            {"two_lines_ep", "S|E"},
            {"two_lines_ep_first", "E|S C"},
            {"two_lines_ep_first_show", "E|S"},
            {"title", "S"},
    };
    static final String DEFAULT_ACCENT = "#7B5CFA";
    static final String TIME_TOTAL = "total";
    static final String TIME_REMAINING = "remaining";
    static final String TIME_END = "end";

    /** What only PlayerActivity can do, for the menus. */
    public static final class Actions {
        final Runnable openSettings;
        final Runnable openSubtitleFile;
        final java.util.function.IntConsumer setResizeMode;
        final Runnable zoom;

        public Actions(Runnable openSettings, Runnable openSubtitleFile, java.util.function.IntConsumer setResizeMode, Runnable zoom) {
            this.openSettings = openSettings;
            this.openSubtitleFile = openSubtitleFile;
            this.setResizeMode = setResizeMode;
            this.zoom = zoom;
        }
    }

    /** Id Just Player gives its aspect ratio button. */
    private static final int ASPECT_BUTTON_ID = Integer.MAX_VALUE - 100;
    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};

    private final Activity activity;
    private final TextView titleView;
    private Actions actions;
    private AutoSubs autoSubs;
    private ImageButton subtitleButton;
    private TextView speedBadge;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int accent;
    private boolean enabled;
    private TextView subtitleBadge;
    private TextView timeLeft;
    private TextView timeRight;
    private DefaultTimeBar timeBar;
    private Player player;
    private ImageButton playButton;
    private LinearLayout controls;
    private PlayerView playerView;
    private boolean scrubbing;
    private int barBottomSet = -1;

    /** The skin that draws the rounded time bar (static: called from CustomDefaultTimeBar.onDraw). */
    private static PlayerSkin drawing;
    private static java.lang.reflect.Field progressField, bufferedField, scrubberField;
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onEvents(Player p, Player.Events events) {
            updatePlayButton();
            if (events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)) updateSpeedBadge();
        }
    };

    private PlayerSkin(Activity activity, TextView titleView) {
        this.activity = activity;
        this.titleView = titleView;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(activity);
    }

    /** Call once, after PlayerActivity has built its controls. */
    public static PlayerSkin apply(Activity activity, PlayerView playerView, TextView titleView, Actions actions) {
        PlayerSkin skin = new PlayerSkin(activity, titleView);
        skin.actions = actions;
        skin.enabled = skin.prefs.getBoolean(KEY_ENABLED, true);
        if (!skin.enabled) return skin;
        try {
            skin.accent = Color.parseColor(skin.prefs.getString(KEY_ACCENT, DEFAULT_ACCENT));
        } catch (RuntimeException e) {
            skin.accent = Color.parseColor(DEFAULT_ACCENT);
        }
        try {
            skin.applyTo(playerView);
        } catch (RuntimeException e) {
            // Never break the player because of looks
            android.util.Log.w(AutoSubs.TAG, "Skin not applied", e);
        }
        return skin;
    }

    private void applyTo(PlayerView playerView) {
        this.playerView = playerView;
        // Soft gradients: dark at the top (title) and bottom (controls), clear in the middle
        View background = playerView.findViewById(R.id.exo_controls_background);
        if (background != null) {
            background.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xA0000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x8C000000, 0xF0000000}));
        }
        View bottomBar = playerView.findViewById(R.id.exo_bottom_bar);
        if (bottomBar != null) bottomBar.setBackground(new ColorDrawable(Color.TRANSPARENT));
        for (int id : new int[]{R.id.exo_top, R.id.exo_left, R.id.exo_right}) {
            View v = playerView.findViewById(id);
            if (v != null) v.setBackground(new ColorDrawable(Color.TRANSPARENT));
        }

        // Buttons bottom left; Media3's own (hidden) buttons come first in the row, so put ours in front
        setGravity(playerView.findViewById(R.id.exo_basic_controls), Gravity.CENTER_VERTICAL | Gravity.START);
        View scroll = playerView.findViewById(R.id.controls_scroll_view);
        setGravity(scroll, Gravity.BOTTOM | Gravity.START);
        if (scroll != null && scroll.getParent() instanceof ViewGroup) {
            ViewGroup row = (ViewGroup) scroll.getParent();
            row.removeView(scroll);
            row.addView(scroll, 0);
            row.setPadding(dp(16), row.getPaddingTop(), row.getPaddingRight(), row.getPaddingBottom());
            // Just Player keeps 170sp free on the left for the time, which now sits next to the time bar
            scroll.setPaddingRelative(0, scroll.getPaddingTop(), dp(16), scroll.getPaddingBottom());
        }
        LinearLayout controls = playerView.findViewById(R.id.controls);
        this.controls = controls;
        if (controls != null) {
            setGravity(controls, Gravity.CENTER_VERTICAL | Gravity.START);
            // "Open file" is rarely used with streams: move it to the end
            if (controls.getChildCount() > 1) {
                View open = controls.getChildAt(0);
                controls.removeViewAt(0);
                controls.addView(open);
            }
            movePlayPauseIntoRow(playerView, controls);
            View cc = replaceSubtitleButton(playerView, controls);
            addMenuButtons(controls);
            for (int i = 0; i < controls.getChildCount(); i++) {
                View child = controls.getChildAt(i);
                if (child instanceof ImageButton) styleButton((ImageButton) child);
                // One short press per button: no hidden long-press actions
                child.setOnLongClickListener(null);
                child.setLongClickable(false);
            }
            addSubtitleBadge(controls, cc);
            speedBadge = addBadge(controls, speedButton);
            controls.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> linkFocus());
        }

        // Thin accent time bar with the times left and right of it
        View exoTime = playerView.findViewById(R.id.exo_time);
        if (exoTime != null) exoTime.setVisibility(View.GONE);
        View bar = playerView.findViewById(R.id.exo_progress);
        if (bar instanceof DefaultTimeBar) {
            timeBar = (DefaultTimeBar) bar;
            enableRoundedBar();
            setBarColors();
            timeBar.addListener(new TimeBar.OnScrubListener() {
                @Override
                public void onScrubStart(TimeBar t, long position) {
                    scrubbing = true;
                }

                @Override
                public void onScrubMove(TimeBar t, long position) {
                }

                @Override
                public void onScrubStop(TimeBar t, long position, boolean canceled) {
                    scrubbing = false;
                }
            });
            addTimeLabels(timeBar);
        }

        // Title: no bar behind it, small, with a second info line
        if (titleView != null) {
            titleView.setBackground(new ColorDrawable(Color.TRANSPARENT));
            titleView.setMaxLines(3);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            titleView.setShadowLayer(dp(3), 0, dp(1), 0xAA000000);
            titleView.setPadding(dp(28), dp(18), dp(28), dp(8));
            titleView.setOnLongClickListener(null);
            titleView.setLongClickable(false);
        }
    }

    // ---------------------------------------------------------------- buttons with a menu above them

    void setAutoSubs(AutoSubs autoSubs) {
        this.autoSubs = autoSubs;
    }

    /** Our own subtitle button: one list with built-in and online subtitles (Media3's disables itself without built-in tracks). */
    private View replaceSubtitleButton(PlayerView playerView, LinearLayout controls) {
        View original = playerView.findViewById(R.id.exo_subtitle);
        int index = original != null && original.getParent() == controls ? controls.indexOfChild(original) : 1;
        if (original != null && original.getParent() == controls) controls.removeView(original);
        subtitleButton = new ImageButton(activity);
        subtitleButton.setImageResource(R.drawable.exo_ic_subtitle_off);
        subtitleButton.setContentDescription(activity.getString(R.string.skin_menu_subtitles));
        subtitleButton.setFocusable(true);
        subtitleButton.setId(View.generateViewId());
        subtitleButton.setOnClickListener(v -> {
            if (autoSubs != null) {
                SkinMenu.show(activity, playerView, v, accent, activity.getString(R.string.skin_menu_subtitles), autoSubs.subtitleMenu());
            }
        });
        controls.addView(subtitleButton, Math.min(index, controls.getChildCount()));
        return subtitleButton;
    }

    private ImageButton audioButton, speedButton;

    /** Audio and speed buttons after the subtitle button; aspect ratio gets a menu, the gear opens the settings. */
    private void addMenuButtons(LinearLayout controls) {
        int index = subtitleButton != null ? controls.indexOfChild(subtitleButton) + 1 : controls.getChildCount();
        audioButton = new ImageButton(activity);
        audioButton.setImageResource(R.drawable.exo_ic_audiotrack);
        audioButton.setContentDescription(activity.getString(R.string.skin_menu_audio));
        audioButton.setFocusable(true);
        audioButton.setId(View.generateViewId());
        audioButton.setOnClickListener(this::showAudioMenu);
        controls.addView(audioButton, index);
        speedButton = new ImageButton(activity);
        speedButton.setImageResource(R.drawable.exo_ic_speed);
        speedButton.setContentDescription(activity.getString(R.string.skin_menu_speed));
        speedButton.setFocusable(true);
        speedButton.setId(View.generateViewId());
        speedButton.setOnClickListener(this::showSpeedMenu);
        controls.addView(speedButton, index + 1);

        View aspect = controls.findViewById(ASPECT_BUTTON_ID);
        if (aspect != null) aspect.setOnClickListener(this::showAspectMenu);
        View settings = controls.findViewById(R.id.exo_settings);
        if (settings != null && actions != null) settings.setOnClickListener(v -> actions.openSettings.run());
    }

    private void showAudioMenu(View anchor) {
        List<SkinMenu.Item> items = new ArrayList<>();
        if (player != null) {
            int n = 0;
            for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
                if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
                for (int i = 0; i < group.length; i++) {
                    n++;
                    if (!group.isTrackSupported(i)) continue;
                    Format f = group.getTrackFormat(i);
                    final Tracks.Group g = group;
                    final int track = i;
                    items.add(SkinMenu.Item.option(trackName(f, n), audioDetail(f), group.isTrackSelected(i), () -> {
                        if (player == null) return;
                        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                                .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), track))
                                .build());
                    }));
                }
            }
        }
        if (items.isEmpty()) items.add(SkinMenu.Item.header(activity.getString(R.string.skin_menu_no_audio)));
        SkinMenu.show(activity, playerView, anchor, accent, activity.getString(R.string.skin_menu_audio), items);
    }

    private void showSpeedMenu(View anchor) {
        if (player == null) return;
        float current = player.getPlaybackParameters().speed;
        List<SkinMenu.Item> items = new ArrayList<>();
        for (float s : SPEEDS) {
            items.add(SkinMenu.Item.option(speedText(s), null, Math.abs(current - s) < 0.01f, () -> {
                if (player == null) return;
                player.setPlaybackSpeed(s);
                updateSpeedBadge();
            }));
        }
        SkinMenu.show(activity, playerView, anchor, accent, activity.getString(R.string.skin_menu_speed), items);
    }

    private void showAspectMenu(View anchor) {
        if (playerView == null || actions == null) return;
        int mode = playerView.getResizeMode();
        List<SkinMenu.Item> items = new ArrayList<>();
        items.add(SkinMenu.Item.option(activity.getString(R.string.skin_aspect_fit), null, mode == AspectRatioFrameLayout.RESIZE_MODE_FIT,
                () -> actions.setResizeMode.accept(AspectRatioFrameLayout.RESIZE_MODE_FIT)));
        items.add(SkinMenu.Item.option(activity.getString(R.string.skin_aspect_crop), null, mode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                () -> actions.setResizeMode.accept(AspectRatioFrameLayout.RESIZE_MODE_ZOOM)));
        if (actions.zoom != null) items.add(SkinMenu.Item.action(activity.getString(R.string.skin_aspect_zoom), actions.zoom));
        SkinMenu.show(activity, playerView, anchor, accent, activity.getString(R.string.skin_menu_aspect), items);
    }

    /** "Ondertitel uit bestand…" in the subtitle menu. */
    Runnable openSubtitleFileAction() {
        return actions != null ? actions.openSubtitleFile : null;
    }

    PlayerView playerView() {
        return playerView;
    }

    int accent() {
        return accent;
    }

    private static String speedText(float s) {
        java.text.NumberFormat nf = java.text.NumberFormat.getNumberInstance();
        nf.setMaximumFractionDigits(2);
        return nf.format(s) + "×";
    }

    private void updateSpeedBadge() {
        if (speedBadge == null || player == null) return;
        float s = player.getPlaybackParameters().speed;
        if (Math.abs(s - 1f) < 0.01f) {
            speedBadge.setVisibility(View.GONE);
        } else {
            speedBadge.setText(speedText(s));
            speedBadge.setVisibility(View.VISIBLE);
        }
    }

    /** "Japans", "Engels · Commentary", or "Spoor 2". */
    String trackName(Format f, int number) {
        List<String> parts = new ArrayList<>();
        if (f.language != null && !"und".equals(f.language)) {
            parts.add(autosubs.core.Languages.displayName(autosubs.core.Languages.canonical(f.language)));
        }
        if (f.label != null && !f.label.trim().isEmpty() && (parts.isEmpty() || !f.label.equalsIgnoreCase(parts.get(0)))) {
            parts.add(f.label.trim());
        }
        if (parts.isEmpty()) parts.add(activity.getString(R.string.skin_menu_track, number));
        return android.text.TextUtils.join(" · ", parts);
    }

    /** "5.1 · Dolby Digital+" */
    static String audioDetail(Format f) {
        List<String> parts = new ArrayList<>();
        if (f.channelCount >= 8) parts.add("7.1");
        else if (f.channelCount >= 6) parts.add("5.1");
        else if (f.channelCount == 2) parts.add("Stereo");
        else if (f.channelCount == 1) parts.add("Mono");
        String mime = f.sampleMimeType == null ? "" : f.sampleMimeType;
        String codec;
        switch (mime) {
            case MimeTypes.AUDIO_E_AC3_JOC: codec = "Atmos"; break;
            case MimeTypes.AUDIO_E_AC3: codec = "Dolby Digital+"; break;
            case MimeTypes.AUDIO_AC3: codec = "Dolby Digital"; break;
            case MimeTypes.AUDIO_TRUEHD: codec = "TrueHD"; break;
            case MimeTypes.AUDIO_AAC: codec = "AAC"; break;
            case MimeTypes.AUDIO_FLAC: codec = "FLAC"; break;
            case MimeTypes.AUDIO_OPUS: codec = "Opus"; break;
            case MimeTypes.AUDIO_VORBIS: codec = "Vorbis"; break;
            case MimeTypes.AUDIO_MPEG: codec = "MP3"; break;
            case MimeTypes.AUDIO_RAW: codec = "PCM"; break;
            default: codec = mime.startsWith(MimeTypes.AUDIO_DTS) ? "DTS" : null;
        }
        if (codec != null) parts.add(codec);
        return android.text.TextUtils.join(" · ", parts);
    }

    /**
     * Replaces the big play button in the middle by our own play/pause button as first button of the bottom row.
     * Just Player hides its button while a video loads; ours is always there. Ours takes over the view id, so
     * Just Player's "focus the play button when the controls appear" now focuses it. The loader stays centered.
     */
    private void movePlayPauseIntoRow(PlayerView playerView, LinearLayout controls) {
        View original = playerView.findViewById(R.id.exo_play_pause);
        if (original != null && original.getParent() instanceof ViewGroup) {
            ((ViewGroup) original.getParent()).removeView(original);
            original.setId(View.NO_ID);
        }
        playButton = new ImageButton(activity);
        playButton.setId(R.id.exo_play_pause);
        playButton.setImageResource(R.drawable.exo_styled_controls_play);
        playButton.setContentDescription(activity.getString(R.string.exo_controls_play_description));
        playButton.setFocusable(true);
        playButton.setOnClickListener(v -> {
            if (player != null) Util.handlePlayPauseButtonAction(player);
        });
        controls.addView(playButton, 0);
    }

    private void updatePlayButton() {
        if (playButton == null || player == null) return;
        boolean showPlay = Util.shouldShowPlayButton(player);
        playButton.setImageResource(showPlay ? R.drawable.exo_styled_controls_play : R.drawable.exo_styled_controls_pause);
        playButton.setContentDescription(activity.getString(showPlay
                ? R.string.exo_controls_play_description : R.string.exo_controls_pause_description));
    }

    /** Not used for navigation any more (see dispatchKeyEvent); keeps ids for accessibility. */
    private void linkFocus() {
        if (controls == null) return;
        for (int i = 0; i < controls.getChildCount(); i++) {
            View v = controls.getChildAt(i);
            if (v.isFocusable() && v.getId() == View.NO_ID) v.setId(View.generateViewId());
        }
    }

    private boolean usable(View v) {
        return v.getVisibility() == View.VISIBLE && v.isFocusable() && v.isEnabled();
    }

    /**
     * Remote control navigation in the controls: left/right moves to the next usable button of the row (disabled
     * buttons are skipped, it never jumps to the time bar), up goes to the time bar, down from the time bar
     * comes back to the play button.
     */
    private boolean navigate(KeyEvent event) {
        if (playerView == null || controls == null || !playerView.isControllerFullyVisible()) return false;
        View focused = activity.getCurrentFocus();
        if (focused == null) return false;
        int key = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (focused.getParent() == controls) {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (down) {
                    int step = key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                    for (int i = controls.indexOfChild(focused) + step; i >= 0 && i < controls.getChildCount(); i += step) {
                        View v = controls.getChildAt(i);
                        if (usable(v)) {
                            v.requestFocus();
                            break;
                        }
                    }
                }
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_UP) {
                if (down && timeBar != null && usable(timeBar)) timeBar.requestFocus();
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) return true;
        } else if (focused == timeBar && key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (down && playButton != null) playButton.requestFocus();
            return true;
        }
        return false;
    }

    /** OK on the time bar plays/pauses (while scrubbing with left/right it confirms the new position). */
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!enabled) return false;
        if (navigate(event)) return true;
        if (timeBar == null || !timeBar.isFocused() || player == null || scrubbing) return false;
        int key = event.getKeyCode();
        if (key != KeyEvent.KEYCODE_DPAD_CENTER && key != KeyEvent.KEYCODE_ENTER
                && key != KeyEvent.KEYCODE_NUMPAD_ENTER && key != KeyEvent.KEYCODE_BUTTON_A) return false;
        if (event.getAction() == KeyEvent.ACTION_UP) Util.handlePlayPauseButtonAction(player);
        return true;
    }

    // ---------------------------------------------------------------- rounded time bar

    /**
     * Called from CustomDefaultTimeBar.onDraw before the default drawing. Draws the bar with rounded ends;
     * the default bar colors are transparent, so Media3 only adds the scrubber on top.
     */
    public static void drawTimeBar(DefaultTimeBar bar, Canvas canvas) {
        PlayerSkin skin = drawing;
        if (skin == null || bar != skin.timeBar || progressField == null) return;
        try {
            Rect progress = (Rect) progressField.get(bar);
            Rect buffered = (Rect) bufferedField.get(bar);
            Rect played = (Rect) scrubberField.get(bar);
            if (progress == null || progress.width() <= 0) return;
            float r = progress.height() / 2f;
            skin.barPaint.setColor(0x40FFFFFF);
            skin.barRect.set(progress);
            canvas.drawRoundRect(skin.barRect, r, r, skin.barPaint);
            if (buffered != null && buffered.right > progress.left) {
                skin.barPaint.setColor(0x59FFFFFF);
                skin.barRect.set(progress.left, progress.top, Math.min(buffered.right, progress.right), progress.bottom);
                canvas.drawRoundRect(skin.barRect, r, r, skin.barPaint);
            }
            if (played != null && played.right > progress.left) {
                skin.barPaint.setColor(skin.accent);
                skin.barRect.set(progress.left, progress.top, Math.min(played.right, progress.right), progress.bottom);
                canvas.drawRoundRect(skin.barRect, r, r, skin.barPaint);
            }
        } catch (Exception ignored) {
        }
    }

    private boolean enableRoundedBar() {
        try {
            progressField = DefaultTimeBar.class.getDeclaredField("progressBar");
            bufferedField = DefaultTimeBar.class.getDeclaredField("bufferedBar");
            scrubberField = DefaultTimeBar.class.getDeclaredField("scrubberBar");
            progressField.setAccessible(true);
            bufferedField.setAccessible(true);
            scrubberField.setAccessible(true);
            drawing = this;
            return true;
        } catch (Exception e) {
            progressField = null;
            return false;
        }
    }

    private void setBarColors() {
        if (timeBar == null) return;
        timeBar.setScrubberColor(accent);
        if (drawing == this && progressField != null) {
            // We draw the bar ourselves (rounded)
            timeBar.setPlayedColor(Color.TRANSPARENT);
            timeBar.setBufferedColor(Color.TRANSPARENT);
            timeBar.setUnplayedColor(Color.TRANSPARENT);
        } else {
            timeBar.setPlayedColor(accent);
            timeBar.setBufferedColor(0x59FFFFFF);
            timeBar.setUnplayedColor(0x40FFFFFF);
        }
    }

    /** Rounded accent highlight behind the focused button. */
    private void styleButton(ImageButton b) {
        int inset = dp(4);
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(accent);
        focused.setCornerRadius(dp(12));
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor((accent & 0x00FFFFFF) | 0x80000000);
        pressed.setCornerRadius(dp(12));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, new InsetDrawable(focused, inset));
        bg.addState(new int[]{android.R.attr.state_pressed}, new InsetDrawable(pressed, inset));
        bg.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        b.setBackground(bg);
        int iconOnAccent = isLight(accent) ? 0xFF111111 : Color.WHITE;
        ImageViewCompat.setImageTintList(b, new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}},
                new int[]{iconOnAccent, Color.WHITE}));
        // Media3's and Just Player's buttons have different sizes and paddings: make them all the same
        int size = dp(52);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.gravity = Gravity.CENTER_VERTICAL;
        lp.setMargins(dp(2), 0, dp(2), 0);
        b.setLayoutParams(lp);
        b.setPadding(dp(13), dp(13), dp(13), dp(13));
        b.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
    }

    private void addTimeLabels(DefaultTimeBar bar) {
        if (!(bar.getParent() instanceof FrameLayout) || !(bar.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
        FrameLayout parent = (FrameLayout) bar.getParent();
        FrameLayout.LayoutParams barLp = (FrameLayout.LayoutParams) bar.getLayoutParams();
        final int side = dp(104);
        barLp.leftMargin = side;
        barLp.rightMargin = side;
        bar.setLayoutParams(barLp);
        // Just Player resets the time bar margins whenever the window insets change: put ours back
        final int extraBottom = dp(18);
        barBottomSet = barLp.bottomMargin + extraBottom;
        barLp.bottomMargin = barBottomSet;
        bar.setLayoutParams(barLp);
        bar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (!(v.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) v.getLayoutParams();
            boolean changed = false;
            if (p.leftMargin != side || p.rightMargin != side) {
                p.leftMargin = side;
                p.rightMargin = side;
                changed = true;
            }
            // Just Player sets its own bottom margin when the insets change: keep extra room above the buttons
            if (p.bottomMargin != barBottomSet) {
                barBottomSet = p.bottomMargin + extraBottom;
                p.bottomMargin = barBottomSet;
                changed = true;
            }
            if (changed) v.post(() -> v.setLayoutParams(p));
            alignTimeLabels(p);
        });
        timeLeft = timeLabel(Gravity.END);
        timeRight = timeLabel(Gravity.START);
        int index = parent.indexOfChild(bar) + 1;
        parent.addView(timeLeft, index, timeLabelParams(barLp, side, Gravity.BOTTOM | Gravity.START));
        parent.addView(timeRight, index + 1, timeLabelParams(barLp, side, Gravity.BOTTOM | Gravity.END));
        // Media3 slides and fades the time bar when the controls hide: let the times follow it every frame
        parent.getViewTreeObserver().addOnPreDrawListener(() -> {
            for (TextView t : new TextView[]{timeLeft, timeRight}) {
                if (t.getTranslationY() != bar.getTranslationY()) t.setTranslationY(bar.getTranslationY());
                if (t.getAlpha() != bar.getAlpha()) t.setAlpha(bar.getAlpha());
                int vis = bar.getVisibility();
                if (t.getVisibility() != vis) t.setVisibility(vis);
            }
            return true;
        });
    }

    /** Keep the time labels at the same height as the time bar (its bottom margin changes with the insets). */
    private void alignTimeLabels(FrameLayout.LayoutParams barLp) {
        for (TextView t : new TextView[]{timeLeft, timeRight}) {
            if (t == null || !(t.getLayoutParams() instanceof FrameLayout.LayoutParams)) continue;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) t.getLayoutParams();
            int bottom = barLp.bottomMargin - dp(3);
            if (lp.bottomMargin != bottom || lp.height != barLp.height) {
                lp.bottomMargin = bottom;
                lp.height = barLp.height;
                t.post(() -> t.setLayoutParams(lp));
            }
        }
    }

    private TextView timeLabel(int textGravity) {
        TextView t = new TextView(activity);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setGravity(Gravity.BOTTOM | textGravity);
        t.setPadding(dp(12), 0, dp(12), 0);
        t.setFocusable(false);
        t.setShadowLayer(dp(2), 0, dp(1), 0xAA000000);
        return t;
    }

    private FrameLayout.LayoutParams timeLabelParams(FrameLayout.LayoutParams barLp, int width, int gravity) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, barLp.height, gravity);
        lp.bottomMargin = barLp.bottomMargin - dp(3);
        return lp;
    }

    // ---------------------------------------------------------------- player updates

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateTimes();
            main.postDelayed(this, 500);
        }
    };

    /** Called for every new player (Just Player sets the time bar colors again for each video). */
    public void onPlayerStarted(Player player) {
        if (this.player != null && this.player != player) this.player.removeListener(playerListener);
        this.player = player;
        if (!enabled) return;
        player.addListener(playerListener);
        updatePlayButton();
        updateSpeedBadge();
        setBarColors();
        linkFocus();
        main.removeCallbacks(tick);
        if (timeLeft != null) main.post(tick);
    }

    public void onPlayerStopped() {
        main.removeCallbacks(tick);
        if (player != null) player.removeListener(playerListener);
        player = null;
    }

    private void updateTimes() {
        if (player == null || timeLeft == null || !timeLeft.isShown()) return;
        long pos = Math.max(0, player.getCurrentPosition());
        long dur = player.getDuration();
        boolean hours = dur != C.TIME_UNSET && dur >= 3600_000L;
        timeLeft.setText(format(pos, hours));
        if (dur == C.TIME_UNSET || dur <= 0) {
            timeRight.setText("");
            return;
        }
        String mode = prefs.getString(KEY_TIME, TIME_TOTAL);
        if (TIME_REMAINING.equals(mode)) {
            timeRight.setText("-" + format(Math.max(0, dur - pos), hours));
        } else if (TIME_END.equals(mode)) {
            float speed = player.getPlaybackParameters().speed;
            long left = (long) ((dur - pos) / (speed > 0 ? speed : 1f));
            String clock = DateFormat.getTimeFormat(activity).format(new Date(System.currentTimeMillis() + left));
            timeRight.setText(activity.getString(R.string.skin_time_ends_at, clock));
        } else {
            timeRight.setText(format(dur, hours));
        }
    }

    private static String format(long ms, boolean hours) {
        long s = ms / 1000;
        if (hours) return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        return String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60);
    }

    // ---------------------------------------------------------------- subtitle badge and title

    /** Small label next to the subtitle button with the language of the online subtitle ("NL"). */
    private void addSubtitleBadge(LinearLayout controls, View subtitleButton) {
        subtitleBadge = addBadge(controls, subtitleButton);
    }

    private TextView addBadge(LinearLayout controls, View button) {
        if (button == null) return null;
        int index = controls.indexOfChild(button);
        if (index < 0) return null;
        TextView badge = new TextView(activity);
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setFocusable(false);
        badge.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(0, 0, dp(6), 0);
        badge.setGravity(Gravity.CENTER_VERTICAL);
        controls.addView(badge, index + 1, lp);
        return badge;
    }

    /** Language code (e.g. "nld") of the subtitle that is shown, or null when none is shown. */
    public void setSubtitleLanguage(String lang) {
        if (subtitleButton != null) {
            subtitleButton.setImageResource(lang != null ? R.drawable.exo_ic_subtitle_on : R.drawable.exo_ic_subtitle_off);
        }
        if (subtitleBadge == null) return;
        String iso1 = lang == null ? null : autosubs.core.Languages.toIso1(lang);
        if (iso1 == null) {
            subtitleBadge.setVisibility(View.GONE);
        } else {
            subtitleBadge.setText(iso1.toUpperCase(Locale.ROOT).replace("-", " "));
            subtitleBadge.setVisibility(View.VISIBLE);
        }
    }

    private String show, episodeTitle;
    private int year = -1, season = -1, episode = -1;
    private Tracks tracks;
    private CharSequence lastSet, fileTitle;

    /** Parts of the title: from the file name first, later from Cinemeta. season/episode -1 for a movie. */
    public void setTitleParts(String show, int year, int season, int episode, String episodeTitle) {
        if (show == null) return;
        this.show = show;
        this.year = year;
        this.season = season;
        this.episode = episode;
        this.episodeTitle = episodeTitle;
        renderTitle();
    }

    public void setTracks(Tracks tracks) {
        this.tracks = tracks;
        renderTitle();
    }

    /** The title in the chosen format, e.g. "Young Sheldon · S06E22 · A Tornado..." or "The Batman (2022)". */
    static String formatTitle(String format, String show, int year, int season, int episode, String episodeTitle) {
        if (show == null) return null;
        String pattern = TITLE_FORMATS[0][1];
        for (String[] f : TITLE_FORMATS) if (f[0].equals(format)) pattern = f[1];
        if (season < 0 || episode < 0) {
            // Movie: the title, with the year unless "title only"
            return year > 0 && !"S".equals(pattern) ? show + " (" + year + ")" : show;
        }
        boolean hasName = episodeTitle != null && !episodeTitle.isEmpty();
        // Without an episode title: "show · S06E22" if the format would otherwise lose the show or be empty
        if (!hasName && pattern.contains("E") && !pattern.contains("S")) pattern = "S C";
        else if (!hasName && pattern.contains("E") && !pattern.contains("C")) pattern = pattern.replace("E", "C");
        String code = String.format(Locale.ROOT, "S%02dE%02d", season, episode);
        StringBuilder out = new StringBuilder();
        for (String line : pattern.split("\\|")) {
            StringBuilder l = new StringBuilder();
            for (String part : line.split(" ")) {
                String text = part.equals("S") ? show : part.equals("C") ? code : hasName ? episodeTitle : null;
                if (text == null) continue;
                if (l.length() > 0) l.append(" · ");
                l.append(text);
            }
            if (l.length() == 0) continue;
            if (out.length() > 0) out.append('\n');
            out.append(l);
        }
        return out.toString();
    }

    private void renderTitle() {
        if (!enabled || titleView == null) return;
        CharSequence current = titleView.getText();
        if (lastSet == null || !lastSet.toString().equals(String.valueOf(current))) fileTitle = current;
        String format = prefs.getString(KEY_TITLE, TITLE_CODE_EPISODE);
        String title;
        if (TITLE_NONE.equals(format)) title = "";
        else if (TITLE_FILENAME.equals(format)) title = fileTitle == null ? "" : fileTitle.toString();
        else title = formatTitle(format, show, year, season, episode, episodeTitle);
        if (title == null) {
            if (tracks == null) return;
            title = fileTitle == null ? "" : fileTitle.toString();
        }
        String info = TITLE_NONE.equals(format) || tracks == null ? "" : info(tracks);
        SpannableStringBuilder sb = new SpannableStringBuilder(title);
        int nl = title.indexOf('\n');
        if (nl >= 0) sb.setSpan(new AbsoluteSizeSpan(14, true), nl + 1, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (!info.isEmpty()) {
            if (sb.length() == 0) sb.append('\u200B');
            int start = sb.length();
            sb.append('\n').append(info);
            sb.setSpan(new AbsoluteSizeSpan(12, true), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(0xB3FFFFFF), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        titleView.setText(sb);
        lastSet = titleView.getText();
    }

    static String info(Tracks tracks) {
        List<String> parts = new ArrayList<>();
        Format video = selected(tracks, C.TRACK_TYPE_VIDEO);
        Format audio = selected(tracks, C.TRACK_TYPE_AUDIO);
        if (video != null) {
            int h = Math.min(video.height, video.width > 0 ? video.width * 9 / 16 + 1 : video.height);
            if (video.width >= 3200 || h >= 2000) parts.add("4K");
            else if (video.width >= 1800 || h >= 1000) parts.add("1080p");
            else if (video.width >= 1200 || h >= 700) parts.add("720p");
            String codecs = video.codecs == null ? "" : video.codecs;
            if (MimeTypes.VIDEO_DOLBY_VISION.equals(video.sampleMimeType) || codecs.startsWith("dvh") || codecs.startsWith("dvav")) {
                parts.add("Dolby Vision");
            } else if (video.colorInfo != null && video.colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084) {
                parts.add("HDR");
            } else if (video.colorInfo != null && video.colorInfo.colorTransfer == C.COLOR_TRANSFER_HLG) {
                parts.add("HLG");
            }
        }
        if (audio != null) {
            String mime = audio.sampleMimeType == null ? "" : audio.sampleMimeType;
            if (MimeTypes.AUDIO_E_AC3_JOC.equals(mime)) parts.add("Atmos");
            else if (MimeTypes.AUDIO_TRUEHD.equals(mime)) parts.add("TrueHD");
            else if (mime.startsWith(MimeTypes.AUDIO_DTS)) parts.add("DTS");
            if (audio.channelCount >= 8) parts.add("7.1");
            else if (audio.channelCount >= 6) parts.add("5.1");
        }
        return android.text.TextUtils.join(" · ", parts);
    }

    private static Format selected(Tracks tracks, int type) {
        if (tracks == null) return null;
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != type || !g.isSelected()) continue;
            for (int i = 0; i < g.length; i++) if (g.isTrackSelected(i)) return g.getTrackFormat(i);
        }
        return null;
    }

    // ---------------------------------------------------------------- helpers

    static boolean isLight(int color) {
        double l = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color);
        return l > 186;
    }

    private static void setGravity(View v, int gravity) {
        if (v == null) return;
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp instanceof FrameLayout.LayoutParams) {
            ((FrameLayout.LayoutParams) lp).gravity = gravity;
            v.setLayoutParams(lp);
        } else if (lp instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) lp).gravity = gravity;
            v.setLayoutParams(lp);
        }
        if (v instanceof LinearLayout) ((LinearLayout) v).setGravity(gravity);
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
