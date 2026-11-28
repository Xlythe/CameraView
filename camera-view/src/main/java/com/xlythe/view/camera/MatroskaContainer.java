package com.xlythe.view.camera;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import java.io.File;
import java.io.IOException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Matroska and WebM, read only: the segment's title, dates and writing application, its tags,
 * an attached cover picture, and the first video track's size, colour and spherical
 * projection. The clusters that hold the frames are skipped by their sizes.
 */
final class MatroskaContainer extends MediaContainer {
    /** The EBML header's ID, with its marker bits. */
    static final int ID_EBML = 0x1A45DFA3;
    /** The DocType element's ID, with its marker bits. */
    static final int ID_DOC_TYPE = 0x4282;
    /** The Segment element's ID, with its marker bits. */
    static final int ID_SEGMENT = 0x18538067;
    /** The Info element's ID, with its marker bits. */
    static final int ID_INFO = 0x1549A966;
    /** The Title element's ID, with its marker bits. */
    static final int ID_TITLE = 0x7BA9;
    /** The DateUTC element's ID, with its marker bits. */
    static final int ID_DATE_UTC = 0x4461;
    /** The MuxingApp element's ID, with its marker bits. */
    static final int ID_MUXING_APP = 0x4D80;
    /** The WritingApp element's ID, with its marker bits. */
    static final int ID_WRITING_APP = 0x5741;
    /** The Tracks element's ID, with its marker bits. */
    static final int ID_TRACKS = 0x1654AE6B;
    /** The TrackEntry element's ID, with its marker bits. */
    static final int ID_TRACK_ENTRY = 0xAE;
    /** The TrackType element's ID, with its marker bits. */
    static final int ID_TRACK_TYPE = 0x83;
    /** The Video element's ID, with its marker bits. */
    static final int ID_VIDEO = 0xE0;
    /** The PixelWidth element's ID, with its marker bits. */
    static final int ID_PIXEL_WIDTH = 0xB0;
    /** The PixelHeight element's ID, with its marker bits. */
    static final int ID_PIXEL_HEIGHT = 0xBA;
    /** The StereoMode element's ID, with its marker bits. */
    static final int ID_STEREO_MODE = 0x53B8;
    /** The Colour element's ID, with its marker bits. */
    static final int ID_COLOUR = 0x55B0;
    /** The TransferCharacteristics element's ID, with its marker bits. */
    static final int ID_TRANSFER = 0x55BA;
    /** The Projection element's ID, with its marker bits. */
    static final int ID_PROJECTION = 0x7670;
    /** The ProjectionType element's ID, with its marker bits. */
    static final int ID_PROJECTION_TYPE = 0x7671;
    /** The ProjectionPrivate element's ID, with its marker bits. */
    static final int ID_PROJECTION_PRIVATE = 0x7672;
    /** The PoseYaw element's ID, with its marker bits. */
    static final int ID_POSE_YAW = 0x7673;
    /** The PosePitch element's ID, with its marker bits. */
    static final int ID_POSE_PITCH = 0x7674;
    /** The PoseRoll element's ID, with its marker bits. */
    static final int ID_POSE_ROLL = 0x7675;
    /** The Cluster element's ID, with its marker bits. */
    static final int ID_CLUSTER = 0x1F43B675;
    /** The Tags element's ID, with its marker bits. */
    static final int ID_TAGS = 0x1254C367;
    /** The Tag element's ID, with its marker bits. */
    static final int ID_TAG = 0x7373;
    /** The SimpleTag element's ID, with its marker bits. */
    static final int ID_SIMPLE_TAG = 0x67C8;
    /** The TagName element's ID, with its marker bits. */
    static final int ID_TAG_NAME = 0x45A3;
    /** The TagString element's ID, with its marker bits. */
    static final int ID_TAG_STRING = 0x4487;
    /** The Attachments element's ID, with its marker bits. */
    static final int ID_ATTACHMENTS = 0x1941A469;
    /** The AttachedFile element's ID, with its marker bits. */
    static final int ID_ATTACHED_FILE = 0x61A7;
    /** The FileName element's ID, with its marker bits. */
    static final int ID_FILE_NAME = 0x466E;
    /** The FileMediaType element's ID, with its marker bits. */
    static final int ID_FILE_MEDIA_TYPE = 0x4660;
    /** The FileData element's ID, with its marker bits. */
    static final int ID_FILE_DATA = 0x465C;

    /** A track type: video. */
    private static final int TRACK_VIDEO = 1;

    /** A track type: audio. */
    private static final int TRACK_AUDIO = 2;

    /** A projection type: equirectangular. */
    private static final int PROJECTION_EQUIRECTANGULAR = 1;

    /** A projection type: a cube map. */
    private static final int PROJECTION_CUBEMAP = 2;

    /** A projection type: a mesh. */
    private static final int PROJECTION_MESH = 3;

    /** The size of an element whose size is not known: all of its bits set. */
    private static final long UNKNOWN_SIZE = -1;

    /** The largest metadata element this reads whole. Clusters are never read. */
    private static final long MAX_ELEMENT = 16 * 1024 * 1024;

    /** Milliseconds from the epoch to 2001-01-01T00:00:00Z, which Matroska dates count from. */
    private static final long MATROSKA_EPOCH_MILLIS = 978307200000L;

    /** What it holds. */
    @MediaTags.Kind
    private final int mKind;

    /** The segment's title, or null. */
    @Nullable
    private final String mTitle;

    /** The program that wrote the file, or null. */
    @Nullable
    private final String mWritingApp;

    /** When the file was written, in milliseconds since the epoch, or -1. */
    private final long mDate;

    /** The tags, by upper case name. The first of a name wins. */
    @NonNull
    private final Map<String, String> mTags;

    /** The attached cover picture, or null. */
    @Nullable
    private final byte[] mCover;

    /** The first video track's size, or null. */
    @Nullable
    private final int[] mVideoSize;

    /** The first video track's H.273 transfer characteristics, or -1. */
    private final int mTransfer;

    /** The first video track's spherical projection, or null. */
    @Nullable
    private final MediaTags.SphericalVideo mSpherical;

    /** An empty packet, which can never be written. */
    private final Xmp mXmp = Xmp.empty();

    private MatroskaContainer(@NonNull Reader reader) {
        mKind = reader.mHasVideo ? MediaTags.KIND_VIDEO
                : reader.mHasAudio ? MediaTags.KIND_AUDIO : MediaTags.KIND_VIDEO;
        mTitle = reader.mTitle;
        mWritingApp = reader.mWritingApp != null ? reader.mWritingApp : reader.mMuxingApp;
        mDate = reader.mDate;
        mTags = reader.mTags;
        mCover = reader.mCover;
        mVideoSize = reader.mVideoSize;
        mTransfer = reader.mTransfer;
        mSpherical = reader.mSpherical;
    }

    /** Reads a Matroska or WebM file's metadata. */
    @WorkerThread
    @NonNull
    static MatroskaContainer read(@NonNull ByteSource source) throws IOException {
        Reader reader = new Reader(source);
        reader.read();
        return new MatroskaContainer(reader);
    }

    @Override
    int format() {
        return MediaTags.FORMAT_MATROSKA;
    }

    @Override
    int kind() {
        return mKind;
    }

    @Override
    boolean keeps(@Field int field) {
        return false;
    }

    @Override
    boolean hasNativeHome(@Field int field) {
        return field == TITLE || field == DESCRIPTION || field == AUTHOR || field == COPYRIGHT
                || field == SOFTWARE || field == CAPTURE_TIME || field == COVER_ART;
    }

    @Nullable
    @Override
    String text(@Field int field) {
        switch (field) {
            case TITLE:
                return mTitle != null ? mTitle : mTags.get("TITLE");
            case DESCRIPTION:
                return mTags.get("DESCRIPTION") != null ? mTags.get("DESCRIPTION")
                        : mTags.get("COMMENT");
            case AUTHOR:
                return mTags.get("ARTIST") != null ? mTags.get("ARTIST")
                        : mTags.get("DIRECTOR");
            case COPYRIGHT:
                return mTags.get("COPYRIGHT");
            case SOFTWARE:
                return mTags.get("ENCODER") != null ? mTags.get("ENCODER") : mWritingApp;
            default:
                return null;
        }
    }

    @Override
    long captureTime() {
        long recorded = parseDate(mTags.get("DATE_RECORDED"));
        return recorded != -1 ? recorded : mDate;
    }

    @Nullable
    @Override
    byte[] coverArt() {
        return mCover == null ? null : mCover.clone();
    }

    @Override
    int videoTransfer() {
        return Mp4Container.transferOf(mTransfer);
    }

    @Nullable
    @Override
    MediaTags.SphericalVideo sphericalVideo() {
        return mSpherical;
    }

    @Nullable
    @Override
    int[] videoSize() {
        return mVideoSize == null ? null : mVideoSize.clone();
    }

    @NonNull
    @Override
    Xmp xmp() {
        return mXmp;
    }

    @Override
    int maxXmpBytes() {
        return NO_XMP;
    }

    @Override
    void save(@NonNull File file) throws IOException {
        throw new IOException("Matroska is read only");
    }

    /**
     * A tag's date, as Matroska writes one: "2016-03-12 10:20:30" and shorter forms, in UTC
     * unless it says otherwise. Returns -1 for null or anything else.
     */
    @VisibleForTesting
    static long parseDate(@Nullable String text) {
        if (text == null) {
            return -1;
        }
        // The longest form first, so a date with a time keeps its time.
        String[] patterns = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm",
                "yyyy-MM-dd", "yyyy-MM", "yyyy"};
        for (String pattern : patterns) {
            SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            format.setLenient(false);
            Date date = format.parse(text.trim(), new ParsePosition(0));
            if (date != null) {
                return date.getTime();
            }
        }
        return -1;
    }

    /** Walks the elements once, keeping what the container answers with. */
    private static final class Reader {
        /** The file. */
        @NonNull
        private final ByteSource mSource;

        /** Whether a video track was found. */
        boolean mHasVideo;

        /** Whether an audio track was found. */
        boolean mHasAudio;

        /** The segment's title. */
        @Nullable
        String mTitle;

        /** The application that wrote the file. */
        @Nullable
        String mWritingApp;

        /** The library that muxed it. */
        @Nullable
        String mMuxingApp;

        /** When it was written, or -1. */
        long mDate = -1;

        /** The tags by name. */
        @NonNull
        final Map<String, String> mTags = new HashMap<>();

        /** The cover picture. */
        @Nullable
        byte[] mCover;

        /** The first video track's size. */
        @Nullable
        int[] mVideoSize;

        /** The first video track's transfer characteristics. */
        int mTransfer = -1;

        /** The first video track's projection. */
        @Nullable
        MediaTags.SphericalVideo mSpherical;

        Reader(@NonNull ByteSource source) {
            mSource = source;
        }

        void read() throws IOException {
            long length = mSource.length();
            long at = 0;
            while (at < length) {
                Header header = header(mSource, at);
                if (header == null) {
                    return;
                }
                if (header.id == ID_SEGMENT) {
                    long end = header.size == UNKNOWN_SIZE ? length
                            : Math.min(length, header.data + header.size);
                    readSegment(header.data, end);
                    return;
                }
                if (header.size == UNKNOWN_SIZE) {
                    return;
                }
                at = header.data + header.size;
            }
        }

        private void readSegment(long start, long end) throws IOException {
            long at = start;
            while (at < end) {
                Header header = header(mSource, at);
                if (header == null || header.size == UNKNOWN_SIZE) {
                    // A cluster of unknown size has no end to skip to.
                    return;
                }
                if (header.size <= MAX_ELEMENT && header.data + header.size <= end) {
                    switch (header.id) {
                        case ID_INFO:
                            readInfo(element(header));
                            break;
                        case ID_TRACKS:
                            readTracks(element(header));
                            break;
                        case ID_TAGS:
                            readTags(element(header));
                            break;
                        case ID_ATTACHMENTS:
                            readAttachments(element(header));
                            break;
                        default:
                            break;
                    }
                }
                at = header.data + header.size;
            }
        }

        @NonNull
        private byte[] element(@NonNull Header header) throws IOException {
            return mSource.bytes(header.data, header.size);
        }

        private void readInfo(@NonNull byte[] info) {
            for (Header child : children(info, 0, info.length)) {
                switch (child.id) {
                    case ID_TITLE:
                        mTitle = string(info, child);
                        break;
                    case ID_WRITING_APP:
                        mWritingApp = string(info, child);
                        break;
                    case ID_MUXING_APP:
                        mMuxingApp = string(info, child);
                        break;
                    case ID_DATE_UTC:
                        if (child.size == 8) {
                            mDate = MATROSKA_EPOCH_MILLIS
                                    + signed(info, child) / 1_000_000L;
                        }
                        break;
                    default:
                        break;
                }
            }
        }

        private void readTracks(@NonNull byte[] tracks) {
            for (Header entry : children(tracks, 0, tracks.length)) {
                if (entry.id != ID_TRACK_ENTRY) {
                    continue;
                }
                long type = 0;
                Header video = null;
                for (Header child : children(tracks, entry)) {
                    if (child.id == ID_TRACK_TYPE) {
                        type = unsigned(tracks, child);
                    } else if (child.id == ID_VIDEO) {
                        video = child;
                    }
                }
                if (type == TRACK_AUDIO) {
                    mHasAudio = true;
                } else if (type == TRACK_VIDEO && !mHasVideo) {
                    mHasVideo = true;
                    if (video != null) {
                        readVideo(tracks, video);
                    }
                }
            }
        }

        private void readVideo(@NonNull byte[] data, @NonNull Header video) {
            long width = 0;
            long height = 0;
            int stereo = MediaTags.SphericalVideo.STEREO_MONO;
            Header projection = null;
            for (Header child : children(data, video)) {
                switch (child.id) {
                    case ID_PIXEL_WIDTH:
                        width = unsigned(data, child);
                        break;
                    case ID_PIXEL_HEIGHT:
                        height = unsigned(data, child);
                        break;
                    case ID_STEREO_MODE:
                        stereo = stereoOf(unsigned(data, child));
                        break;
                    case ID_COLOUR:
                        for (Header colour : children(data, child)) {
                            if (colour.id == ID_TRANSFER) {
                                mTransfer = (int) unsigned(data, colour);
                            }
                        }
                        break;
                    case ID_PROJECTION:
                        projection = child;
                        break;
                    default:
                        break;
                }
            }
            if (width > 0 && height > 0 && width < Integer.MAX_VALUE
                    && height < Integer.MAX_VALUE) {
                mVideoSize = new int[] {(int) width, (int) height};
            }
            if (projection != null) {
                mSpherical = projection(data, projection, stereo);
            }
        }

        /** The spherical video a Projection element describes, or null for a flat one. */
        @Nullable
        private static MediaTags.SphericalVideo projection(@NonNull byte[] data,
                @NonNull Header projection, int stereo) {
            long type = 0;
            float yaw = 0;
            float pitch = 0;
            float roll = 0;
            double[] crop = {0, 0, 0, 0};
            for (Header child : children(data, projection)) {
                switch (child.id) {
                    case ID_PROJECTION_TYPE:
                        type = unsigned(data, child);
                        break;
                    case ID_POSE_YAW:
                        yaw = (float) real(data, child);
                        break;
                    case ID_POSE_PITCH:
                        pitch = (float) real(data, child);
                        break;
                    case ID_POSE_ROLL:
                        roll = (float) real(data, child);
                        break;
                    case ID_PROJECTION_PRIVATE:
                        // For equirectangular: version and flags, then the four bounds.
                        if (child.size >= 20) {
                            int at = (int) child.data + 4;
                            for (int i = 0; i < 4; i++) {
                                crop[i] = Bytes.u32(data, at + i * 4) / 4294967296.0;
                            }
                        }
                        break;
                    default:
                        break;
                }
            }
            int kind;
            if (type == PROJECTION_EQUIRECTANGULAR) {
                kind = MediaTags.SphericalVideo.PROJECTION_EQUIRECTANGULAR;
            } else if (type == PROJECTION_CUBEMAP) {
                kind = MediaTags.SphericalVideo.PROJECTION_CUBEMAP;
            } else if (type == PROJECTION_MESH) {
                kind = MediaTags.SphericalVideo.PROJECTION_MESH;
            } else {
                return null;
            }
            return new MediaTags.SphericalVideo(kind, stereo, yaw, pitch, roll, crop[0], crop[1],
                    crop[2], crop[3]);
        }

        private void readTags(@NonNull byte[] tags) {
            for (Header tag : children(tags, 0, tags.length)) {
                if (tag.id != ID_TAG) {
                    continue;
                }
                for (Header simple : children(tags, tag)) {
                    if (simple.id == ID_SIMPLE_TAG) {
                        readSimpleTag(tags, simple);
                    }
                }
            }
        }

        private void readSimpleTag(@NonNull byte[] data, @NonNull Header simple) {
            String name = null;
            String value = null;
            for (Header child : children(data, simple)) {
                if (child.id == ID_TAG_NAME) {
                    name = string(data, child);
                } else if (child.id == ID_TAG_STRING) {
                    value = string(data, child);
                } else if (child.id == ID_SIMPLE_TAG) {
                    readSimpleTag(data, child);
                }
            }
            if (name != null && value != null && !value.isEmpty()) {
                String key = name.toUpperCase(Locale.US);
                if (!mTags.containsKey(key)) {
                    mTags.put(key, value);
                }
            }
        }

        private void readAttachments(@NonNull byte[] attachments) {
            for (Header file : children(attachments, 0, attachments.length)) {
                if (file.id != ID_ATTACHED_FILE) {
                    continue;
                }
                String name = null;
                String type = null;
                Header data = null;
                for (Header child : children(attachments, file)) {
                    if (child.id == ID_FILE_NAME) {
                        name = string(attachments, child);
                    } else if (child.id == ID_FILE_MEDIA_TYPE) {
                        type = string(attachments, child);
                    } else if (child.id == ID_FILE_DATA) {
                        data = child;
                    }
                }
                boolean picture = type != null && type.startsWith("image/");
                boolean cover = name != null && name.toLowerCase(Locale.US).startsWith("cover");
                if (data != null && picture && (cover || mCover == null)) {
                    mCover = new byte[(int) data.size];
                    System.arraycopy(attachments, (int) data.data, mCover, 0, mCover.length);
                }
            }
        }

        private static int stereoOf(long mode) {
            if (mode == 1 || mode == 11) {
                return MediaTags.SphericalVideo.STEREO_LEFT_RIGHT;
            }
            if (mode == 2 || mode == 3) {
                return MediaTags.SphericalVideo.STEREO_TOP_BOTTOM;
            }
            return MediaTags.SphericalVideo.STEREO_MONO;
        }
    }

    /** An element's ID, where its data starts, and how long the data is. */
    @VisibleForTesting
    static final class Header {
        /** The ID, with its marker bits. */
        final int id;

        /** Where the data starts. */
        final long data;

        /** How long the data is, or {@link #UNKNOWN_SIZE}. */
        final long size;

        Header(int id, long data, long size) {
            this.id = id;
            this.data = data;
            this.size = size;
        }
    }

    /** The element header at a place in the file, or null if none can be read there. */
    @Nullable
    @WorkerThread
    private static Header header(@NonNull ByteSource source, long at) throws IOException {
        byte[] bytes = source.bytesUpTo(at, 12);
        Header header = header(bytes, 0, bytes.length);
        return header == null ? null : new Header(header.id, at + header.data, header.size);
    }

    /** The element header at a place in memory, or null if none can be read there. */
    @Nullable
    @VisibleForTesting
    static Header header(@NonNull byte[] data, int at, int end) {
        if (at >= end) {
            return null;
        }
        int first = Bytes.u8(data, at);
        int idLength = Integer.numberOfLeadingZeros(first) - 23;
        if (first == 0 || idLength > 4 || at + idLength >= end) {
            return null;
        }
        int id = 0;
        for (int i = 0; i < idLength; i++) {
            id = (id << 8) | Bytes.u8(data, at + i);
        }
        int sizeAt = at + idLength;
        int marker = Bytes.u8(data, sizeAt);
        int sizeLength = Integer.numberOfLeadingZeros(marker) - 23;
        if (marker == 0 || sizeAt + sizeLength > end) {
            return null;
        }
        long size = marker & (0xFF >> sizeLength);
        boolean unknown = size == (0xFF >> sizeLength);
        for (int i = 1; i < sizeLength; i++) {
            int b = Bytes.u8(data, sizeAt + i);
            size = (size << 8) | b;
            unknown &= b == 0xFF;
        }
        return new Header(id, sizeAt + sizeLength, unknown ? UNKNOWN_SIZE : size);
    }

    /** The elements inside one, skipping any that run past it. */
    @NonNull
    private static Iterable<Header> children(@NonNull byte[] data, @NonNull Header parent) {
        return children(data, (int) parent.data, (int) (parent.data + parent.size));
    }

    /** The elements between two places, stopping at one that cannot be read. */
    @NonNull
    private static Iterable<Header> children(@NonNull byte[] data, int start, int end) {
        List<Header> children = new ArrayList<>();
        int at = start;
        while (at < end) {
            Header header = header(data, at, end);
            if (header == null || header.size == UNKNOWN_SIZE
                    || header.data + header.size > end) {
                break;
            }
            children.add(header);
            at = (int) (header.data + header.size);
        }
        return children;
    }

    private static long unsigned(@NonNull byte[] data, @NonNull Header element) {
        long value = 0;
        for (int i = 0; i < element.size && i < 8; i++) {
            value = (value << 8) | Bytes.u8(data, (int) element.data + i);
        }
        return value;
    }

    private static long signed(@NonNull byte[] data, @NonNull Header element) {
        long value = unsigned(data, element);
        int bits = (int) Math.min(8, element.size) * 8;
        return bits == 0 || bits == 64 ? value : (value << (64 - bits)) >> (64 - bits);
    }

    private static double real(@NonNull byte[] data, @NonNull Header element) {
        long bits = unsigned(data, element);
        if (element.size == 4) {
            return Float.intBitsToFloat((int) bits);
        }
        return element.size == 8 ? Double.longBitsToDouble(bits) : 0;
    }

    @NonNull
    private static String string(@NonNull byte[] data, @NonNull Header element) {
        int end = (int) (element.data + element.size);
        int stop = (int) element.data;
        while (stop < end && data[stop] != 0) {
            stop++;
        }
        return new String(data, (int) element.data, stop - (int) element.data,
                Bytes.UTF_8);
    }
}
