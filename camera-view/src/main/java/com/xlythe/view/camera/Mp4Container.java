package com.xlythe.view.camera;

import android.location.Location;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An MP4, QuickTime, 3GP or M4A file.
 *
 * <p>Its metadata lives in {@code moov}: QuickTime's {@code ©}-prefixed user-data boxes, the
 * keyed metadata Apple and Android write, iTunes' item list, which is what an M4A's player reads,
 * and 3GPP's asset boxes, which are read. The recording time is also kept in the movie header,
 * and the rotation in the video track's matrix. XMP goes in a top-level uuid box.</p>
 *
 * <p>A changed {@code moov} goes back where it was when it fits there, with any free box after
 * it as room to grow, and otherwise moves to the end of the file, leaving a free box behind; the
 * samples never move, so no offset to them changes. A fragmented file, whose fragments must
 * follow its {@code moov}, never moves it; a {@code moov} that grows pushes the fragments along
 * instead, where every fragment counts its data from itself, and its random access index is
 * moved to match.</p>
 */
final class Mp4Container extends MediaContainer {
    private static final String TAG = "MediaTags";

    /** The uuid of the box XMP goes in. */
    static final byte[] XMP_UUID = {(byte) 0xBE, 0x7A, (byte) 0xCF, (byte) 0xCB, (byte) 0x97,
            (byte) 0xA9, 0x42, (byte) 0xE8, (byte) 0x9C, 0x71, (byte) 0x99, (byte) 0x94,
            (byte) 0x91, (byte) 0xE3, (byte) 0xAF, (byte) 0xAC};

    /** The uuid of the box version 1 of the spherical video metadata goes in. */
    static final byte[] SPHERICAL_UUID = {(byte) 0xFF, (byte) 0xCC, (byte) 0x82, 0x63,
            (byte) 0xF8, 0x55, 0x4A, (byte) 0x93, (byte) 0x88, 0x14, 0x58, 0x7A, 0x02, 0x52,
            0x1F, (byte) 0xDD};

    /** The largest moov this reads into memory: far more than any recording's metadata. */
    private static final long MAX_MOOV_BYTES = 64L * 1024 * 1024;

    /** How much of a fragmented file is moved at a time when its moov grows. */
    private static final int PUSH_BUFFER_BYTES = 256 * 1024;

    /** Seconds between 1904, where QuickTime counts from, and 1970. */
    static final long SECONDS_1904_TO_1970 = 2082844800L;

    /** The data types of the item values written here. */
    private static final int TYPE_UTF8 = 1;

    /** The handler of a meta box of iTunes items. */
    private static final String MDIR = "mdir";

    /** The handler of a meta box of keyed metadata. */
    private static final String MDTA = "mdta";

    /** The keyed-metadata key for the identifier a Live Photo's two halves share. */
    static final String KEY_CONTENT_IDENTIFIER = "com.apple.quicktime.content.identifier";

    /** The keyed-metadata key for the cover picture. */
    static final String KEY_ARTWORK = "com.apple.quicktime.artwork";

    /** A heading as QuickTime writes one: degrees, then T or M for true or magnetic north. */
    private static final Pattern DIRECTION = Pattern.compile("^\\s*([+-]?\\d+(?:\\.\\d+)?)");

    /** ISO 6709: a latitude, a longitude and maybe an altitude, each signed. */
    private static final Pattern ISO_6709 = Pattern.compile(
            "([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)?");

    /** Whether the file can be written. */
    private final boolean mWritable;

    /** What the file is, from its brand. */
    private final int mFormat;

    /** Whether it holds video, or only sound. */
    private final int mKind;

    /** The top-level boxes. */
    @NonNull
    private final List<IsoBoxes.Box> mTopLevel;

    /** The top-level moov, or null. */
    @Nullable
    private final IsoBoxes.Box mMoovBox;

    /** The moov's bytes, as read, or null if there is none or it is too large to read. */
    @Nullable
    private byte[] mMoov;

    /** The moov's own children's root, a box covering all of {@link #mMoov}. */
    @Nullable
    private IsoBoxes.Box mRoot;

    /** The top-level XMP box, or null. */
    @Nullable
    private final IsoBoxes.Box mXmpBox;

    /** The packet, as read or as changed. */
    @NonNull
    private final Xmp mXmp;

    /** Whether the packet has changed. */
    private boolean mXmpDirty;

    /** Whether the file is fragmented, so its moov may not move. */
    private final boolean mFragmented;

    /** The QuickTime user-data boxes to change, by type; null removes one. */
    private final Map<String, byte[]> mUserData = new LinkedHashMap<>();

    /** The keyed metadata to change, by key, as typed values; null removes one. */
    private final Map<String, byte[]> mKeys = new LinkedHashMap<>();

    /** The iTunes items to change, by type, as typed values; null removes one. */
    private final Map<String, byte[]> mItems = new LinkedHashMap<>();

    /** The recording time for the movie header, in seconds since 1970, or null to leave it. */
    @Nullable
    private Long mHeaderTime;

    /** The rotation for the video track's matrix, or null to leave it. */
    @Nullable
    private Integer mRotation;

    private Mp4Container(@NonNull ByteSource source, boolean writable, int format, int kind,
            @NonNull List<IsoBoxes.Box> topLevel, @Nullable IsoBoxes.Box moovBox,
            @Nullable byte[] moov, @Nullable IsoBoxes.Box xmpBox, @NonNull Xmp xmp,
            boolean fragmented) {
        mWritable = writable;
        mFormat = format;
        mKind = kind;
        mTopLevel = topLevel;
        mMoovBox = moovBox;
        mMoov = moov;
        mRoot = moov == null ? null : new IsoBoxes.Box("moov", 0, 8, moov.length, false);
        mXmpBox = xmpBox;
        mXmp = xmp;
        mFragmented = fragmented;
    }

    @WorkerThread
    @NonNull
    static Mp4Container read(@NonNull ByteSource source, boolean writable) throws IOException {
        List<IsoBoxes.Box> topLevel = IsoBoxes.children(source, 0, source.length());
        IsoBoxes.Box ftyp = IsoBoxes.find(topLevel, "ftyp");
        String brand = null;
        boolean audioBrand = false;
        boolean threeGpp = false;
        if (ftyp != null && ftyp.dataSize() >= 4) {
            byte[] brands = source.bytesUpTo(ftyp.data(), (int) Math.min(ftyp.dataSize(), 256));
            brand = Bytes.fourCc(brands, 0);
            for (int at = 0; at + 4 <= brands.length; at += 4) {
                String compatible = Bytes.fourCc(brands, at);
                threeGpp |= at != 4 && compatible.startsWith("3g");
                audioBrand |= compatible.startsWith("M4A") || compatible.startsWith("M4B")
                        || compatible.startsWith("M4P");
            }
        }
        IsoBoxes.Box moovBox = IsoBoxes.find(topLevel, "moov");
        byte[] moov = null;
        if (moovBox != null && moovBox.size <= MAX_MOOV_BYTES) {
            byte[] raw = source.bytes(moovBox.offset, moovBox.size);
            // Edited as a box with an ordinary header, whatever header it was written with.
            moov = moovBox.header == 8 ? raw : IsoBoxes.box("moov",
                    Arrays.copyOfRange(raw, moovBox.header, raw.length));
        }
        IsoBoxes.Box xmpBox = null;
        Xmp xmp = null;
        for (IsoBoxes.Box box : topLevel) {
            if ("uuid".equals(box.type) && box.dataSize() < MAX_MOOV_BYTES
                    && Arrays.equals(XMP_UUID, source.bytes(box.data() - 16, 16))) {
                xmpBox = box;
                xmp = Xmp.parse(source.bytes(box.data(), box.dataSize()));
                break;
            }
        }
        boolean video = false;
        boolean fragmented = IsoBoxes.find(topLevel, "moof") != null;
        if (moov != null) {
            IsoBoxes.Box root = new IsoBoxes.Box("moov", 0, 8, moov.length, false);
            for (IsoBoxes.Box child : IsoBoxes.children(moov, root, 0)) {
                String handler = "trak".equals(child.type) ? handler(moov, child) : null;
                // A picture sequence plays as a video does.
                if ("vide".equals(handler) || "pict".equals(handler)) {
                    video = true;
                } else if ("mvex".equals(child.type)) {
                    fragmented = true;
                }
            }
            if (xmp == null) {
                IsoBoxes.Box legacy = IsoBoxes.path(moov, root, "udta", "XMP_");
                if (legacy != null) {
                    xmp = Xmp.parse(IsoBoxes.data(moov, legacy));
                }
            }
        }
        int format;
        if ("qt  ".equals(brand) || ftyp == null) {
            // A movie with no brand at all is QuickTime's, from before brands.
            format = MediaTags.FORMAT_QUICKTIME;
        } else if (threeGpp) {
            format = MediaTags.FORMAT_3GP;
        } else if (audioBrand && !video) {
            format = MediaTags.FORMAT_M4A;
        } else {
            format = MediaTags.FORMAT_MP4;
        }
        int kind = video ? MediaTags.KIND_VIDEO
                : moov != null || audioBrand ? MediaTags.KIND_AUDIO : MediaTags.KIND_UNKNOWN;
        if (writable && fragmented && moovBox != null) {
            writable = canPushAlong(box -> source.bytes(box.offset, box.size), topLevel,
                    moovBox);
        }
        return new Mp4Container(source, writable, format, kind, topLevel, moovBox, moov, xmpBox,
                xmp == null ? Xmp.empty() : xmp, fragmented);
    }

    /** A track's handler type, such as "vide" or "soun", or null. */
    @Nullable
    private static String handler(@NonNull byte[] moov, @NonNull IsoBoxes.Box trak) {
        IsoBoxes.Box hdlr = IsoBoxes.path(moov, trak, "mdia", "hdlr");
        return hdlr == null || hdlr.dataSize() < 12 ? null
                : Bytes.fourCc(moov, (int) hdlr.data() + 8);
    }

    @Override
    int format() {
        return mFormat;
    }

    @Override
    int kind() {
        return mKind;
    }

    /** Whether the moov can be written. */
    private boolean canWrite() {
        return mWritable && mMoov != null;
    }

    @Override
    boolean keeps(@Field int field) {
        return field != AMBIENT && canWrite()
                && (field != ORIENTATION || videoTrack() != null);
    }

    @Override
    boolean hasNativeHome(@Field int field) {
        return field != AMBIENT;
    }

    // ---------- Reading ----------

    /** The first video track, or null. */
    @Nullable
    private IsoBoxes.Box videoTrack() {
        if (mMoov == null) {
            return null;
        }
        for (IsoBoxes.Box child : IsoBoxes.children(mMoov, mRoot, 0)) {
            if ("trak".equals(child.type) && "vide".equals(handler(mMoov, child))) {
                return child;
            }
        }
        return null;
    }

    /** The moov's user data box, or null. */
    @Nullable
    private IsoBoxes.Box userData() {
        return mMoov == null ? null : IsoBoxes.path(mMoov, mRoot, "udta");
    }

    /** A user-data text box's text, by type, or null. */
    @Nullable
    private String userDataText(@NonNull String type) {
        if (mUserData.containsKey(type)) {
            byte[] box = mUserData.get(type);
            return box == null ? null : VideoMetadata.textOf(box);
        }
        IsoBoxes.Box udta = userData();
        if (udta == null) {
            return null;
        }
        IsoBoxes.Box box = IsoBoxes.find(IsoBoxes.children(mMoov, udta, 0), type);
        if (box == null) {
            return null;
        }
        byte[] bytes = IsoBoxes.bytes(mMoov, box);
        String text = VideoMetadata.textOf(bytes);
        if (text == null || text.isEmpty()) {
            // Some writers put an iTunes data box here instead.
            IsoBoxes.Box data = IsoBoxes.find(IsoBoxes.children(mMoov, box, 0), "data");
            text = data == null ? null : VideoMetadata.valueOf(IsoBoxes.bytes(mMoov, data));
        }
        return text;
    }

    /** A keyed value's data box, by key, or null. */
    @Nullable
    private byte[] keyData(@NonNull String key) {
        if (mKeys.containsKey(key)) {
            byte[] value = mKeys.get(key);
            return value == null ? null : IsoBoxes.box("data", value);
        }
        return mMoov == null ? null : VideoMetadata.findMetadata(mMoov, key);
    }

    /** The iTunes item list in the user data, or null. */
    @Nullable
    private IsoBoxes.Box itemList() {
        IsoBoxes.Box udta = userData();
        if (udta == null) {
            return null;
        }
        for (IsoBoxes.Box child : IsoBoxes.children(mMoov, udta, 0)) {
            if ("meta".equals(child.type)) {
                IsoBoxes.Box ilst = IsoBoxes.find(IsoBoxes.children(mMoov, child,
                        IsoBoxes.metaSkip(mMoov, child)), "ilst");
                if (ilst != null) {
                    return ilst;
                }
            }
        }
        return null;
    }

    /** An iTunes item's data box, by type, or null. */
    @Nullable
    private byte[] itemData(@NonNull String type) {
        if (mItems.containsKey(type)) {
            byte[] value = mItems.get(type);
            return value == null ? null : IsoBoxes.box("data", value);
        }
        IsoBoxes.Box ilst = itemList();
        if (ilst == null) {
            return null;
        }
        IsoBoxes.Box item = IsoBoxes.find(IsoBoxes.children(mMoov, ilst, 0), type);
        IsoBoxes.Box data = item == null ? null
                : IsoBoxes.find(IsoBoxes.children(mMoov, item, 0), "data");
        return data == null ? null : IsoBoxes.bytes(mMoov, data);
    }

    @Nullable
    private String itemText(@NonNull String type) {
        byte[] data = itemData(type);
        return data == null ? null : VideoMetadata.valueOf(data);
    }

    /** A 3GPP asset box's text, by type, or null. */
    @Nullable
    private String assetText(@NonNull String type) {
        IsoBoxes.Box udta = userData();
        IsoBoxes.Box box = udta == null ? null
                : IsoBoxes.find(IsoBoxes.children(mMoov, udta, 0), type);
        if (box == null || box.dataSize() < 7) {
            return null;
        }
        return assetString(mMoov, (int) box.data() + 6, (int) box.end());
    }

    /** A 3GPP string: UTF-16 if it starts with a byte order mark, else UTF-8, to a NUL. */
    @Nullable
    private static String assetString(@NonNull byte[] data, int at, int end) {
        if (at + 2 <= end && Bytes.u16(data, at) == 0xFEFF) {
            int stop = at + 2;
            while (stop + 1 < end && (data[stop] != 0 || data[stop + 1] != 0)) {
                stop += 2;
            }
            return new String(data, at + 2, stop - at - 2, Bytes.UTF_16BE);
        }
        int stop = Bytes.terminator(data, at, end);
        return new String(data, at, stop - at, Bytes.UTF_8);
    }

    /** The first of these that is set, or null. */
    @Nullable
    private static String first(@Nullable String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    @Nullable
    @Override
    String videoTag(@NonNull VideoMetadata.Tag tag) {
        String key = tag.metadataKey();
        if (key != null) {
            byte[] data = keyData(key);
            String value = data == null ? null : VideoMetadata.valueOf(data);
            if (value != null) {
                return value;
            }
        }
        String type = tag.userDataType();
        return type == null ? null : userDataText(type);
    }

    /** The iTunes item a field also goes in, or null. */
    @Nullable
    private static String itemType(@Field int field) {
        switch (field) {
            case TITLE:
                return "©nam";
            case DESCRIPTION:
                return "desc";
            case AUTHOR:
                return "©ART";
            case COPYRIGHT:
                return "cprt";
            case SOFTWARE:
                return "©too";
            case CAPTURE_TIME:
                return "©day";
            case COVER_ART:
                return "covr";
            default:
                return null;
        }
    }

    /** The QuickTime tag a text field goes in. */
    @NonNull
    private static VideoMetadata.Tag tagFor(@Field int field) {
        switch (field) {
            case TITLE:
                return VideoMetadata.Tag.TITLE;
            case DESCRIPTION:
                return VideoMetadata.Tag.DESCRIPTION;
            case AUTHOR:
                return VideoMetadata.Tag.AUTHOR;
            case COPYRIGHT:
                return VideoMetadata.Tag.COPYRIGHT;
            case MAKE:
                return VideoMetadata.Tag.MAKE;
            case MODEL:
                return VideoMetadata.Tag.MODEL;
            default:
                return VideoMetadata.Tag.SOFTWARE;
        }
    }

    /** The 3GPP asset box a field may be read from, or null. */
    @Nullable
    private static String assetType(@Field int field) {
        switch (field) {
            case TITLE:
                return "titl";
            case DESCRIPTION:
                return "dscp";
            case AUTHOR:
                return "auth";
            case COPYRIGHT:
                return "cprt";
            default:
                return null;
        }
    }

    @Nullable
    @Override
    String text(@Field int field) {
        String item = itemType(field);
        String asset = assetType(field);
        String value = first(videoTag(tagFor(field)), item == null ? null : itemText(item),
                asset == null ? null : assetText(asset));
        if (value == null && field == AUTHOR) {
            value = first(videoTag(VideoMetadata.Tag.ARTIST), userDataText("©aut"),
                    userDataText("©ART"), assetText("perf"));
        }
        if (value == null && field == DESCRIPTION) {
            value = first(userDataText("©des"), itemText("©des"),
                    itemText("©cmt"));
        }
        return value;
    }

    @Override
    boolean setText(@Field int field, @Nullable String value) {
        setVideoTag(tagFor(field), value);
        String item = itemType(field);
        if (item != null && writesItems(item)) {
            mItems.put(item, value == null ? null
                    : VideoMetadata.typed(TYPE_UTF8, value.getBytes(Bytes.UTF_8)));
        }
        return true;
    }

    /** Whether an iTunes item is written: always in an M4A, and where the file has it already. */
    private boolean writesItems(@NonNull String type) {
        return mFormat == MediaTags.FORMAT_M4A || itemData(type) != null;
    }

    @Override
    boolean setVideoTag(@NonNull VideoMetadata.Tag tag, @Nullable String value) {
        if (!canWrite()) {
            return false;
        }
        String userDataType = tag.userDataType();
        String key = tag.metadataKey();
        if (value != null && key != null) {
            // Encoded first, so a number tag given a word changes nothing.
            try {
                VideoMetadata.metadataValue(tag, value);
            } catch (IllegalArgumentException notANumber) {
                return false;
            }
        }
        if (userDataType != null) {
            mUserData.put(userDataType, value == null ? null : VideoMetadata.textBox(tag, value));
        }
        if (key != null) {
            mKeys.put(key, value == null ? null : VideoMetadata.metadataValue(tag, value));
        }
        markDirty();
        return true;
    }

    @Override
    long captureTime() {
        long time = MediaTags.parseIsoTime(first(videoTag(VideoMetadata.Tag.CREATION_DATE),
                itemText("©day")));
        if (time != -1) {
            return time;
        }
        if (mHeaderTime != null) {
            return mHeaderTime == -SECONDS_1904_TO_1970 ? -1 : mHeaderTime * 1000;
        }
        long seconds = headerTime();
        return seconds > 0 ? (seconds - SECONDS_1904_TO_1970) * 1000 : -1;
    }

    /** The movie header's creation time, in seconds since 1904, or 0. */
    private long headerTime() {
        IsoBoxes.Box mvhd = mMoov == null ? null : IsoBoxes.path(mMoov, mRoot, "mvhd");
        if (mvhd == null || mvhd.dataSize() < 20) {
            return 0;
        }
        int at = (int) mvhd.data();
        return mMoov[at] == 1 ? Bytes.u64(mMoov, at + 4) : Bytes.u32(mMoov, at + 4);
    }

    @Override
    void setCaptureTime(long millis) {
        String text = millis < 0 ? null : MediaTags.formatIsoTime(millis, TimeZone.getDefault());
        setVideoTag(VideoMetadata.Tag.CREATION_DATE, text);
        if (writesItems("©day")) {
            mItems.put("©day", text == null ? null
                    : VideoMetadata.typed(TYPE_UTF8, text.getBytes(Bytes.UTF_8)));
        }
        // Zero is what the header holds for no time.
        mHeaderTime = millis < 0 ? -SECONDS_1904_TO_1970
                : (millis - (((millis % 1000) + 1000) % 1000)) / 1000;
    }

    @Nullable
    @Override
    Location location() {
        String iso = videoTag(VideoMetadata.Tag.LOCATION);
        Location location = parseIso6709(iso);
        return location != null ? location : assetLocation();
    }

    /** A location in ISO 6709, such as "+37.3317-122.0302+012.345/", or null. */
    @VisibleForTesting
    @Nullable
    static Location parseIso6709(@Nullable String iso) {
        if (iso == null) {
            return null;
        }
        Matcher matcher = ISO_6709.matcher(iso.trim());
        if (!matcher.lookingAt()) {
            return null;
        }
        try {
            double latitude = Double.parseDouble(matcher.group(1));
            double longitude = Double.parseDouble(matcher.group(2));
            if (Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
                return null;
            }
            Location location = new Location(TAG);
            location.setLatitude(latitude);
            location.setLongitude(longitude);
            if (matcher.group(3) != null) {
                location.setAltitude(Double.parseDouble(matcher.group(3)));
            }
            return location;
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    /** ISO 6709 as QuickTime writes it, with the altitude when there is one. */
    @VisibleForTesting
    @NonNull
    static String formatIso6709(@NonNull Location location) {
        String iso = String.format(Locale.US, "%+08.4f%+09.4f", location.getLatitude(),
                location.getLongitude());
        if (location.hasAltitude()) {
            iso += String.format(Locale.US, "%+.3f", location.getAltitude());
        }
        return iso + "/";
    }

    /** The location in a 3GPP loci box, or null. */
    @Nullable
    private Location assetLocation() {
        IsoBoxes.Box udta = userData();
        IsoBoxes.Box loci = udta == null ? null
                : IsoBoxes.find(IsoBoxes.children(mMoov, udta, 0), "loci");
        if (loci == null || loci.dataSize() < 20) {
            return null;
        }
        int at = (int) loci.data() + 6;
        int end = (int) loci.end();
        // Past the name, NUL-terminated in UTF-8 or UTF-16, and the role.
        if (at + 2 <= end && Bytes.u16(mMoov, at) == 0xFEFF) {
            at += 2;
            while (at + 1 < end && (mMoov[at] != 0 || mMoov[at + 1] != 0)) {
                at += 2;
            }
            at += 2;
        } else {
            at = Bytes.terminator(mMoov, at, end) + 1;
        }
        at += 1;
        if (at + 12 > end) {
            return null;
        }
        Location location = new Location(TAG);
        location.setLongitude((int) Bytes.u32(mMoov, at) / 65536.0);
        location.setLatitude((int) Bytes.u32(mMoov, at + 4) / 65536.0);
        location.setAltitude((int) Bytes.u32(mMoov, at + 8) / 65536.0);
        return location;
    }

    @Override
    void setLocation(@Nullable Location location) {
        setVideoTag(VideoMetadata.Tag.LOCATION, location == null ? null
                : formatIso6709(location));
    }

    @Override
    float heading() {
        String facing = videoTag(VideoMetadata.Tag.DIRECTION_FACING);
        if (facing == null) {
            return Float.NaN;
        }
        Matcher matcher = DIRECTION.matcher(facing);
        try {
            return matcher.find() ? Float.parseFloat(matcher.group(1)) : Float.NaN;
        } catch (NumberFormatException malformed) {
            return Float.NaN;
        }
    }

    @Override
    void setHeading(float degrees) {
        setVideoTag(VideoMetadata.Tag.DIRECTION_FACING, Float.isNaN(degrees) ? null
                : String.format(Locale.US, "%+.2fT", ((degrees % 360) + 360) % 360));
    }

    @Override
    int orientation() {
        if (mRotation != null) {
            return MediaTags.toExifOrientation(mRotation, false);
        }
        IsoBoxes.Box track = videoTrack();
        int[] matrix = track == null ? null : matrix(track);
        return matrix == null ? 0 : orientationOf(matrix);
    }

    /** The a, b, c and d of a track header's matrix, in 16.16 fixed point, or null. */
    @Nullable
    private int[] matrix(@NonNull IsoBoxes.Box track) {
        int at = matrixOffset(track);
        if (at < 0) {
            return null;
        }
        return new int[] {(int) Bytes.u32(mMoov, at), (int) Bytes.u32(mMoov, at + 4),
                (int) Bytes.u32(mMoov, at + 12), (int) Bytes.u32(mMoov, at + 16)};
    }

    /** Where a track header's matrix starts in the moov, or -1. */
    private int matrixOffset(@NonNull IsoBoxes.Box track) {
        IsoBoxes.Box tkhd = IsoBoxes.find(IsoBoxes.children(mMoov, track, 0), "tkhd");
        if (tkhd == null) {
            return -1;
        }
        int at = (int) tkhd.data();
        int matrix = at + (mMoov[at] == 1 ? 52 : 40);
        return matrix + 36 <= tkhd.end() ? matrix : -1;
    }

    /** An Exif orientation from a matrix's a, b, c and d. */
    @VisibleForTesting
    static int orientationOf(@NonNull int[] matrix) {
        int a = Integer.signum(matrix[0]);
        int b = Integer.signum(matrix[1]);
        int c = Integer.signum(matrix[2]);
        int d = Integer.signum(matrix[3]);
        boolean mirrored = (long) matrix[0] * matrix[3] - (long) matrix[1] * matrix[2] < 0;
        int rotation;
        if (!mirrored) {
            rotation = a == 1 ? 0 : a == -1 ? 180 : b == 1 ? 90 : 270;
        } else {
            // Mirrored left to right first, then turned.
            rotation = a == -1 ? 0 : a == 1 ? 180 : b == 1 ? 270 : 90;
        }
        if (a == 0 && b == 0 && c == 0 && d == 0) {
            return 0;
        }
        return MediaTags.toExifOrientation(rotation, mirrored);
    }

    @Override
    boolean setOrientation(int orientation) {
        if (MediaTags.isMirrored(orientation) || videoTrack() == null) {
            return false;
        }
        mRotation = MediaTags.rotationOf(orientation);
        markDirty();
        return true;
    }

    @Nullable
    @Override
    byte[] coverArt() {
        byte[] data = keyData(KEY_ARTWORK);
        if (data == null) {
            data = itemData("covr");
        }
        return data == null || data.length <= 16 ? null : Arrays.copyOfRange(data, 16, data.length);
    }

    @Override
    boolean setCoverArt(@Nullable byte[] image) {
        byte[] typed = null;
        if (image != null) {
            try {
                typed = VideoMetadata.typed(VideoMetadata.imageType(image), image);
            } catch (IllegalArgumentException unsupported) {
                return false;
            }
        }
        mKeys.put(KEY_ARTWORK, typed);
        if (writesItems("covr")) {
            mItems.put("covr", typed);
        }
        markDirty();
        return true;
    }

    @NonNull
    @Override
    Xmp xmp() {
        return mXmp;
    }

    @Override
    int maxXmpBytes() {
        return mWritable ? Integer.MAX_VALUE : NO_XMP;
    }

    @Override
    void xmpChanged() {
        super.xmpChanged();
        mXmpDirty = true;
    }

    @Override
    int videoTransfer() {
        IsoBoxes.Box entry = videoSampleEntry();
        if (entry == null) {
            return MediaTags.TRANSFER_UNKNOWN;
        }
        // The colour box first, then Dolby Vision's configuration, then the stream's own
        // parameter set, which some writers leave as the only place HDR is said.
        int dolby = MediaTags.TRANSFER_UNKNOWN;
        int stream = MediaTags.TRANSFER_UNKNOWN;
        for (IsoBoxes.Box child : IsoBoxes.children(mMoov, entry, 78)) {
            int at = (int) child.data();
            if ("colr".equals(child.type) && child.dataSize() >= 10) {
                String type = Bytes.fourCc(mMoov, at);
                int transfer = "nclx".equals(type) || "nclc".equals(type)
                        ? transferOf(Bytes.u16(mMoov, at + 6)) : MediaTags.TRANSFER_UNKNOWN;
                if (transfer != MediaTags.TRANSFER_UNKNOWN) {
                    return transfer;
                }
            } else if (("dvcC".equals(child.type) || "dvvC".equals(child.type)
                    || "dvwC".equals(child.type)) && child.dataSize() >= 5) {
                int profile = Bytes.u8(mMoov, at + 2) >> 1;
                int compatibility = Bytes.u8(mMoov, at + 4) >> 4;
                dolby = compatibility == 4 ? MediaTags.TRANSFER_HLG
                        : compatibility == 2 && profile != 5 ? MediaTags.TRANSFER_SDR
                        : MediaTags.TRANSFER_PQ;
            } else if ("hvcC".equals(child.type)) {
                stream = transferOf(HevcParameters.transferCharacteristics(mMoov, at,
                        (int) child.end()));
            } else if ("vpcC".equals(child.type) && child.dataSize() >= 9) {
                stream = transferOf(Bytes.u8(mMoov, at + 8));
            } else if ("avcC".equals(child.type)) {
                stream = transferOf(AvcParameters.transferCharacteristics(mMoov, at,
                        (int) child.end()));
            } else if ("esds".equals(child.type)) {
                stream = transferOf(AvcParameters.esdsTransfer(mMoov, at, (int) child.end()));
            }
        }
        if (dolby != MediaTags.TRANSFER_UNKNOWN) {
            return dolby;
        }
        if (stream != MediaTags.TRANSFER_UNKNOWN) {
            return stream;
        }
        // A Dolby Vision sample entry with no configuration is still Dolby Vision.
        String type = entry.type;
        return type.startsWith("dvh") || type.startsWith("dva") || type.equals("dav1")
                ? MediaTags.TRANSFER_PQ : MediaTags.TRANSFER_UNKNOWN;
    }

    /** The transfer for an H.273 transfer characteristics code. */
    @VisibleForTesting
    static int transferOf(int code) {
        switch (code) {
            case 16:
                return MediaTags.TRANSFER_PQ;
            case 18:
                return MediaTags.TRANSFER_HLG;
            case 0:
            case 2:
            case 3:
                // Reserved, and unspecified.
                return MediaTags.TRANSFER_UNKNOWN;
            default:
                // Every other curve H.273 defines is a standard dynamic range one.
                return code > 0 && code <= 17 ? MediaTags.TRANSFER_SDR
                        : MediaTags.TRANSFER_UNKNOWN;
        }
    }

    /** The first video track's first sample entry, or null. */
    @Nullable
    private IsoBoxes.Box videoSampleEntry() {
        IsoBoxes.Box track = videoTrack();
        IsoBoxes.Box stsd = track == null ? null
                : IsoBoxes.path(mMoov, track, "mdia", "minf", "stbl", "stsd");
        if (stsd == null || stsd.dataSize() < 8) {
            return null;
        }
        List<IsoBoxes.Box> entries = IsoBoxes.children(mMoov, stsd, 8);
        IsoBoxes.Box entry = entries.isEmpty() ? null : entries.get(0);
        return entry != null && entry.dataSize() >= 78 ? entry : null;
    }

    @Nullable
    @Override
    MediaTags.SphericalVideo sphericalVideo() {
        IsoBoxes.Box track = videoTrack();
        if (track == null) {
            return null;
        }
        IsoBoxes.Box entry = videoSampleEntry();
        if (entry != null) {
            List<IsoBoxes.Box> children = IsoBoxes.children(mMoov, entry, 78);
            IsoBoxes.Box sv3d = IsoBoxes.find(children, "sv3d");
            if (sv3d != null) {
                IsoBoxes.Box st3d = IsoBoxes.find(children, "st3d");
                int stereo = st3d != null && st3d.dataSize() >= 5
                        ? Bytes.u8(mMoov, (int) st3d.data() + 4) : 0;
                return sphericalV2(sv3d, stereo);
            }
        }
        for (IsoBoxes.Box child : IsoBoxes.children(mMoov, track, 0)) {
            if ("uuid".equals(child.type)
                    && Arrays.equals(SPHERICAL_UUID, IsoBoxes.uuid(mMoov, child))) {
                return MediaTags.SphericalVideo.fromVersion1(
                        Xmp.parse(IsoBoxes.data(mMoov, child)));
            }
        }
        return null;
    }

    /** Version 2 of the spherical video metadata, from its sv3d box. */
    @Nullable
    private MediaTags.SphericalVideo sphericalV2(@NonNull IsoBoxes.Box sv3d, int stereo) {
        IsoBoxes.Box proj = IsoBoxes.find(IsoBoxes.children(mMoov, sv3d, 0), "proj");
        if (proj == null) {
            return null;
        }
        float yaw = 0;
        float pitch = 0;
        float roll = 0;
        int projection = MediaTags.SphericalVideo.PROJECTION_OTHER;
        double[] bounds = {0, 0, 0, 0};
        for (IsoBoxes.Box child : IsoBoxes.children(mMoov, proj, 0)) {
            int at = (int) child.data() + 4;
            if ("prhd".equals(child.type) && child.dataSize() >= 16) {
                yaw = (int) Bytes.u32(mMoov, at) / 65536f;
                pitch = (int) Bytes.u32(mMoov, at + 4) / 65536f;
                roll = (int) Bytes.u32(mMoov, at + 8) / 65536f;
            } else if ("equi".equals(child.type) && child.dataSize() >= 20) {
                projection = MediaTags.SphericalVideo.PROJECTION_EQUIRECTANGULAR;
                for (int i = 0; i < 4; i++) {
                    bounds[i] = Bytes.u32(mMoov, at + i * 4) / 4294967296.0;
                }
            } else if ("cbmp".equals(child.type)) {
                projection = MediaTags.SphericalVideo.PROJECTION_CUBEMAP;
            } else if ("mshp".equals(child.type)) {
                projection = MediaTags.SphericalVideo.PROJECTION_MESH;
            }
        }
        return new MediaTags.SphericalVideo(projection, stereo, yaw, pitch, roll, bounds[0],
                bounds[1], bounds[2], bounds[3]);
    }

    @Nullable
    @Override
    int[] videoSize() {
        IsoBoxes.Box track = videoTrack();
        int matrix = track == null ? -1 : matrixOffset(track);
        if (matrix < 0) {
            return null;
        }
        int width = (int) (Bytes.u32(mMoov, matrix + 36) >> 16);
        int height = (int) (Bytes.u32(mMoov, matrix + 40) >> 16);
        if (width <= 0 || height <= 0) {
            return null;
        }
        int rotation = MediaTags.rotationOf(orientation());
        return rotation == 90 || rotation == 270 ? new int[] {height, width}
                : new int[] {width, height};
    }

    @Nullable
    @Override
    String livePhotoId() {
        byte[] data = keyData(KEY_CONTENT_IDENTIFIER);
        return data == null ? null : VideoMetadata.valueOf(data);
    }

    @Nullable
    @Override
    MediaTags.CaptureSettings captureSettings() {
        String fps = videoTag(VideoMetadata.Tag.CAPTURE_FPS);
        float rate = Float.NaN;
        try {
            rate = fps == null ? Float.NaN : Float.parseFloat(fps);
        } catch (NumberFormatException malformed) {
            Log.w(TAG, "A capture frame rate that is not a number: " + fps);
        }
        return Float.isNaN(rate) ? null : MediaTags.CaptureSettings.ofFrameRate(rate);
    }

    // ---------- Writing ----------

    @Override
    void save(@NonNull File file) throws IOException {
        byte[] moov = mMoov != null && (!mUserData.isEmpty() || !mKeys.isEmpty()
                || !mItems.isEmpty() || mHeaderTime != null || mRotation != null)
                ? editedMoov() : null;
        byte[] xmp = null;
        boolean removeXmp = false;
        if (mXmpDirty) {
            if (mXmp.isEmpty()) {
                removeXmp = true;
            } else {
                xmp = IsoBoxes.uuidBox(XMP_UUID, mXmp.toBytes());
            }
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            List<Placement> placements = new ArrayList<>();
            if (moov != null) {
                placements.add(new Placement(mMoovBox, moov, !mFragmented));
            }
            if (xmp != null || (removeXmp && mXmpBox != null)) {
                placements.add(new Placement(mXmpBox, xmp, true));
            }
            place(raf, mTopLevel, placements);
        }
        mUserData.clear();
        mKeys.clear();
        mItems.clear();
        mHeaderTime = null;
        mRotation = null;
        mXmpDirty = false;
        saved();
    }

    /** A box to write: where its old self is, if anywhere, and its new bytes, or null. */
    @VisibleForTesting
    static final class Placement {
        @Nullable
        final IsoBoxes.Box old;

        @Nullable
        final byte[] bytes;

        /** Whether it may move to the end of the file if it no longer fits where it is. */
        final boolean movable;

        Placement(@Nullable IsoBoxes.Box old, @Nullable byte[] bytes, boolean movable) {
            this.old = old;
            this.bytes = bytes;
            this.movable = movable;
        }
    }

    /**
     * Writes boxes into a file of top-level boxes: each where its old self was if it fits, with
     * a following free box as room, and otherwise at the end, with its old self freed.
     */
    @VisibleForTesting
    static void place(@NonNull RandomAccessFile raf, @NonNull List<IsoBoxes.Box> topLevel,
            @NonNull List<Placement> placements) throws IOException {
        long length = raf.length();
        IsoBoxes.Box last = topLevel.isEmpty() ? null : topLevel.get(topLevel.size() - 1);
        List<byte[]> appends = new ArrayList<>();
        List<IsoBoxes.Box> freed = new ArrayList<>();
        // Where the file was pushed along to make room, and by how much, for the boxes after.
        long[] shift = {Long.MAX_VALUE, 0};
        for (Placement placement : placements) {
            IsoBoxes.Box original = placement.old;
            IsoBoxes.Box old = moved(original, shift);
            byte[] bytes = placement.bytes;
            if (old == null) {
                if (bytes != null) {
                    appends.add(bytes);
                }
                continue;
            }
            if (bytes == null) {
                freed.add(old);
                continue;
            }
            long room = old.size;
            IsoBoxes.Box next = moved(following(topLevel, original), shift);
            if (next != null && ("free".equals(next.type) || "skip".equals(next.type))
                    && !next.toEnd) {
                room += next.size;
            }
            if (old.end() == length && original == last) {
                raf.seek(old.offset);
                raf.write(bytes);
                raf.setLength(old.offset + bytes.length);
                length = raf.length();
                last = null;
            } else if (bytes.length == room || bytes.length + 8 <= room) {
                raf.seek(old.offset);
                raf.write(bytes);
                if (room > bytes.length) {
                    raf.write(freeHeader(room - bytes.length));
                }
            } else if (placement.movable) {
                appends.add(bytes);
                freed.add(old);
            } else if (canPushAlong(box -> {
                byte[] whole = new byte[(int) box.size];
                raf.seek(box.offset);
                raf.readFully(whole);
                return whole;
            }, topLevel, old)) {
                long grow = bytes.length - room;
                pushAlong(raf, old.offset + room, grow);
                moveIndex(raf, topLevel, shift, old.offset + room, grow);
                raf.seek(old.offset);
                raf.write(bytes);
                shift[0] = old.offset + room;
                shift[1] = grow;
                length = raf.length();
            } else {
                throw new IOException("A fragmented file whose fragments say where their data "
                        + "is has no room for its larger metadata");
            }
        }
        last = moved(last, shift);
        if (!appends.isEmpty()) {
            if (last != null && last.end() != length) {
                throw new IOException("The file has data after its last box, so nothing can "
                        + "follow it");
            }
            if (last != null && last.toEnd) {
                // The box ran to the end of the file; it now has to say how long it is.
                if (last.size > 0xFFFFFFFFL || last.header != 8) {
                    throw new IOException("The last box is too large to give a size");
                }
                raf.seek(last.offset);
                byte[] size = new byte[4];
                Bytes.put32(size, 0, last.size);
                raf.write(size);
            }
            raf.seek(length);
            for (byte[] bytes : appends) {
                raf.write(bytes);
            }
        }
        // Freed only once the new copies are written, so the file always has one.
        for (IsoBoxes.Box old : freed) {
            raf.seek(old.offset);
            if (old.header > 8 && !"uuid".equals(old.type)) {
                raf.write(largeFreeHeader(old.size));
            } else {
                raf.write(freeHeader(old.size));
            }
        }
    }

    /** Where a box is now, after the file was pushed along from a place, or null for null. */
    @Nullable
    private static IsoBoxes.Box moved(@Nullable IsoBoxes.Box box, @NonNull long[] shift) {
        if (box == null || box.offset < shift[0]) {
            return box;
        }
        return new IsoBoxes.Box(box.type, box.offset + shift[1], box.header, box.size,
                box.toEnd);
    }

    /** Reads a whole top-level box. */
    private interface BoxReader {
        @WorkerThread
        @NonNull
        byte[] read(@NonNull IsoBoxes.Box box) throws IOException;
    }

    /**
     * Whether everything after a box can be pushed along: every fragment counts its data from
     * itself rather than from the file's start, and no segment index before it points past it.
     */
    @WorkerThread
    private static boolean canPushAlong(@NonNull BoxReader reader,
            @NonNull List<IsoBoxes.Box> topLevel, @NonNull IsoBoxes.Box box) throws IOException {
        for (IsoBoxes.Box top : topLevel) {
            if ("sidx".equals(top.type) && top.offset < box.offset) {
                return false;
            }
            if (!"moof".equals(top.type)) {
                continue;
            }
            if (top.size > MAX_MOOV_BYTES) {
                return false;
            }
            byte[] moof = reader.read(top);
            IsoBoxes.Box root = new IsoBoxes.Box("moof", 0, top.header, moof.length, false);
            for (IsoBoxes.Box traf : IsoBoxes.children(moof, root, 0)) {
                if (!"traf".equals(traf.type)) {
                    continue;
                }
                for (IsoBoxes.Box tfhd : IsoBoxes.children(moof, traf, 0)) {
                    // The base data offset flag: the data is counted from the file's start.
                    if ("tfhd".equals(tfhd.type) && tfhd.dataSize() >= 4
                            && (Bytes.u8(moof, (int) tfhd.data() + 3) & 0x01) != 0) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Moves everything from a place to the end of the file along by some bytes. */
    private static void pushAlong(@NonNull RandomAccessFile raf, long from, long by)
            throws IOException {
        long length = raf.length();
        raf.setLength(length + by);
        byte[] buffer = new byte[PUSH_BUFFER_BYTES];
        long end = length;
        while (end > from) {
            int count = (int) Math.min(buffer.length, end - from);
            long start = end - count;
            raf.seek(start);
            raf.readFully(buffer, 0, count);
            raf.seek(start + by);
            raf.write(buffer, 0, count);
            end = start;
        }
    }

    /** Moves the fragments' places in a random access index along with them. */
    private static void moveIndex(@NonNull RandomAccessFile raf,
            @NonNull List<IsoBoxes.Box> topLevel, @NonNull long[] shift, long from, long by)
            throws IOException {
        IsoBoxes.Box mfra = IsoBoxes.find(topLevel, "mfra");
        if (mfra == null || mfra.size > MAX_MOOV_BYTES) {
            return;
        }
        long at = mfra.offset >= from ? mfra.offset + by : mfra.offset;
        byte[] index = new byte[(int) mfra.size];
        raf.seek(at);
        raf.readFully(index);
        IsoBoxes.Box root = new IsoBoxes.Box("mfra", 0, mfra.header, index.length, false);
        for (IsoBoxes.Box tfra : IsoBoxes.children(index, root, 0)) {
            if (!"tfra".equals(tfra.type) || tfra.dataSize() < 16) {
                continue;
            }
            int data = (int) tfra.data();
            boolean wide = Bytes.u8(index, data) == 1;
            long sizes = Bytes.u32(index, data + 8);
            int extra = (int) (((sizes >> 4) & 3) + ((sizes >> 2) & 3) + (sizes & 3) + 3);
            long count = Bytes.u32(index, data + 12);
            int entry = data + 16;
            int timeSize = wide ? 8 : 4;
            for (long i = 0; i < count && entry + 2 * timeSize + extra <= tfra.end(); i++) {
                int offsetAt = entry + timeSize;
                long offset = wide ? (Bytes.u32(index, offsetAt) << 32)
                        | Bytes.u32(index, offsetAt + 4) : Bytes.u32(index, offsetAt);
                if (offset >= from) {
                    offset += by;
                    if (wide) {
                        Bytes.put32(index, offsetAt, offset >>> 32);
                        Bytes.put32(index, offsetAt + 4, offset);
                    } else {
                        Bytes.put32(index, offsetAt, offset);
                    }
                }
                entry += 2 * timeSize + extra;
            }
        }
        raf.seek(at);
        raf.write(index);
    }

    @Nullable
    private static IsoBoxes.Box following(@NonNull List<IsoBoxes.Box> boxes,
            @NonNull IsoBoxes.Box box) {
        int index = boxes.indexOf(box);
        return index >= 0 && index + 1 < boxes.size() ? boxes.get(index + 1) : null;
    }

    /** The header of a free box of a size. */
    @NonNull
    private static byte[] freeHeader(long size) {
        if (size > 0xFFFFFFFFL) {
            return largeFreeHeader(size);
        }
        byte[] header = new byte[8];
        Bytes.put32(header, 0, size);
        System.arraycopy(Bytes.fourCc("free"), 0, header, 4, 4);
        return header;
    }

    @NonNull
    private static byte[] largeFreeHeader(long size) {
        byte[] header = new byte[16];
        Bytes.put32(header, 0, 1);
        System.arraycopy(Bytes.fourCc("free"), 0, header, 4, 4);
        Bytes.put64(header, 8, size);
        return header;
    }

    /** The moov with every staged change in it. */
    @NonNull
    private byte[] editedMoov() {
        byte[] moov = mMoov.clone();
        IsoBoxes.Box root = mRoot;
        if (mHeaderTime != null) {
            IsoBoxes.Box mvhd = IsoBoxes.path(moov, root, "mvhd");
            if (mvhd != null && mvhd.dataSize() >= 20) {
                int at = (int) mvhd.data();
                long seconds = mHeaderTime + SECONDS_1904_TO_1970;
                if (moov[at] == 1) {
                    Bytes.put64(moov, at + 4, seconds);
                } else {
                    Bytes.put32(moov, at + 4, Math.max(0, Math.min(0xFFFFFFFFL, seconds)));
                }
            }
        }
        if (mRotation != null) {
            IsoBoxes.Box track = videoTrack();
            int at = track == null ? -1 : matrixOffset(track);
            if (at >= 0) {
                int[] abcd = rotationMatrix(mRotation);
                Bytes.put32(moov, at, abcd[0]);
                Bytes.put32(moov, at + 4, abcd[1]);
                Bytes.put32(moov, at + 12, abcd[2]);
                Bytes.put32(moov, at + 16, abcd[3]);
            }
        }
        List<byte[]> children = new ArrayList<>();
        int udtaIndex = -1;
        int metaIndex = -1;
        for (IsoBoxes.Box child : IsoBoxes.children(moov, root, 0)) {
            if ("udta".equals(child.type) && udtaIndex < 0) {
                udtaIndex = children.size();
            } else if ("meta".equals(child.type) && metaIndex < 0
                    && MDTA.equals(metaHandler(moov, child))) {
                metaIndex = children.size();
            }
            children.add(IsoBoxes.bytes(moov, child));
        }
        if (!mUserData.isEmpty() || !mItems.isEmpty()) {
            byte[] udta = udtaIndex >= 0 ? children.get(udtaIndex) : IsoBoxes.box("udta");
            udta = editUserData(udta);
            if (udtaIndex >= 0) {
                children.set(udtaIndex, udta);
            } else {
                children.add(udta);
            }
        }
        if (!mKeys.isEmpty()) {
            byte[] meta = metaIndex >= 0 ? children.get(metaIndex) : null;
            meta = editKeys(meta);
            if (metaIndex >= 0) {
                children.set(metaIndex, meta);
            } else {
                children.add(meta);
            }
        }
        return IsoBoxes.box("moov", children);
    }

    /** The a, b, c and d of a matrix that turns a picture clockwise by a right angle. */
    @VisibleForTesting
    @NonNull
    static int[] rotationMatrix(int rotation) {
        int one = 0x10000;
        switch (rotation) {
            case 90:
                return new int[] {0, one, -one, 0};
            case 180:
                return new int[] {-one, 0, 0, -one};
            case 270:
                return new int[] {0, -one, one, 0};
            default:
                return new int[] {one, 0, 0, one};
        }
    }

    /** A meta box's handler type, or null. */
    @Nullable
    private static String metaHandler(@NonNull byte[] data, @NonNull IsoBoxes.Box meta) {
        IsoBoxes.Box hdlr = IsoBoxes.find(IsoBoxes.children(data, meta,
                IsoBoxes.metaSkip(data, meta)), "hdlr");
        return hdlr == null || hdlr.dataSize() < 12 ? null
                : Bytes.fourCc(data, (int) hdlr.data() + 8);
    }

    /** A udta box with the user-data and iTunes item changes made. */
    @NonNull
    private byte[] editUserData(@NonNull byte[] udta) {
        IsoBoxes.Box box = IsoBoxes.children(udta, 0, udta.length).get(0);
        List<byte[]> children = new ArrayList<>();
        Map<String, byte[]> remaining = new LinkedHashMap<>(mUserData);
        int itemsIndex = -1;
        for (IsoBoxes.Box child : IsoBoxes.children(udta, box, 0)) {
            if (remaining.containsKey(child.type)) {
                byte[] replacement = remaining.remove(child.type);
                if (replacement != null) {
                    children.add(replacement);
                }
                continue;
            }
            if ("meta".equals(child.type) && itemsIndex < 0
                    && MDIR.equals(metaHandler(udta, child))) {
                itemsIndex = children.size();
            }
            children.add(IsoBoxes.bytes(udta, child));
        }
        for (byte[] added : remaining.values()) {
            if (added != null) {
                children.add(added);
            }
        }
        if (!mItems.isEmpty()) {
            byte[] meta = itemsIndex >= 0 ? children.get(itemsIndex) : itemsMeta();
            meta = editItems(meta);
            if (itemsIndex >= 0) {
                children.set(itemsIndex, meta);
            } else {
                children.add(meta);
            }
        }
        return IsoBoxes.box("udta", children);
    }

    /** An empty meta box of iTunes items, as iTunes writes one. */
    @NonNull
    private static byte[] itemsMeta() {
        byte[] hdlr = new byte[4 + 4 + 4 + 12 + 1];
        System.arraycopy(Bytes.fourCc(MDIR), 0, hdlr, 8, 4);
        System.arraycopy(Bytes.fourCc("appl"), 0, hdlr, 12, 4);
        return IsoBoxes.box("meta", new byte[4], IsoBoxes.box("hdlr", hdlr),
                IsoBoxes.box("ilst"));
    }

    /** A meta box of iTunes items with the item changes made. */
    @NonNull
    private byte[] editItems(@NonNull byte[] meta) {
        IsoBoxes.Box box = IsoBoxes.children(meta, 0, meta.length).get(0);
        int skip = IsoBoxes.metaSkip(meta, box);
        List<byte[]> parts = new ArrayList<>();
        parts.add(Arrays.copyOfRange(meta, (int) box.data(), (int) box.data() + skip));
        boolean edited = false;
        for (IsoBoxes.Box child : IsoBoxes.children(meta, box, skip)) {
            if ("ilst".equals(child.type) && !edited) {
                parts.add(editList(meta, child, mItems));
                edited = true;
            } else {
                parts.add(IsoBoxes.bytes(meta, child));
            }
        }
        if (!edited) {
            byte[] empty = IsoBoxes.box("ilst");
            parts.add(editList(empty, new IsoBoxes.Box("ilst", 0, 8, 8, false), mItems));
        }
        return IsoBoxes.box("meta", parts);
    }

    /** An item list with items replaced, removed or added, each around one data box. */
    @NonNull
    private static byte[] editList(@NonNull byte[] data, @NonNull IsoBoxes.Box ilst,
            @NonNull Map<String, byte[]> changes) {
        Map<String, byte[]> remaining = new LinkedHashMap<>(changes);
        List<byte[]> items = new ArrayList<>();
        for (IsoBoxes.Box item : IsoBoxes.children(data, ilst, 0)) {
            if (remaining.containsKey(item.type)) {
                byte[] value = remaining.remove(item.type);
                if (value != null) {
                    items.add(IsoBoxes.box(item.type, IsoBoxes.box("data", value)));
                }
            } else {
                items.add(IsoBoxes.bytes(data, item));
            }
        }
        for (Map.Entry<String, byte[]> added : remaining.entrySet()) {
            if (added.getValue() != null) {
                items.add(IsoBoxes.box(added.getKey(), IsoBoxes.box("data", added.getValue())));
            }
        }
        return IsoBoxes.box("ilst", items);
    }

    /** A keyed-metadata meta box with the key changes made, or a new one. */
    @NonNull
    private byte[] editKeys(@Nullable byte[] meta) {
        if (meta == null) {
            byte[] hdlr = new byte[4 + 4 + 4 + 12 + 1];
            System.arraycopy(Bytes.fourCc(MDTA), 0, hdlr, 8, 4);
            meta = IsoBoxes.box("meta", IsoBoxes.box("hdlr", hdlr));
        }
        IsoBoxes.Box box = IsoBoxes.children(meta, 0, meta.length).get(0);
        int skip = IsoBoxes.metaSkip(meta, box);
        List<byte[]> parts = new ArrayList<>();
        byte[] keys = null;
        byte[] ilst = null;
        for (IsoBoxes.Box child : IsoBoxes.children(meta, box, skip)) {
            if ("keys".equals(child.type) && keys == null) {
                keys = IsoBoxes.bytes(meta, child);
            } else if ("ilst".equals(child.type) && ilst == null) {
                ilst = IsoBoxes.bytes(meta, child);
            } else {
                parts.add(IsoBoxes.bytes(meta, child));
            }
        }
        List<String> names = new ArrayList<>();
        List<byte[]> entries = new ArrayList<>();
        byte[] keysVersion = new byte[4];
        if (keys != null && keys.length >= 16) {
            System.arraycopy(keys, 8, keysVersion, 0, 4);
            long count = Bytes.u32(keys, 12);
            int at = 16;
            for (long i = 0; i < count && at + 8 <= keys.length; i++) {
                int size = (int) Bytes.u32(keys, at);
                if (size < 8 || at + size > keys.length) {
                    break;
                }
                entries.add(Arrays.copyOfRange(keys, at, at + size));
                names.add(new String(keys, at + 8, size - 8, Bytes.UTF_8));
                at += size;
            }
        }
        Map<Integer, byte[]> values = new LinkedHashMap<>();
        if (ilst != null && ilst.length >= 8) {
            IsoBoxes.Box list = new IsoBoxes.Box("ilst", 0, 8, ilst.length, false);
            for (IsoBoxes.Box item : IsoBoxes.children(ilst, list, 0)) {
                values.put((int) Bytes.u32(ilst, (int) item.offset + 4),
                        IsoBoxes.bytes(ilst, item));
            }
        }
        for (Map.Entry<String, byte[]> change : mKeys.entrySet()) {
            int index = names.indexOf(change.getKey()) + 1;
            if (index == 0) {
                if (change.getValue() == null) {
                    continue;
                }
                byte[] name = change.getKey().getBytes(Bytes.UTF_8);
                byte[] entry = new byte[8 + name.length];
                Bytes.put32(entry, 0, entry.length);
                System.arraycopy(Bytes.fourCc(MDTA), 0, entry, 4, 4);
                System.arraycopy(name, 0, entry, 8, name.length);
                entries.add(entry);
                names.add(change.getKey());
                index = names.size();
            }
            if (change.getValue() == null) {
                values.remove(index);
            } else {
                byte[] item = IsoBoxes.box("item", IsoBoxes.box("data", change.getValue()));
                Bytes.put32(item, 4, index);
                values.put(index, item);
            }
        }
        byte[] count = new byte[4];
        Bytes.put32(count, 0, entries.size());
        List<byte[]> keyParts = new ArrayList<>();
        keyParts.add(keysVersion);
        keyParts.add(count);
        keyParts.addAll(entries);
        parts.add(IsoBoxes.box("keys", keyParts));
        parts.add(IsoBoxes.box("ilst", new ArrayList<>(values.values())));
        List<byte[]> body = new ArrayList<>();
        body.add(Arrays.copyOfRange(meta, (int) box.data(), (int) box.data() + skip));
        body.addAll(parts);
        return IsoBoxes.box("meta", body);
    }
}
