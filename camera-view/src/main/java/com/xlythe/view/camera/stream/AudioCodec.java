package com.xlythe.view.camera.stream;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;

import androidx.annotation.AnyThread;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * The formats a stream's audio can travel in, what each costs, and which one two devices share.
 *
 * <p>The two ends of a stream are often different devices on different releases, so neither can
 * assume the other decodes what it encodes. Opus is the best of the three for speech and has the
 * widest bit rate range, but the platform only encodes it from API 29, while decoding it goes back
 * to 21. AAC is everywhere MediaCodec is. PCM needs nothing at all, which is why it is what a
 * stream falls back to and what a receiver that never said what it plays is sent.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class AudioCodec {
    /** One of the formats below. */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({PCM, AAC, OPUS})
    public @interface Codec {}

    /** Uncompressed 16-bit PCM, which every version of the library plays. */
    public static final int PCM = 0;

    /** AAC-LC, one raw access unit per frame. */
    public static final int AAC = 1;

    /** Opus, one packet per frame. */
    public static final int OPUS = 2;

    /** Best first: the order a sender tries them in. */
    static final int[] PREFERENCE = {OPUS, AAC, PCM};

    /** The first release with MediaCodec, and so with any compressed format. */
    static final int MEDIA_CODEC_SDK = 16;

    /** The first release whose platform decodes Opus. */
    static final int OPUS_DECODE_SDK = 21;

    /** The first release whose platform encodes Opus. */
    static final int OPUS_ENCODE_SDK = 29;

    /** The rates Opus is defined at. A recorder that settled on another sends AAC instead. */
    private static final int[] OPUS_SAMPLE_RATES = {8000, 12000, 16000, 24000, 48000};

    /** The sampling frequency table AAC's AudioSpecificConfig indexes into. */
    private static final int[] AAC_SAMPLE_RATES = {
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350
    };

    /** AAC-LC's audio object type. */
    private static final int AAC_OBJECT_TYPE_LC = 2;

    /**
     * Samples the Opus decoder discards from the start, at 48kHz. The encoder's lookahead, which is
     * what the platform encoder reports; it only trims the first few milliseconds of a stream.
     */
    static final int OPUS_PRE_SKIP_SAMPLES = 312;

    /** How far before a seek point Opus has to start decoding to converge. The RFC 7845 value. */
    private static final long OPUS_SEEK_PRE_ROLL_NANOS = 80_000_000L;

    /** Opus always decodes at this rate, whatever it was encoded at. */
    static final int OPUS_DECODE_RATE = 48000;

    /**
     * The largest compressed frame the two-byte length on the wire can carry. Far above any
     * Opus packet (1275 bytes) or AAC access unit (768 bytes a channel).
     */
    static final int MAX_FRAME_BYTES = 0xFFFF;

    /** Whether a device has a codec for a format, which tests answer without a device. */
    interface Query {
        /** Whether the device has an encoder, or a decoder, for this MIME type. */
        boolean has(@NonNull String mimeType, boolean encoder);
    }

    /** Asks the platform's codec list, which is what a running stream goes by. */
    static final Query DEVICE = AudioCodec::deviceHas;

    private AudioCodec() {}

    /** The MIME type MediaCodec knows the format by, or null for PCM, which needs no codec. */
    @Nullable
    static String mimeTypeOf(@Codec int codec) {
        switch (codec) {
            case AAC:
                return MediaFormat.MIMETYPE_AUDIO_AAC;
            case OPUS:
                return MediaFormat.MIMETYPE_AUDIO_OPUS;
            default:
                return null;
        }
    }

    /** Whether a byte off the wire names a format this version knows. */
    static boolean isKnown(int codec) {
        return codec == PCM || codec == AAC || codec == OPUS;
    }

    /** Whether a device at this release, with these codecs, can play the format. */
    @AnyThread
    static boolean canDecode(@Codec int codec, int sdk, @NonNull Query query) {
        switch (codec) {
            case PCM:
                return true;
            case AAC:
                return sdk >= MEDIA_CODEC_SDK && query.has(MediaFormat.MIMETYPE_AUDIO_AAC, false);
            case OPUS:
                return sdk >= OPUS_DECODE_SDK && query.has(MediaFormat.MIMETYPE_AUDIO_OPUS, false);
            default:
                return false;
        }
    }

    /** Whether a device at this release, with these codecs, can produce the format at a rate. */
    @AnyThread
    static boolean canEncode(@Codec int codec, int sampleRate, int channelCount, int sdk,
            @NonNull Query query) {
        if (channelCount < 1 || channelCount > 2) {
            return codec == PCM;
        }
        switch (codec) {
            case PCM:
                return true;
            case AAC:
                return sdk >= MEDIA_CODEC_SDK
                        && indexOf(AAC_SAMPLE_RATES, sampleRate) >= 0
                        && query.has(MediaFormat.MIMETYPE_AUDIO_AAC, true);
            case OPUS:
                return sdk >= OPUS_ENCODE_SDK
                        && indexOf(OPUS_SAMPLE_RATES, sampleRate) >= 0
                        && query.has(MediaFormat.MIMETYPE_AUDIO_OPUS, true);
            default:
                return false;
        }
    }

    /**
     * The format to send, given what the receiver said it plays.
     *
     * @param receiver the formats from the receiver's capabilities, or null when it said nothing.
     *     A receiver that said nothing is a version from before compression, so it gets PCM.
     */
    @Codec
    @AnyThread
    static int choose(@Nullable int[] receiver, int sampleRate, int channelCount, int sdk,
            @NonNull Query query) {
        if (receiver == null) {
            return PCM;
        }
        for (int codec : PREFERENCE) {
            if (indexOf(receiver, codec) >= 0
                    && canEncode(codec, sampleRate, channelCount, sdk, query)) {
                return codec;
            }
        }
        return PCM;
    }

    /**
     * The bit rate a compressed stream starts at, and the most adaptation raises it back to.
     *
     * <p>Opus is transparent for wideband speech at 24kbps, and AAC-LC needs about a third more
     * for the same; both scale with the bandwidth the rate can carry. PCM is what it is.</p>
     */
    static int defaultBitRate(@Codec int codec, int sampleRate, int channelCount) {
        switch (codec) {
            case OPUS:
                if (sampleRate <= 16000) {
                    return 24_000 * channelCount;
                }
                return (sampleRate <= 24000 ? 32_000 : 64_000) * channelCount;
            case AAC:
                if (sampleRate <= 16000) {
                    return 32_000 * channelCount;
                }
                return (sampleRate <= 24000 ? 48_000 : 64_000) * channelCount;
            default:
                return sampleRate * channelCount * 16;
        }
    }

    /**
     * The least a congested stream lowers its audio to.
     *
     * <p>Opus at 8kbps a channel is still intelligible narrowband speech. AAC-LC breaks down sooner,
     * so it stops at twice that. PCM cannot be lowered without changing its rate, which the header
     * that went out first already fixed.</p>
     */
    static int minimumBitRate(@Codec int codec, int sampleRate, int channelCount) {
        switch (codec) {
            case OPUS:
                return 8_000 * channelCount;
            case AAC:
                return 16_000 * channelCount;
            default:
                return defaultBitRate(PCM, sampleRate, channelCount);
        }
    }

    /**
     * What the decoder needs to be told before the first frame, built from the stream's shape.
     *
     * <p>The encoder produces this too, and a sender passes that on when it has it. This is for an
     * encoder that does not, so a receiver is never left without one.</p>
     */
    @NonNull
    static byte[] defaultConfig(@Codec int codec, int sampleRate, int channelCount) {
        switch (codec) {
            case AAC:
                return aacConfig(sampleRate, channelCount);
            case OPUS:
                return opusHead(sampleRate, channelCount, OPUS_PRE_SKIP_SAMPLES);
            default:
                return new byte[0];
        }
    }

    /**
     * Two bytes of AudioSpecificConfig: object type, frequency index, channel configuration.
     *
     * <p>ISO 14496-3 1.6.2.1. A rate outside the table cannot be described in two bytes, so it is
     * written as the nearest index; the recorder never settles on one.</p>
     */
    @NonNull
    static byte[] aacConfig(int sampleRate, int channelCount) {
        int index = indexOf(AAC_SAMPLE_RATES, sampleRate);
        if (index < 0) {
            index = nearestIndex(AAC_SAMPLE_RATES, sampleRate);
        }
        return new byte[] {
                (byte) ((AAC_OBJECT_TYPE_LC << 3) | (index >> 1)),
                (byte) (((index & 1) << 7) | (channelCount << 3))
        };
    }

    /** An Opus identification header, RFC 7845 section 5.1, for channel mapping family 0. */
    @NonNull
    static byte[] opusHead(int sampleRate, int channelCount, int preSkip) {
        ByteBuffer head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        head.put(new byte[] {'O', 'p', 'u', 's', 'H', 'e', 'a', 'd'});
        head.put((byte) 1);
        head.put((byte) channelCount);
        head.putShort((short) preSkip);
        head.putInt(sampleRate);
        head.putShort((short) 0);
        head.put((byte) 0);
        return head.array();
    }

    /**
     * The configuration to send for what an encoder produced, or null to send the default.
     *
     * <p>AAC's is sent as it is. The platform's Opus encoder wraps its identification header in
     * tagged sections that only API 29 decoders unpack, so the header is taken out of them: a
     * receiver on an older release decodes Opus too, and it expects the bare header.</p>
     */
    @Nullable
    static byte[] normalizeConfig(@Codec int codec, @Nullable byte[] encoderConfig) {
        if (encoderConfig == null || encoderConfig.length == 0) {
            return null;
        }
        if (codec != OPUS) {
            return encoderConfig;
        }
        if (startsWith(encoderConfig, 0, "OpusHead")) {
            return encoderConfig;
        }
        // AOPUSHDR, an 8 byte little-endian length, then the header itself.
        if (startsWith(encoderConfig, 0, "AOPUSHDR") && encoderConfig.length >= 16) {
            long length = ByteBuffer.wrap(encoderConfig, 8, 8)
                    .order(ByteOrder.LITTLE_ENDIAN).getLong();
            if (length >= 19 && length <= encoderConfig.length - 16
                    && startsWith(encoderConfig, 16, "OpusHead")) {
                return Arrays.copyOfRange(encoderConfig, 16, 16 + (int) length);
            }
        }
        return null;
    }

    /**
     * The codec-specific data a decoder is configured with, as MediaFormat's csd-N buffers.
     *
     * <p>Opus takes three: the identification header, then its pre-skip and seek pre-roll in
     * nanoseconds as native-order longs. AAC takes its AudioSpecificConfig alone.</p>
     */
    @NonNull
    static ByteBuffer[] decoderConfig(@Codec int codec, @NonNull byte[] config) {
        if (codec != OPUS) {
            return new ByteBuffer[] {ByteBuffer.wrap(config)};
        }
        int preSkip = config.length >= 12
                ? (config[10] & 0xFF) | ((config[11] & 0xFF) << 8)
                : OPUS_PRE_SKIP_SAMPLES;
        long preSkipNanos = preSkip * 1_000_000_000L / OPUS_DECODE_RATE;
        return new ByteBuffer[] {
                ByteBuffer.wrap(config),
                ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0, preSkipNanos),
                ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
                        .putLong(0, OPUS_SEEK_PRE_ROLL_NANOS)
        };
    }

    /**
     * A compressed frame as it goes on the wire: a two-byte big-endian length, then the frame.
     *
     * <p>One array, so a lossy pipe drops the length and the frame together or not at all.</p>
     */
    @NonNull
    static byte[] frame(@NonNull byte[] payload) {
        if (payload.length > MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("Frame too long: " + payload.length);
        }
        byte[] framed = new byte[2 + payload.length];
        framed[0] = (byte) (payload.length >> 8);
        framed[1] = (byte) payload.length;
        System.arraycopy(payload, 0, framed, 2, payload.length);
        return framed;
    }

    /**
     * Reads the next compressed frame off the wire.
     *
     * @return the frame, or null at the end of the stream, including one that ends mid-frame
     */
    @Nullable
    static byte[] readFrame(@NonNull InputStream in) throws IOException {
        int high = in.read();
        int low = high < 0 ? -1 : in.read();
        if (low < 0) {
            return null;
        }
        byte[] payload = new byte[(high << 8) | low];
        int read = 0;
        while (read < payload.length) {
            int count = in.read(payload, read, payload.length - read);
            if (count < 0) {
                return null;
            }
            read += count;
        }
        return payload;
    }

    private static boolean startsWith(byte[] bytes, int offset, String prefix) {
        if (bytes.length - offset < prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (bytes[offset + i] != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private static int nearestIndex(int[] values, int value) {
        int best = 0;
        for (int i = 1; i < values.length; i++) {
            if (Math.abs(values[i] - value) < Math.abs(values[best] - value)) {
                best = i;
            }
        }
        return best;
    }

    /** Whether the platform lists a codec for the type. Below MediaCodec, there is none. */
    private static boolean deviceHas(@NonNull String mimeType, boolean encoder) {
        if (Build.VERSION.SDK_INT < MEDIA_CODEC_SDK) {
            return false;
        }
        try {
            int count = MediaCodecList.getCodecCount();
            for (int i = 0; i < count; i++) {
                MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
                if (info.isEncoder() != encoder) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    if (type.equalsIgnoreCase(mimeType)) {
                        return true;
                    }
                }
            }
        } catch (RuntimeException e) {
            // A media server that has died answers every query with an exception. Nothing can be
            // encoded or decoded then either, so the honest answer is no.
            return false;
        }
        return false;
    }
}
