package com.xlythe.view.camera.stream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * The audio stream format exactly as the last release before compression wrote and read it.
 *
 * <p>Copied rather than referenced, because the point is to hold the current code to what devices
 * already in the field do: a phone that has not updated is the receiver, or the sender, on the
 * other end of a stream from one that has.</p>
 */
final class ReleasedAudioFormat {
    /** 'X', 'A', 'U', 'D'. */
    private static final int MAGIC = 0x58415544;

    /** The only version that release knew. */
    private static final int VERSION = 1;

    /** Magic, version, sample rate, channel count, encoding. */
    private static final int LENGTH = 4 + 1 + 4 + 1 + 1;

    /** AudioFormat.ENCODING_PCM_16BIT. */
    private static final int ENCODING_PCM_16BIT = 2;

    private ReleasedAudioFormat() {}

    /** What that release's recorder sent: its header, then raw PCM. */
    static byte[] send(int sampleRate, int channelCount, byte[] pcm) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeByte(VERSION);
        out.writeInt(sampleRate);
        out.writeByte(channelCount);
        out.writeByte(ENCODING_PCM_16BIT);
        out.write(pcm);
        return bytes.toByteArray();
    }

    /** What that release's player did with a stream. */
    static final class Received {
        /** The rate it played at, or -1 when it found no header and guessed. */
        final int sampleRate;

        /** The channels it played, or -1 when it guessed. */
        final int channelCount;

        /** Every byte it handed to the speaker as PCM. */
        final byte[] played;

        Received(int sampleRate, int channelCount, byte[] played) {
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
            this.played = played;
        }
    }

    /** Plays a stream the way that release did: a version 1 header if there is one, then PCM. */
    static Received receive(byte[] stream) throws IOException {
        if (stream.length >= LENGTH) {
            DataInputStream header = new DataInputStream(
                    new ByteArrayInputStream(stream, 0, LENGTH));
            if (header.readInt() == MAGIC && header.readByte() == VERSION) {
                int sampleRate = header.readInt();
                int channelCount = header.readByte();
                header.readByte();
                if (sampleRate > 0 && channelCount > 0) {
                    return new Received(sampleRate, channelCount,
                            Arrays.copyOfRange(stream, LENGTH, stream.length));
                }
            }
        }
        return new Received(-1, -1, stream.clone());
    }
}
