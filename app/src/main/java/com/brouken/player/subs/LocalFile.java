package com.brouken.player.subs;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import autosubs.core.MkvIndex;

/** Reading parts of a local video (content:// or file://) for the OpenSubtitles hash and the MKV index. */
final class LocalFile {

    private LocalFile() {}

    static boolean isLocal(Uri uri) {
        String s = uri == null ? null : uri.getScheme();
        return "content".equals(s) || "file".equals(s);
    }

    static String displayName(Context context, Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (Cursor c = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
            } catch (Exception ignored) {
            }
        }
        return uri.getLastPathSegment();
    }

    static long size(Context context, Uri uri) {
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            return pfd == null ? -1 : pfd.getStatSize();
        } catch (Exception e) {
            return -1;
        }
    }

    static MkvIndex.RangeReader reader(Context context, Uri uri) {
        return (start, length, minUseful) -> {
            try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
                if (pfd == null) throw new IOException("Cannot open " + uri);
                try (FileInputStream in = new FileInputStream(pfd.getFileDescriptor())) {
                    FileChannel ch = in.getChannel();
                    ByteBuffer buf = ByteBuffer.allocate(length);
                    long pos = start;
                    while (buf.hasRemaining()) {
                        int n = ch.read(buf, pos);
                        if (n <= 0) break;
                        pos += n;
                    }
                    if (buf.position() < minUseful) throw new IOException("Short read: " + buf.position());
                    byte[] out = new byte[buf.position()];
                    buf.flip();
                    buf.get(out);
                    return out;
                }
            }
        };
    }
}
