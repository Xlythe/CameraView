package com.xlythe.view.camera.stream;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;

/**
 * What the recorder tells the player about the audio that follows.
 *
 * <p>Without it both ends have to guess, and they guess separately: the recorder takes the lowest
 * rate {@code AudioRecord} will give it and the player the lowest {@code AudioTrack} will, which
 * are different questions asked of what may be two different devices. When the answers differ the
 * audio plays at the wrong speed and pitch - a phone streaming to a watch is exactly the case
 * where they are most likely to.</p>
 *
 * <p>A stream with no header is read as raw PCM at the player's own guess, which is what a
 * recorder from before this existed produces.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class AudioStreamHeader {
    /** 'X', 'A', 'U', 'D'. Chosen to be something PCM audio is very unlikely to start with. */
    private static final int MAGIC = 0x58415544;

    private static final int VERSION = 1;

    /** Magic, version, sample rate, channel count, encoding. */
    static final int LENGTH = 4 + 1 + 4 + 1 + 1;

    private final int mSampleRate;
    private final int mChannelCount;
    private final int mEncoding;

    AudioStreamHeader(int sampleRate, int channelCount, int encoding) {
        mSampleRate = sampleRate;
        mChannelCount = channelCount;
        mEncoding = encoding;
    }

    public int getSampleRate() {
        return mSampleRate;
    }

    public int getChannelCount() {
        return mChannelCount;
    }

    /** One of the {@code AudioFormat.ENCODING_*} constants. */
    public int getEncoding() {
        return mEncoding;
    }

    void writeTo(@NonNull OutputStream outputStream) throws IOException {
        DataOutputStream out = new DataOutputStream(outputStream);
        out.writeInt(MAGIC);
        out.writeByte(VERSION);
        out.writeInt(mSampleRate);
        out.writeByte(mChannelCount);
        out.writeByte(mEncoding);
        out.flush();
    }

    /**
     * Reads the header, if the stream has one.
     *
     * <p>The bytes are consumed either way, so the caller is given back a stream to keep reading:
     * when there was no header those bytes are audio and are put back in front of it.</p>
     */
    @NonNull
    static Read read(@NonNull InputStream inputStream) throws IOException {
        byte[] start = new byte[LENGTH];
        int read = 0;
        while (read < start.length) {
            int count = inputStream.read(start, read, start.length - read);
            if (count < 0) {
                break;
            }
            read += count;
        }
        if (read < start.length) {
            // Too short to be a header, so it is the whole of a very short stream.
            return new Read(null, prepend(start, read, inputStream));
        }

        DataInputStream header = new DataInputStream(
                new java.io.ByteArrayInputStream(start));
        try {
            if (header.readInt() != MAGIC || header.readByte() != VERSION) {
                return new Read(null, prepend(start, read, inputStream));
            }
            int sampleRate = header.readInt();
            int channelCount = header.readByte();
            int encoding = header.readByte();
            if (sampleRate <= 0 || channelCount <= 0) {
                return new Read(null, prepend(start, read, inputStream));
            }
            return new Read(
                    new AudioStreamHeader(sampleRate, channelCount, encoding), inputStream);
        } catch (EOFException error) {
            return new Read(null, prepend(start, read, inputStream));
        }
    }

    private static InputStream prepend(byte[] start, int length, InputStream rest) {
        ByteArrayOutputStream head = new ByteArrayOutputStream(length);
        head.write(start, 0, length);
        return new SequenceInputStream(
                new java.io.ByteArrayInputStream(head.toByteArray()), rest);
    }

    @NonNull
    @Override
    public String toString() {
        return "AudioStreamHeader{sampleRate=" + mSampleRate
                + ", channels=" + mChannelCount + ", encoding=" + mEncoding + "}";
    }

    /** A header, when the stream had one, and the stream to keep reading audio from. */
    static final class Read {
        @Nullable final AudioStreamHeader header;
        @NonNull final InputStream stream;

        Read(@Nullable AudioStreamHeader header, @NonNull InputStream stream) {
            this.header = header;
            this.stream = stream;
        }
    }
}
