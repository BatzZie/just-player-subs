package com.brouken.player.subs;

import android.util.Log;

import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decoder choices on top of Media3's defaults.
 *
 * FLAC: Android's own software FLAC decoder made Media3 stop with an IllegalArgumentException in
 * DefaultAudioSink.handleBuffer, every time at the same point of a 5.1 FLAC anime episode (Shield, Android 11).
 * FLAC then goes to the FFmpeg extension, which decodes it as well. Only when extensions are allowed.
 */
public final class AudioDecoders {
    private static boolean logged;

    private AudioDecoders() {
    }

    public static MediaCodecSelector selector(int extensionRendererMode) {
        if (extensionRendererMode == DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF) return MediaCodecSelector.DEFAULT;
        return (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> {
            List<MediaCodecInfo> infos = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
            if (!MimeTypes.AUDIO_FLAC.equals(mimeType)) return infos;
            List<MediaCodecInfo> kept = new ArrayList<>();
            for (MediaCodecInfo info : infos) {
                String name = info.name.toLowerCase(Locale.ROOT);
                if (name.startsWith("omx.google.") || name.startsWith("c2.android.")) continue;
                kept.add(info);
            }
            if (kept.size() != infos.size() && !logged) {
                logged = true;
                Log.i(AutoSubs.TAG, "FLAC: using the FFmpeg decoder instead of Android's");
            }
            return kept;
        };
    }
}
