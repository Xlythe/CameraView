package com.xlythe.view.camera;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * A JPEG: Exif in its APP1 segment, XMP in another, and whatever follows the picture, such as a
 * gain map or a motion photo's clip.
 *
 * <p>A changed packet goes in its own APP1 segment straight after the Exif one, ahead of the
 * MPF segment that locates a gain map. MPF counts from its own position, so anything that grows
 * ahead of it moves the gain map with it, and nothing after it moves at all.</p>
 */
final class JpegContainer extends ExifImageContainer {
    private static final String TAG = "MediaTags";

    static final int SOI = 0xD8;
    static final int EOI = 0xD9;
    static final int SOS = 0xDA;
    static final int APP0 = 0xE0;
    static final int APP1 = 0xE1;
    static final int APP2 = 0xE2;

    /** What an XMP segment starts with, terminator included. */
    static final byte[] XMP_IDENTIFIER =
            "http://ns.adobe.com/xap/1.0/\u0000".getBytes(Bytes.LATIN_1);

    /** What an extended XMP segment starts with, before its GUID, length and offset. */
    static final byte[] EXTENDED_XMP_IDENTIFIER =
            "http://ns.adobe.com/xmp/extension/\u0000".getBytes(Bytes.LATIN_1);

    /** What an MPF segment starts with, before its TIFF header. */
    static final byte[] MPF_IDENTIFIER = {'M', 'P', 'F', 0};

    /** What an ISO 21496-1 gain map segment starts with. */
    static final byte[] ISO_GAIN_MAP_IDENTIFIER =
            "urn:iso:std:iso:ts:21496:-1\u0000".getBytes(Bytes.LATIN_1);

    /** The most an XMP packet can take, in one segment after its identifier. */
    static final int MAX_XMP_BYTES = 0xFFFF - 2 - 29;

    /** How much of a picture inside the file is read to find its metadata. */
    private static final int SECONDARY_HEAD_BYTES = 128 * 1024;

    /** The MPF tag that lists the pictures in the file. */
    private static final int MPF_ENTRY = 0xB002;

    /** A segment's place in the file. */
    static final class Segment {
        /** The marker, without its 0xFF. */
        final int marker;

        /** Where the segment's 0xFF is. */
        final long offset;

        /** How long its payload is, after the two bytes of its length. */
        final int length;

        /** The start of its payload, enough of it to tell what it holds. */
        @NonNull
        final byte[] head;

        Segment(int marker, long offset, int length, @NonNull byte[] head) {
            this.marker = marker;
            this.offset = offset;
            this.length = length;
            this.head = head;
        }

        /** Where the payload starts. */
        long payload() {
            return offset + 4;
        }

        /** One past the end of the segment. */
        long end() {
            return payload() + length;
        }

        boolean is(int marker, @NonNull byte[] identifier) {
            return this.marker == marker && Bytes.startsWith(head, 0, identifier);
        }
    }

    /** The motion photo's clip, as the file was read, or null. */
    @Nullable
    private final MediaTags.MotionPhoto mMotionPhoto;

    /** The gain map, as the file was read. */
    @MediaTags.GainMap
    private final int mGainMap;

    /** The packet, as read or as changed since. */
    @NonNull
    private final Xmp mXmp;

    /** The extended packet, or null. */
    @Nullable
    private final Xmp mExtendedXmp;

    /** Whether the packet has changed since it was read or saved. */
    private boolean mXmpDirty;

    /** Whether the Exif could be opened, without which it is not written either. */
    private final boolean mExifReadable;

    private JpegContainer(@Nullable MediaTags.MotionPhoto motionPhoto, int gainMap,
            @NonNull Xmp xmp, @Nullable Xmp extendedXmp, @Nullable Exif exif, boolean writable) {
        super(writable);
        mMotionPhoto = motionPhoto;
        mGainMap = gainMap;
        mXmp = xmp;
        mExtendedXmp = extendedXmp;
        mExifReadable = exif != null;
        setReadExif(exif);
    }

    /**
     * Reads a JPEG.
     *
     * @param file the file, when it can be written, or null
     */
    @WorkerThread
    @NonNull
    static JpegContainer read(@NonNull ByteSource source, @Nullable File file)
            throws IOException {
        List<Segment> segments = segments(source, 0);
        Xmp xmp = null;
        for (Segment segment : segments) {
            if (xmp == null && segment.is(APP1, XMP_IDENTIFIER)) {
                byte[] payload = source.bytes(segment.payload(), segment.length);
                xmp = Xmp.parse(Arrays.copyOfRange(payload, XMP_IDENTIFIER.length,
                        payload.length));
            }
        }
        if (xmp == null) {
            xmp = Xmp.empty();
        }
        String guid = xmp.get(Xmp.NS_XMP_NOTE, "HasExtendedXMP");
        Xmp extendedXmp = guid == null ? null : readExtended(source, segments, guid.trim());
        Exif exif = null;
        if (file != null || hasExifSegment(segments)) {
            try {
                // From a file even without Exif, so that it can be added.
                exif = file != null ? new Exif(file)
                        : new Exif(new ByteArrayInputStream(head(source, segments)));
            } catch (IOException | RuntimeException unreadable) {
                Log.w(TAG, "Unable to read the Exif of a JPEG", unreadable);
            }
        }
        MediaTags.MotionPhoto motionPhoto = MediaTags.MotionPhoto.describedBy(xmp, source);
        if (motionPhoto == null) {
            motionPhoto = samsungMotionPhoto(source);
        }
        return new JpegContainer(motionPhoto, gainMapOf(source, segments, xmp), xmp,
                extendedXmp, exif, file != null);
    }

    /** The file up to the compressed picture: enough for any reader of its metadata. */
    @NonNull
    private static byte[] head(@NonNull ByteSource source, @NonNull List<Segment> segments)
            throws IOException {
        long end = segments.isEmpty() ? 2 : segments.get(segments.size() - 1).end();
        byte[] head = source.bytes(0, end);
        return Bytes.concat(head, new byte[] {(byte) 0xFF, (byte) EOI});
    }

    private static boolean hasExifSegment(@NonNull List<Segment> segments) {
        for (Segment segment : segments) {
            if (segment.is(APP1, EXIF_IDENTIFIER)) {
                return true;
            }
        }
        return false;
    }

    /** The extended packet with this GUID, put together from its pieces, or null. */
    @Nullable
    private static Xmp readExtended(@NonNull ByteSource source, @NonNull List<Segment> segments,
            @NonNull String guid) throws IOException {
        byte[] assembled = null;
        int filled = 0;
        for (Segment segment : segments) {
            if (!segment.is(APP1, EXTENDED_XMP_IDENTIFIER)) {
                continue;
            }
            byte[] payload = source.bytes(segment.payload(), segment.length);
            int at = EXTENDED_XMP_IDENTIFIER.length;
            if (payload.length < at + 40
                    || !guid.equals(new String(payload, at, 32, Bytes.LATIN_1))) {
                continue;
            }
            long total = Bytes.u32(payload, at + 32);
            long offset = Bytes.u32(payload, at + 36);
            int chunk = payload.length - at - 40;
            if (total > ByteSource.MAX_BUFFERED_STREAM || offset + chunk > total) {
                continue;
            }
            if (assembled == null) {
                assembled = new byte[(int) total];
            } else if (assembled.length != total) {
                continue;
            }
            System.arraycopy(payload, at + 40, assembled, (int) offset, chunk);
            filled += chunk;
        }
        return assembled == null || filled < assembled.length ? null : Xmp.parse(assembled);
    }

    /**
     * The segments of a JPEG from its start of image to its first scan or end of image.
     *
     * @throws IOException if it is not a JPEG
     */
    @WorkerThread
    @NonNull
    static List<Segment> segments(@NonNull ByteSource source, long start) throws IOException {
        byte[] marker = new byte[4];
        if (source.read(start, marker, 0, 2) != 2 || Bytes.u8(marker, 0) != 0xFF
                || Bytes.u8(marker, 1) != SOI) {
            throw new IOException("Not a JPEG");
        }
        List<Segment> segments = new ArrayList<>();
        long at = start + 2;
        while (at + 2 <= source.length()) {
            if (source.read(at, marker, 0, 2) != 2 || Bytes.u8(marker, 0) != 0xFF) {
                break;
            }
            int type = Bytes.u8(marker, 1);
            if (type == 0xFF) {
                // A fill byte before the marker's own.
                at++;
                continue;
            }
            if (type == SOS || type == EOI) {
                break;
            }
            if (type == 0x01 || (type >= 0xD0 && type <= 0xD7)) {
                // Markers with no length.
                at += 2;
                continue;
            }
            if (source.read(at + 2, marker, 2, 2) != 2) {
                break;
            }
            int length = Bytes.u16(marker, 2) - 2;
            if (length < 0 || at + 4 + length > source.length()) {
                break;
            }
            byte[] head = source.bytesUpTo(at + 4, Math.min(length, 64));
            segments.add(new Segment(type, at, length, head));
            at += 4 + length;
        }
        return segments;
    }

    @Override
    int format() {
        return MediaTags.FORMAT_JPEG;
    }

    @Override
    int kind() {
        return MediaTags.KIND_IMAGE;
    }

    @Override
    boolean canWriteExif() {
        return isWritable() && mExifReadable;
    }

    @NonNull
    @Override
    Exif openWritableExif() throws IOException {
        Exif exif = exif();
        // A JPEG read from a file was opened for writing from the start.
        if (exif == null) {
            throw new IOException("The JPEG has no Exif that can be written");
        }
        return exif;
    }

    @NonNull
    @Override
    Xmp xmp() {
        return mXmp;
    }

    @Nullable
    @Override
    Xmp extendedXmp() {
        return mExtendedXmp;
    }

    @Override
    int maxXmpBytes() {
        return isWritable() ? MAX_XMP_BYTES : NO_XMP;
    }

    @Override
    void xmpChanged() {
        super.xmpChanged();
        mXmpDirty = true;
    }

    @Nullable
    @Override
    MediaTags.MotionPhoto motionPhoto() {
        return mMotionPhoto;
    }

    @Override
    int gainMap() {
        return mGainMap;
    }

    /**
     * Which gain map a JPEG carries: one its metadata names, which must also be there, as a
     * picture the MPF segment locates inside the file. The primary picture's metadata may name
     * no gain map, which the pictures after it then say for themselves.
     */
    @MediaTags.GainMap
    private static int gainMapOf(@NonNull ByteSource source, @NonNull List<Segment> segments,
            @NonNull Xmp xmp) {
        List<Long> pictures = secondaryImages(source, segments);
        if (pictures.isEmpty()) {
            // Metadata left behind when the gain map was taken off says nothing.
            return MediaTags.GAIN_MAP_NONE;
        }
        boolean iso = false;
        for (Segment segment : segments) {
            iso |= segment.is(APP2, ISO_GAIN_MAP_IDENTIFIER);
        }
        int kind = gainMapIn(xmp);
        if (kind == MediaTags.GAIN_MAP_ULTRA_HDR) {
            return kind;
        }
        for (long offset : pictures) {
            try {
                for (Segment segment : segments(source, offset)) {
                    if (segment.is(APP2, ISO_GAIN_MAP_IDENTIFIER)) {
                        iso = true;
                    } else if (segment.is(APP1, XMP_IDENTIFIER)) {
                        byte[] payload = source.bytes(segment.payload(), segment.length);
                        int secondary = gainMapIn(Xmp.parse(Arrays.copyOfRange(payload,
                                XMP_IDENTIFIER.length, payload.length)));
                        if (secondary == MediaTags.GAIN_MAP_ULTRA_HDR) {
                            return secondary;
                        }
                        if (kind == MediaTags.GAIN_MAP_NONE) {
                            kind = secondary;
                        }
                    }
                }
            } catch (IOException notAPicture) {
                // An entry that leads nowhere says nothing about a gain map.
            }
        }
        return iso ? MediaTags.GAIN_MAP_ISO_21496_1 : kind;
    }

    /** What a packet says about a gain map: Ultra HDR's, Apple's, or none. */
    @MediaTags.GainMap
    static int gainMapIn(@NonNull Xmp xmp) {
        // Apple's first: since iOS 18 its gain map carries Adobe's metadata too.
        String auxiliary = xmp.get(Xmp.NS_APPLE_PIXEL_DATA, "AuxiliaryImageType");
        if ((auxiliary != null && auxiliary.contains("hdrgainmap"))
                || xmp.get(Xmp.NS_APPLE_GAIN_MAP, "HDRGainMapVersion") != null) {
            return MediaTags.GAIN_MAP_APPLE;
        }
        if (xmp.get(Xmp.NS_HDR_GAIN_MAP, "Version") != null) {
            return MediaTags.GAIN_MAP_ULTRA_HDR;
        }
        for (Map<String, String> item : xmp.getStructs(Xmp.NS_CONTAINER, "Directory")) {
            // Samsung writes "Gainmap".
            if ("GainMap".equalsIgnoreCase(item.get(Xmp.NS_ITEM + "Semantic"))) {
                return MediaTags.GAIN_MAP_ULTRA_HDR;
            }
        }
        return MediaTags.GAIN_MAP_NONE;
    }

    /**
     * Where each picture after the first starts, as the MPF segment lists them, leaving out any
     * that is not a JPEG inside the file.
     */
    @NonNull
    private static List<Long> secondaryImages(@NonNull ByteSource source,
            @NonNull List<Segment> segments) {
        List<Long> offsets = new ArrayList<>();
        for (Segment segment : segments) {
            if (!segment.is(APP2, MPF_IDENTIFIER)) {
                continue;
            }
            try {
                byte[] mpf = source.bytes(segment.payload(), segment.length);
                long tiffStart = segment.payload() + MPF_IDENTIFIER.length;
                for (long offset : mpfOffsets(mpf, MPF_IDENTIFIER.length)) {
                    byte[] start = offset <= 0 ? new byte[0]
                            : source.bytesUpTo(tiffStart + offset, 3);
                    if (start.length == 3 && Bytes.u8(start, 0) == 0xFF
                            && Bytes.u8(start, 1) == SOI && Bytes.u8(start, 2) == 0xFF) {
                        offsets.add(tiffStart + offset);
                    }
                }
            } catch (IOException | RuntimeException malformed) {
                Log.w(TAG, "Unable to read a JPEG's MPF segment", malformed);
            }
        }
        return offsets;
    }

    /**
     * The offsets the MP Entry tag lists, measured from the TIFF header, the first picture's
     * being zero.
     */
    @VisibleForTesting
    @NonNull
    static List<Long> mpfOffsets(@NonNull byte[] mpf, int tiff) {
        List<Long> offsets = new ArrayList<>();
        if (!isTiffHeader(mpf, tiff)) {
            return offsets;
        }
        boolean little = mpf[tiff] == 'I';
        long ifd = tiff + u32(mpf, tiff + 4, little);
        if (ifd + 2 > mpf.length) {
            return offsets;
        }
        int count = u16(mpf, (int) ifd, little);
        for (int i = 0; i < count; i++) {
            int entry = (int) ifd + 2 + i * 12;
            if (entry + 12 > mpf.length) {
                break;
            }
            if (u16(mpf, entry, little) != MPF_ENTRY) {
                continue;
            }
            long size = u32(mpf, entry + 4, little);
            long at = tiff + u32(mpf, entry + 8, little);
            for (long image = 0; image * 16 + 16 <= size && at + image * 16 + 16 <= mpf.length;
                    image++) {
                offsets.add(u32(mpf, (int) (at + image * 16 + 8), little));
            }
        }
        return offsets;
    }

    private static int u16(@NonNull byte[] data, int at, boolean little) {
        return little ? Bytes.u16le(data, at) : Bytes.u16(data, at);
    }

    private static long u32(@NonNull byte[] data, int at, boolean little) {
        return little ? Bytes.u32le(data, at) : Bytes.u32(data, at);
    }

    /**
     * A motion photo in the trailer Samsung's cameras append, which ends "SEFT" and lists its
     * records by their distance back from its directory.
     */
    @Nullable
    static MediaTags.MotionPhoto samsungMotionPhoto(@NonNull ByteSource source) {
        try {
            long length = source.length();
            if (length < 16) {
                return null;
            }
            byte[] tail = source.bytes(length - 8, 8);
            if (!Bytes.startsWith(tail, 4, "SEFT")) {
                return null;
            }
            long directoryLength = Bytes.u32le(tail, 0);
            long directory = length - 8 - directoryLength;
            if (directory < 0 || directoryLength < 12 || directoryLength > 1024 * 1024) {
                return null;
            }
            byte[] entries = source.bytes(directory, directoryLength);
            if (!Bytes.startsWith(entries, 0, "SEFH")) {
                return null;
            }
            long count = Bytes.u32le(entries, 8);
            for (int i = 0; i < count && 12 + i * 12 + 12 <= entries.length; i++) {
                int entry = 12 + i * 12;
                long back = Bytes.u32le(entries, entry + 4);
                long size = Bytes.u32le(entries, entry + 8);
                long record = directory - back;
                if (record < 0 || size < 8 || record + size > directory) {
                    continue;
                }
                byte[] header = source.bytes(record, Math.min(size, 64));
                long nameLength = Bytes.u32le(header, 4);
                if (nameLength > 56 || 8 + nameLength > header.length) {
                    continue;
                }
                String name = new String(header, 8, (int) nameLength, Bytes.LATIN_1);
                if ("MotionPhoto_Data".equals(name)) {
                    long start = record + 8 + nameLength;
                    long videoLength = size - 8 - nameLength;
                    if (MediaTags.MotionPhoto.isVideoAt(source, start)) {
                        return new MediaTags.MotionPhoto(MediaTags.MotionPhoto.FORMAT_SAMSUNG,
                                1, start, videoLength, -1);
                    }
                }
            }
        } catch (IOException | RuntimeException malformed) {
            Log.w(TAG, "Unable to read a Samsung trailer", malformed);
        }
        return null;
    }

    @Override
    void save(@NonNull File file) throws IOException {
        if (isExifDirty()) {
            Exif exif = exif();
            if (exif != null) {
                keepCaptureTime(exif);
                exif.save();
            }
        }
        if (mXmpDirty) {
            writeXmp(file, mXmp.isEmpty() ? null : mXmp.toBytes());
        }
        mXmpDirty = false;
        saved();
    }

    /**
     * Rewrites the file with a packet in place of its own, or with none for null. Every other
     * segment and all that follows the picture is kept as it was.
     *
     * <p>The packet goes ahead of an MPF segment, wherever the Exif is, and a file whose packet
     * comes after one is refused: MPF counts from itself, so only what is ahead of it may change
     * size.</p>
     *
     * @throws IOException if the packet is too large for a segment, or comes after MPF
     */
    @WorkerThread
    static void writeXmp(@NonNull File file, @Nullable byte[] packet) throws IOException {
        if (packet != null && packet.length > MAX_XMP_BYTES) {
            throw new IOException("The XMP packet is " + packet.length + " bytes, over a segment");
        }
        try (ByteSource source = ByteSource.of(file)) {
            List<Segment> segments = segments(source, 0);
            final List<Segment> kept = new ArrayList<>();
            int insertAt = 0;
            int mpfAt = -1;
            for (Segment segment : segments) {
                if (segment.is(APP1, XMP_IDENTIFIER)) {
                    if (mpfAt >= 0) {
                        // Rewriting it would move the gain map without moving what locates it.
                        throw new IOException("The XMP follows the MPF segment");
                    }
                    continue;
                }
                kept.add(segment);
                if (mpfAt < 0 && segment.is(APP2, MPF_IDENTIFIER)) {
                    mpfAt = kept.size() - 1;
                }
                if (mpfAt < 0
                        && (segment.marker == APP0 || segment.is(APP1, EXIF_IDENTIFIER))) {
                    insertAt = kept.size();
                }
            }
            long rest = segments.isEmpty() ? 2 : segments.get(segments.size() - 1).end();
            final int insert = insertAt;
            MediaFiles.replace(file, out -> {
                out.write(0xFF);
                out.write(SOI);
                for (int i = 0; i <= kept.size(); i++) {
                    if (i == insert && packet != null) {
                        writeSegment(out, APP1, XMP_IDENTIFIER, packet);
                    }
                    if (i < kept.size()) {
                        Segment segment = kept.get(i);
                        MediaFiles.copy(source, segment.offset, segment.end(), out);
                    }
                }
                MediaFiles.copy(source, rest, source.length(), out);
            });
        }
    }

    /** Writes one segment: its marker, its length, an identifier and a payload. */
    static void writeSegment(@NonNull OutputStream out, int marker, @NonNull byte[] identifier,
            @NonNull byte[] payload) throws IOException {
        int length = 2 + identifier.length + payload.length;
        out.write(0xFF);
        out.write(marker);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.write(identifier);
        out.write(payload);
    }

    /** One JPEG segment as bytes, for tests that build files. */
    @VisibleForTesting
    @NonNull
    static byte[] segment(int marker, @NonNull byte[] identifier, @NonNull byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            writeSegment(out, marker, identifier, payload);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return out.toByteArray();
    }
}
