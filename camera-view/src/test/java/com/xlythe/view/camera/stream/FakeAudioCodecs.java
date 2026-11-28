package com.xlythe.view.camera.stream;

import android.media.MediaFormat;

import org.robolectric.shadows.MediaCodecInfoBuilder;
import org.robolectric.shadows.ShadowMediaCodec;
import org.robolectric.shadows.ShadowMediaCodecList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Stand-ins for the platform's audio codecs, registered where both the codec list and MediaCodec
 * look for them.
 *
 * <p>The "compression" is reversible scrambling rather than a real format, so audio that reaches
 * the speaker intact proves it went through the encoder and back out of the decoder, rather than
 * past them: unscrambled PCM played as if it were decoded would not match, and neither would
 * scrambled frames played as PCM.</p>
 */
final class FakeAudioCodecs {
    /** What every byte is scrambled with. */
    static final byte KEY = 0x5A;

    /** Bytes a codec buffer holds; larger than any chunk the tests feed. */
    private static final int BUFFER_BYTES = 64 * 1024;

    private FakeAudioCodecs() {}

    /** Forgets every codec, real or fake. */
    static void reset() {
        ShadowMediaCodec.clearCodecs();
        ShadowMediaCodecList.reset();
    }

    /** Gives the device an encoder for a type. */
    static void addEncoder(String mimeType) {
        register(mimeType, true);
        ShadowMediaCodec.addEncoder(mimeType, new ShadowMediaCodec.CodecConfig(
                BUFFER_BYTES, BUFFER_BYTES, FakeAudioCodecs::scramble));
    }

    /** Gives the device a decoder for a type. */
    static void addDecoder(String mimeType) {
        register(mimeType, false);
        ShadowMediaCodec.addDecoder(mimeType, new ShadowMediaCodec.CodecConfig(
                BUFFER_BYTES, BUFFER_BYTES, FakeAudioCodecs::scramble));
    }

    /** Both directions of every compressed format, as a current release has. */
    static void addAll() {
        addEncoder(MediaFormat.MIMETYPE_AUDIO_OPUS);
        addDecoder(MediaFormat.MIMETYPE_AUDIO_OPUS);
        addEncoder(MediaFormat.MIMETYPE_AUDIO_AAC);
        addDecoder(MediaFormat.MIMETYPE_AUDIO_AAC);
    }

    private static void register(String mimeType, boolean encoder) {
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder()
                .setName("fake." + mimeType + (encoder ? ".encoder" : ".decoder"))
                .setIsEncoder(encoder)
                .setCapabilities(MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                        .setMediaFormat(MediaFormat.createAudioFormat(mimeType, 48000, 2))
                        .setIsEncoder(encoder)
                        .build())
                .build());
    }

    private static void scramble(ByteBuffer in, ByteBuffer out) {
        while (in.hasRemaining()) {
            out.put((byte) (in.get() ^ KEY));
        }
    }

    /** Undoes the scrambling, for a test reading frames off the wire itself. */
    static byte[] unscramble(byte[] frame) {
        byte[] plain = new byte[frame.length];
        for (int i = 0; i < frame.length; i++) {
            plain[i] = (byte) (frame[i] ^ KEY);
        }
        return plain;
    }

    /** The frames of a compressed stream after its header, unscrambled and joined. */
    static byte[] payloadOf(byte[] framesAfterHeader) throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(framesAfterHeader);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] frame;
        while ((frame = AudioCodec.readFrame(in)) != null) {
            out.write(unscramble(frame));
        }
        return out.toByteArray();
    }
}
