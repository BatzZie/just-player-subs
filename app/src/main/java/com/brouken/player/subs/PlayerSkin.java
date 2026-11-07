package com.brouken.player.subs;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
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
import androidx.media3.common.Tracks;
import androidx.media3.ui.DefaultTimeBar;
import androidx.media3.ui.PlayerView;

import com.brouken.player.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Modern look for Just Player's controls, applied on top of its own layout (so upstream layouts stay untouched):
 * buttons bottom left, time bottom right, soft gradients instead of black bars, a white "pill" on the focused
 * button (clear on TV), a title with video/audio info top left and the active subtitle language next to the
 * subtitle button.
 */
public final class PlayerSkin {

    private final Activity activity;
    private final TextView titleView;
    private TextView subtitleBadge;

    private PlayerSkin(Activity activity, TextView titleView) {
        this.activity = activity;
        this.titleView = titleView;
    }

    /** Call once, after PlayerActivity has built its controls. */
    public static PlayerSkin apply(Activity activity, PlayerView playerView, TextView titleView) {
        PlayerSkin skin = new PlayerSkin(activity, titleView);
        try {
            skin.applyTo(playerView);
        } catch (RuntimeException e) {
            // Never break the player because of looks
            android.util.Log.w(AutoSubs.TAG, "Skin not applied", e);
        }
        return skin;
    }

    private void applyTo(PlayerView playerView) {
        // Soft gradients: dark at the top (title) and bottom (controls), clear in the middle
        View background = playerView.findViewById(R.id.exo_controls_background);
        if (background != null) {
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xB3000000, 0x00000000, 0x00000000, 0x00000000, 0xD9000000});
            background.setBackground(g);
        }
        View bottomBar = playerView.findViewById(R.id.exo_bottom_bar);
        if (bottomBar != null) bottomBar.setBackground(new ColorDrawable(Color.TRANSPARENT));
        for (int id : new int[]{R.id.exo_top, R.id.exo_left, R.id.exo_right}) {
            View v = playerView.findViewById(id);
            if (v != null) v.setBackground(new ColorDrawable(Color.TRANSPARENT));
        }

        // Time to the right, buttons to the left
        setGravity(playerView.findViewById(R.id.exo_time), Gravity.CENTER_VERTICAL | Gravity.END);
        setGravity(playerView.findViewById(R.id.exo_basic_controls), Gravity.CENTER_VERTICAL | Gravity.START);
        View scroll = playerView.findViewById(R.id.controls_scroll_view);
        setGravity(scroll, Gravity.BOTTOM | Gravity.START);
        // Media3's own (hidden) buttons come first in the row and take up space: put ours in front
        if (scroll != null && scroll.getParent() instanceof ViewGroup) {
            ViewGroup row = (ViewGroup) scroll.getParent();
            row.removeView(scroll);
            row.addView(scroll, 0);
            row.setPadding(dp(16), row.getPaddingTop(), row.getPaddingRight(), row.getPaddingBottom());
        }
        LinearLayout controls = playerView.findViewById(R.id.controls);
        if (controls != null) {
            setGravity(controls, Gravity.BOTTOM | Gravity.START);
            // "Open file" is rarely used with streams: move it to the end
            if (controls.getChildCount() > 1) {
                View open = controls.getChildAt(0);
                controls.removeViewAt(0);
                controls.addView(open);
            }
            for (int i = 0; i < controls.getChildCount(); i++) {
                View child = controls.getChildAt(i);
                if (child instanceof ImageButton) stylePill((ImageButton) child);
            }
            addSubtitleBadge(controls, playerView.findViewById(R.id.exo_subtitle));
        }
        View time = playerView.findViewById(R.id.exo_time);
        if (time != null) time.setPadding(time.getPaddingLeft(), time.getPaddingTop(), dp(20), time.getPaddingBottom());

        // Time bar: white played part, subtle rest
        View bar = playerView.findViewById(R.id.exo_progress);
        if (bar instanceof DefaultTimeBar) {
            DefaultTimeBar t = (DefaultTimeBar) bar;
            t.setPlayedColor(Color.WHITE);
            t.setScrubberColor(Color.WHITE);
            t.setUnplayedColor(0x40FFFFFF);
        }

        // Title: no bar behind it, larger text with a second info line
        if (titleView != null) {
            titleView.setBackground(new ColorDrawable(Color.TRANSPARENT));
            titleView.setMaxLines(2);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            titleView.setShadowLayer(dp(3), 0, dp(1), 0xAA000000);
            titleView.setPadding(dp(28), dp(22), dp(28), dp(8));
        }
    }

    /** White rounded "pill" behind the focused button, with a dark icon. */
    private void stylePill(ImageButton b) {
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(Color.WHITE);
        focused.setCornerRadius(dp(24));
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(0x66FFFFFF);
        pressed.setCornerRadius(dp(24));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{android.R.attr.state_pressed}, pressed);
        bg.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        b.setBackground(bg);
        ImageViewCompat.setImageTintList(b, new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}},
                new int[]{0xFF111111, Color.WHITE}));
        ViewGroup.LayoutParams lp = b.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) lp).setMargins(dp(3), 0, dp(3), 0);
            b.setLayoutParams(lp);
        }
    }

    /** Small label next to the subtitle button with the language of the online subtitle ("NL"). */
    private void addSubtitleBadge(LinearLayout controls, View subtitleButton) {
        if (subtitleButton == null) return;
        int index = controls.indexOfChild(subtitleButton);
        if (index < 0) return;
        subtitleBadge = new TextView(activity);
        subtitleBadge.setTextColor(Color.WHITE);
        subtitleBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitleBadge.setTypeface(Typeface.DEFAULT_BOLD);
        subtitleBadge.setFocusable(false);
        subtitleBadge.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(0, 0, dp(6), 0);
        subtitleBadge.setGravity(Gravity.CENTER_VERTICAL);
        controls.addView(subtitleBadge, index + 1, lp);
    }

    /** Language code (e.g. "nld") of the active online subtitle, or null when none is active. */
    public void setSubtitleLanguage(String lang) {
        if (subtitleBadge == null) return;
        String iso1 = lang == null ? null : autosubs.core.Languages.toIso1(lang);
        if (iso1 == null) {
            subtitleBadge.setVisibility(View.GONE);
        } else {
            subtitleBadge.setText(iso1.toUpperCase(java.util.Locale.ROOT).replace("-", " "));
            subtitleBadge.setVisibility(View.VISIBLE);
        }
    }

    /** Title (e.g. "MobLand · S2 E1") and an info line from the selected tracks ("4K · HDR · Atmos"). */
    public void setTitle(String title, Tracks tracks) {
        if (titleView == null || title == null) return;
        String info = info(tracks);
        SpannableStringBuilder sb = new SpannableStringBuilder(title);
        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (!info.isEmpty()) {
            int start = sb.length();
            sb.append('\n').append(info);
            sb.setSpan(new AbsoluteSizeSpan(15, true), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(0xCCFFFFFF), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        titleView.setText(sb);
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

    private static void setGravity(View v, int gravity) {
        if (v == null) return;
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp instanceof FrameLayout.LayoutParams) {
            ((FrameLayout.LayoutParams) lp).gravity = gravity;
            v.setLayoutParams(lp);
        } else if (lp instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) lp).gravity = gravity;
            v.setLayoutParams(lp);
        } else if (v instanceof LinearLayout) {
            ((LinearLayout) v).setGravity(gravity);
        }
        if (v instanceof LinearLayout) ((LinearLayout) v).setGravity(gravity);
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
