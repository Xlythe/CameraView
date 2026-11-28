package com.xlythe.view.camera.stream;

import android.media.AudioFormat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
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
 * <p>There are two layouts. PCM is described by version 1, byte for byte what every earlier
 * release writes and reads:</p>
 *
 * <pre>
 * magic(4) version=1 sampleRate(4) channelCount(1) encoding(1)
 * </pre>
 *
 * <p>Compressed audio is described by version 2, which adds the format and what its decoder has to
 * be told first. A version 1 reader turns this layout down as a header, rather than reading a rate
 * out of the wrong bytes, but then plays the stream as PCM, which is noise. So version 2 is only
 * ever written to a receiver that said it reads it, through {@link PlaybackCapabilities}; anyone
 * else is sent PCM under version 1.</p>
 *
 * <pre>
 * magic(4) version=2 length(2) sampleRate(4) channelCount(1) codec(1) configLength(1) config(n)
 * </pre>
 *
 * <p>{@code length} counts the bytes after it, so a later version can append fields that this one
 * skips. A stream with no header at all is read as raw PCM at the player's own guess, which is
 * what a recorder from before either existed produces.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class AudioStreamHeader {
    /** 'X', 'A', 'U', 'D'. Chosen to be something PCM audio is very unlikely to start with. */
    private static final int MAGIC = 0x58415544;

    /** PCM, in the layout every release reads. */
    private static final int VERSION_PCM = 1;

    /** Compressed audio, in the layout only a receiver that asked for it reads. */
    private static final int VERSION_COMPRESSED = 2;

    /** Magic, version, sample rate, channel count, encoding: all of version 1. */
    static final int LENGTH = 4 + 1 + 4 + 1 + 1;

    /** Magic, version and length: what version 2 has before the bytes its length counts. */
    private static final int COMPRESSED_PREFIX_LENGTH = 4 + 1 + 2;

    /** Version 2's fields after its length: rate, channels, codec and the config's length. */
    private static final int COMPRESSED_FIXED_LENGTH = 4 + 1 + 1 + 1;

    /**
     * The most version 2 may say about itself. Far more than any field needs, and small enough
     * that bytes which only happen to start like a header cannot make the reader allocate much.
     */
    private static final int MAX_COMPRESSED_LENGTH = 1024;

    /** Samples a second. */
    private final int mSampleRate;

    /** Interleaved channels. */
    private final int mChannelCount;

    /** One of the {@code AudioFormat.ENCODING_*} constants. */
    private final int mEncoding;

    /** The format the frames that follow are in. */
    @AudioCodec.Codec private final int mCodec;

    /** What the decoder is configured with, empty for PCM. */
    @NonNull private final byte[] mConfig;

    /** Describes PCM, which is all a version 1 header can. */
    AudioStreamHeader(int sampleRate, int channelCount, int encoding) {
        mSampleRate = sampleRate;
        mChannelCount = channelCount;
        mEncoding = encoding;
        mCodec = AudioCodec.PCM;
        mConfig = new byte[0];
    }

    /** Describes audio in any format; PCM is still written in the version 1 layout. */
    AudioStreamHeader(int sampleRate, int channelCount, @AudioCodec.Codec int codec,
            @NonNull byte[] config) {
        if (config.length > 0xFF) {
            throw new IllegalArgumentException("Codec config too long: " + config.length);
        }
        mSampleRate = sampleRate;
        mChannelCount = channelCount;
        mCodec = codec;
        mConfig = config.clone();
        mEncoding = encodingOf(codec);
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

    /** The format the frames after the header are in. */
    @AudioCodec.Codec
    public int getCodec() {
        return mCodec;
    }

    /** What the decoder has to be given before the first frame. Empty for PCM. */
    @NonNull
    public byte[] getConfig() {
        return mConfig.clone();
    }

    /** The bytes this header goes out as. */
    @NonNull
    byte[] toByteArray() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(LENGTH + mConfig.length + 3);
        try {
            writeTo(bytes);
        } catch (IOException e) {
            throw new IllegalStateException("A byte array cannot fail to be written", e);
        }
        return bytes.toByteArray();
    }

    void writeTo(@NonNull OutputStream outputStream) throws IOException {
        DataOutputStream out = new DataOutputStream(outputStream);
        out.writeInt(MAGIC);
        if (mCodec == AudioCodec.PCM) {
            out.writeByte(VERSION_PCM);
            out.writeInt(mSampleRate);
            out.writeByte(mChannelCount);
            out.writeByte(mEncoding);
        } else {
            out.writeByte(VERSION_COMPRESSED);
            out.writeShort(COMPRESSED_FIXED_LENGTH + mConfig.length);
            out.writeInt(mSampleRate);
            out.writeByte(mChannelCount);
            out.writeByte(mCodec);
            out.writeByte(mConfig.length);
            out.write(mConfig);
        }
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
        int read = readFully(inputStream, start, 0, start.length);
        if (read < start.length) {
            // Too short to be a header, so it is the whole of a very short stream.
            return new Read(null, prepend(start, read, inputStream));
        }

        DataInputStream header = new DataInputStream(new ByteArrayInputStream(start));
        if (header.readInt() != MAGIC) {
            return new Read(null, prepend(start, read, inputStream));
        }
        int version = header.readUnsignedByte();
        if (version == VERSION_PCM) {
            int sampleRate = header.readInt();
            int channelCount = header.readByte();
            int encoding = header.readByte();
            if (sampleRate <= 0 || channelCount <= 0) {
                return new Read(null, prepend(start, read, inputStream));
            }
            return new Read(
                    new AudioStreamHeader(sampleRate, channelCount, encoding), inputStream);
        }
        if (version < VERSION_COMPRESSED) {
            return new Read(null, prepend(start, read, inputStream));
        }
        return readCompressed(start, header.readUnsignedShort(), inputStream);
    }

    /**
     * The rest of a version 2 header, or later. The first {@link #LENGTH} bytes have been read.
     *
     * <p>A version this reader does not know still starts the way version 2 does, so it is read
     * the same way and whatever it appended is skipped.</p>
     */
    @NonNull
    private static Read readCompressed(byte[] start, int length, InputStream inputStream)
            throws IOException {
        if (length < COMPRESSED_FIXED_LENGTH || length > MAX_COMPRESSED_LENGTH) {
            return new Read(null, prepend(start, start.length, inputStream));
        }
        int total = COMPRESSED_PREFIX_LENGTH + length;
        byte[] whole = new byte[total];
        System.arraycopy(start, 0, whole, 0, start.length);
        int read = start.length
                + readFully(inputStream, whole, start.length, total - start.length);
        if (read < total) {
            return new Read(null, prepend(whole, read, inputStream));
        }

        DataInputStream fields = new DataInputStream(
                new ByteArrayInputStream(whole, COMPRESSED_PREFIX_LENGTH, length));
        int sampleRate = fields.readInt();
        int channelCount = fields.readUnsignedByte();
        int codec = fields.readUnsignedByte();
        int configLength = fields.readUnsignedByte();
        if (sampleRate <= 0 || channelCount <= 0 || !AudioCodec.isKnown(codec)
                || configLength > length - COMPRESSED_FIXED_LENGTH) {
            return new Read(null, prepend(whole, read, inputStream));
        }
        byte[] config = new byte[configLength];
        fields.readFully(config);
        return new Read(
                new AudioStreamHeader(sampleRate, channelCount, codec, config), inputStream);
    }

    /** Reads until the range is full or the stream ends, and returns how much it read. */
    private static int readFully(InputStream inputStream, byte[] into, int offset, int length)
            throws IOException {
        int read = 0;
        while (read < length) {
            int count = inputStream.read(into, offset + read, length - read);
            if (count < 0) {
                break;
            }
            read += count;
        }
        return read;
    }

    private static InputStream prepend(byte[] start, int length, InputStream rest) {
        return new SequenceInputStream(new ByteArrayInputStream(start, 0, length), rest);
    }

    /** The {@code AudioFormat} constant for a format. Only PCM's layout puts it on the wire. */
    private static int encodingOf(@AudioCodec.Codec int codec) {
        switch (codec) {
            case AudioCodec.AAC:
                return AudioFormat.ENCODING_AAC_LC;
            case AudioCodec.OPUS:
                return AudioFormat.ENCODING_OPUS;
            default:
                return AudioFormat.ENCODING_PCM_16BIT;
        }
    }

    @NonNull
    @Override
    public String toString() {
        return "AudioStreamHeader{sampleRate=" + mSampleRate
                + ", channels=" + mChannelCount + ", codec=" + mCodec
                + ", encoding=" + mEncoding + "}";
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
