package com.brouken.player.subs;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.List;

/**
 * A menu that opens right above its button in the control row, made for the remote: up/down to choose, OK to
 * confirm, back to close. Keeps the controls visible while it is open.
 */
final class SkinMenu {

    static final class Item {
        static final int OPTION = 0, ACTION = 1, HEADER = 2, DIVIDER = 3;
        final int kind;
        final CharSequence text;
        final String detail;
        final boolean checked;
        final Runnable action;

        private Item(int kind, CharSequence text, String detail, boolean checked, Runnable action) {
            this.kind = kind;
            this.text = text;
            this.detail = detail;
            this.checked = checked;
            this.action = action;
        }

        /** A choice with a check mark when it is the current one. */
        static Item option(CharSequence text, String detail, boolean checked, Runnable action) {
            return new Item(OPTION, text, detail, checked, action);
        }

        static Item action(CharSequence text, Runnable action) {
            return new Item(ACTION, text, null, false, action);
        }

        /** Non-selectable line, e.g. a section name or "Searching…". */
        static Item header(CharSequence text) {
            return new Item(HEADER, text, null, false, null);
        }

        static Item divider() {
            return new Item(DIVIDER, null, null, false, null);
        }
    }

    private static PopupWindow open;

    static boolean isOpen() {
        return open != null && open.isShowing();
    }

    static void dismissOpen() {
        if (isOpen()) open.dismiss();
    }

    static void show(Activity activity, PlayerView playerView, View anchor, int accent, String title, List<Item> items) {
        dismissOpen();
        float density = activity.getResources().getDisplayMetrics().density;
        int screenW = activity.getResources().getDisplayMetrics().widthPixels;
        boolean lightAccent = PlayerSkin.isLight(accent);

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(density, 8), dp(density, 6), dp(density, 8), dp(density, 8));

        if (title != null) {
            TextView t = new TextView(activity);
            t.setText(title);
            t.setTextColor(0xB3FFFFFF);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setPadding(dp(density, 12), dp(density, 8), dp(density, 12), dp(density, 6));
            list.addView(t);
        }

        final PopupWindow[] window = new PopupWindow[1];
        View firstFocus = null, checkedFocus = null;
        List<View> focusables = new ArrayList<>();
        for (Item item : items) {
            if (item.kind == Item.DIVIDER) {
                View line = new View(activity);
                line.setBackgroundColor(0x33FFFFFF);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(density, 1)));
                lp.setMargins(dp(density, 10), dp(density, 6), dp(density, 10), dp(density, 6));
                list.addView(line, lp);
                continue;
            }
            TextView row = new TextView(activity);
            row.setSingleLine(true);
            row.setEllipsize(TextUtils.TruncateAt.END);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, item.kind == Item.HEADER ? 13 : 15);
            if (item.kind == Item.HEADER) {
                row.setText(item.text);
                row.setTextColor(0x99FFFFFF);
                row.setPadding(dp(density, 12), dp(density, 6), dp(density, 12), dp(density, 4));
                list.addView(row);
                continue;
            }
            SpannableStringBuilder sb = new SpannableStringBuilder();
            if (item.kind == Item.OPTION) sb.append(item.checked ? "✓  " : "    ");
            sb.append(item.text);
            if (item.detail != null && !item.detail.isEmpty()) {
                int start = sb.length();
                sb.append("   ").append(item.detail);
                sb.setSpan(new ForegroundColorSpan(0x99FFFFFF), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            row.setText(sb);
            row.setTextColor(new android.content.res.ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}},
                    new int[]{lightAccent ? 0xFF111111 : Color.WHITE, Color.WHITE}));
            row.setPadding(dp(density, 12), dp(density, 9), dp(density, 16), dp(density, 9));
            row.setFocusable(true);
            row.setClickable(true);
            GradientDrawable focused = new GradientDrawable();
            focused.setColor(accent);
            focused.setCornerRadius(dp(density, 10));
            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[]{android.R.attr.state_focused}, focused);
            bg.addState(new int[]{android.R.attr.state_pressed}, focused);
            bg.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
            row.setBackground(bg);
            final Runnable action = item.action;
            row.setOnClickListener(v -> {
                if (window[0] != null) window[0].dismiss();
                if (action != null) action.run();
            });
            list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            focusables.add(row);
            if (firstFocus == null) firstFocus = row;
            if (item.checked && checkedFocus == null) checkedFocus = row;
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(list);
        GradientDrawable panel = new GradientDrawable();
        panel.setColor(0xF0202024);
        panel.setCornerRadius(dp(density, 16));
        panel.setStroke(Math.max(1, dp(density, 1)), 0x22FFFFFF);
        scroll.setBackground(panel);

        // Size: as wide as the content (within limits), not higher than the space above the button
        int minW = dp(density, 240), maxW = Math.min(dp(density, 520), screenW - dp(density, 32));
        list.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int width = Math.max(minW, Math.min(maxW, list.getMeasuredWidth()));
        list.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int maxH = Math.max(dp(density, 120), loc[1] - dp(density, 24));
        int height = Math.min(list.getMeasuredHeight(), maxH);

        PopupWindow popup = new PopupWindow(scroll, width, height, true);
        window[0] = popup;
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        if (android.os.Build.VERSION.SDK_INT >= 21) popup.setElevation(dp(density, 8));

        // Left/right would leave the list in odd ways: keep them inside
        for (View v : focusables) {
            v.setOnKeyListener((v1, keyCode, event) -> keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT);
        }

        // Keep the controls up while the menu is open
        final int timeout = playerView != null ? playerView.getControllerShowTimeoutMs() : 0;
        if (playerView != null) playerView.setControllerShowTimeoutMs(-1);
        popup.setOnDismissListener(() -> {
            if (open == popup) open = null;
            if (playerView != null) {
                playerView.setControllerShowTimeoutMs(timeout);
                playerView.showController();
            }
            if (anchor.isAttachedToWindow()) anchor.requestFocus();
        });

        int x = loc[0] + anchor.getWidth() / 2 - width / 2;
        x = Math.max(dp(density, 16), Math.min(x, screenW - width - dp(density, 16)));
        int y = loc[1] - height - dp(density, 8);
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, Math.max(dp(density, 8), y));
        open = popup;

        final View focus = checkedFocus != null ? checkedFocus : firstFocus;
        if (focus != null) {
            scroll.post(() -> {
                focus.requestFocus();
                scroll.scrollTo(0, Math.max(0, focus.getTop() - height / 3));
            });
        }
    }

    private static int dp(float density, int v) {
        return Math.round(v * density);
    }
}
