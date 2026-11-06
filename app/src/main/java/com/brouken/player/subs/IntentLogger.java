package com.brouken.player.subs;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.util.Arrays;

/**
 * Step 0: logs everything an external app (e.g. Stremio) sends along with the play intent.
 * Read with: adb logcat -s AutoSubs
 */
public final class IntentLogger {

    public static final String TAG = "AutoSubs";

    private IntentLogger() {}

    public static void log(String source, Intent intent) {
        if (intent == null) {
            Log.i(TAG, source + ": intent is null");
            return;
        }
        Log.i(TAG, "===== " + source + " =====");
        Log.i(TAG, "action: " + intent.getAction());
        Log.i(TAG, "data: " + intent.getData());
        Log.i(TAG, "type: " + intent.getType());
        Log.i(TAG, "package (sender hint): " + intent.getPackage());
        Log.i(TAG, "flags: 0x" + Integer.toHexString(intent.getFlags()));
        if (intent.getClipData() != null) {
            Log.i(TAG, "clipData: " + intent.getClipData());
        }

        Bundle extras;
        try {
            extras = intent.getExtras();
        } catch (RuntimeException e) {
            Log.w(TAG, "extras could not be read", e);
            return;
        }
        if (extras == null || extras.isEmpty()) {
            Log.i(TAG, "extras: (none)");
            return;
        }
        for (String key : extras.keySet()) {
            Object value;
            try {
                value = extras.get(key);
            } catch (RuntimeException e) {
                value = "<unreadable: " + e.getClass().getSimpleName() + ">";
            }
            Log.i(TAG, "extra " + key + " = " + describe(value));
        }
    }

    private static String describe(Object value) {
        if (value == null) return "null";
        String type = value.getClass().getSimpleName();
        String text;
        if (value instanceof Object[]) {
            text = Arrays.deepToString((Object[]) value);
        } else if (value instanceof int[]) {
            text = Arrays.toString((int[]) value);
        } else if (value instanceof long[]) {
            text = Arrays.toString((long[]) value);
        } else if (value instanceof Bundle) {
            Bundle b = (Bundle) value;
            StringBuilder sb = new StringBuilder("{");
            for (String k : b.keySet()) {
                sb.append(k).append('=').append(b.get(k)).append(", ");
            }
            text = sb.append('}').toString();
        } else {
            text = String.valueOf(value);
        }
        return "(" + type + ") " + text;
    }
}
