package com.xlythe.view.camera.stream;

import android.os.Build;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;

import java.io.ByteArrayOutputStream;

/**
 * What a receiving device can play, as a few bytes the app carries to the device sending to it.
 *
 * <p>The library does not own the link between the two devices, so it cannot ask. What it can rely
 * on is that something already tells the sender to start, because a stream is not sent to nobody:
 * a message, a request, a channel being opened. These bytes ride on that, and the sender reads
 * them before it chooses how to encode. That adds no round trip and no channel, and it is
 * backward compatible both ways: an old receiver sends nothing and is sent PCM exactly as before,
 * and an old sender never looks at them.</p>
 *
 * <p>Saying anything at all also tells the sender the receiver reads the header that names the
 * format, which is what makes it safe to send anything but PCM.</p>
 *
 * <pre>
 * 'X' 'C' 'A' 'P'   magic
 * version           1
 * count             how many formats follow
 * codec[count]      {@link AudioCodec.Codec} values, in no particular order
 * </pre>
 *
 * <p>A later version may append fields after the list; this one ignores them, and formats it does
 * not know.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class PlaybackCapabilities {
    /** Tells capabilities apart from anything else an app might have put in the same message. */
    private static final byte[] MAGIC = {'X', 'C', 'A', 'P'};

    /** The layout above. */
    private static final int VERSION = 1;

    /** Magic, version and count, before the list. */
    private static final int PREFIX_LENGTH = MAGIC.length + 2;

    private PlaybackCapabilities() {}

    /** What this device plays. */
    @NonNull
    @AnyThread
    public static byte[] forThisDevice() {
        return forDevice(Build.VERSION.SDK_INT, AudioCodec.DEVICE);
    }

    /** What a device at this release, with these codecs, plays. */
    @NonNull
    static byte[] forDevice(int sdk, @NonNull AudioCodec.Query query) {
        int[] codecs = new int[AudioCodec.PREFERENCE.length];
        int count = 0;
        for (int codec : AudioCodec.PREFERENCE) {
            if (AudioCodec.canDecode(codec, sdk, query)) {
                codecs[count++] = codec;
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(PREFIX_LENGTH + count);
        out.write(MAGIC, 0, MAGIC.length);
        out.write(VERSION);
        out.write(count);
        for (int i = 0; i < count; i++) {
            out.write(codecs[i]);
        }
        return out.toByteArray();
    }

    /**
     * The formats a receiver listed, or null when the bytes are not capabilities at all.
     *
     * <p>Null and empty mean different things. Null is a receiver that said nothing, which is an
     * old one, and gets PCM with the header it has always read. A list is a receiver that reads
     * the newer header, whatever is on it.</p>
     */
    @Nullable
    static int[] parse(@Nullable byte[] capabilities) {
        if (capabilities == null || capabilities.length < PREFIX_LENGTH) {
            return null;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (capabilities[i] != MAGIC[i]) {
                return null;
            }
        }
        int version = capabilities[MAGIC.length] & 0xFF;
        if (version < VERSION) {
            return null;
        }
        int count = capabilities[MAGIC.length + 1] & 0xFF;
        if (capabilities.length < PREFIX_LENGTH + count) {
            return null;
        }
        int[] known = new int[count];
        int kept = 0;
        for (int i = 0; i < count; i++) {
            int codec = capabilities[PREFIX_LENGTH + i] & 0xFF;
            if (AudioCodec.isKnown(codec)) {
                known[kept++] = codec;
            }
        }
        int[] codecs = new int[kept];
        System.arraycopy(known, 0, codecs, 0, kept);
        return codecs;
    }
}
