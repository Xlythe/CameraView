package com.xlythe.view.camera;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A HEIF or AVIF picture: Exif and XMP are items of their own, found through the meta box's
 * item list and located by its item locations, and the orientation is the primary item's irot
 * and imir properties, which decoders apply; the Exif orientation is only informative here.
 *
 * <p>Changed Exif and XMP go in a new mdat straight after the meta box, which is rewritten to
 * point at them, and every offset to data after it is moved along by what was added. Data at the
 * end of the file, such as a motion photo's clip, stays at the end.</p>
 */
final class HeifContainer extends ExifImageContainer {
    /** The content type of an XMP item. */
    private static final String XMP_TYPE = "application/rdf+xml";

    /** The auxiliary type of Apple's gain map. */
    private static final String APPLE_GAIN_MAP = "urn:com:apple:photo:2020:aux:hdrgainmap";

    /** The most of the meta box, or of an item, this reads. */
    private static final long MAX_META = 16 * 1024 * 1024;

    /** One item, from its item info entry. */
    @VisibleForTesting
    static final class Item {
        final long id;
        @NonNull
        final String type;
        @Nullable
        final String contentType;

        Item(long id, @NonNull String type, @Nullable String contentType) {
            this.id = id;
            this.type = type;
            this.contentType = contentType;
        }
    }

    /** One extent of an item's data. */
    private static final class Extent {
        final long index;
        final long offset;
        final long length;

        Extent(long index, long offset, long length) {
            this.index = index;
            this.offset = offset;
            this.length = length;
        }
    }

    /** Where an item's data is. */
    private static final class Location {
        final long id;
        final int construction;
        final int dataReference;
        final long base;
        @NonNull
        final List<Extent> extents;

        Location(long id, int construction, int dataReference, long base,
                @NonNull List<Extent> extents) {
            this.id = id;
            this.construction = construction;
            this.dataReference = dataReference;
            this.base = base;
            this.extents = extents;
        }
    }

    /** Whether it is AVIF rather than HEIF. */
    private final boolean mAvif;

    /** The motion photo's clip, as the file was read, or null. */
    @Nullable
    private final MediaTags.MotionPhoto mMotionPhoto;

    /** The top-level boxes. */
    @NonNull
    private final List<IsoBoxes.Box> mTopLevel;

    /** The meta box, as a box in the file. */
    @NonNull
    private final IsoBoxes.Box mMetaBox;

    /** The meta box's bytes, from its header on. */
    @NonNull
    private final byte[] mMeta;

    /** The primary item. */
    private final long mPrimary;

    /** The items. */
    @NonNull
    private final List<Item> mItems;

    /** Where their data is. */
    @NonNull
    private final List<Location> mLocations;

    /** The item location box's version and field sizes: version, offset, length, base, index. */
    @NonNull
    private final int[] mIlocFormat;

    /** The property boxes, in order, as bytes. */
    @NonNull
    private final List<byte[]> mProperties;

    /** Each item's properties, by index from 1, the top bit marking one as essential. */
    @NonNull
    private final List<long[]> mAssociations;

    /** The Exif item's bare TIFF block, or null. */
    @Nullable
    private final byte[] mTiff;

    /** The packet, as read or as changed. */
    @NonNull
    private final Xmp mXmp;

    /** Whether the packet has changed. */
    private boolean mXmpDirty;

    /** The orientation to write, or 0 to leave it. */
    private int mNewOrientation;

    private HeifContainer(boolean avif, @NonNull ByteSource source,
            @NonNull List<IsoBoxes.Box> topLevel, @NonNull IsoBoxes.Box metaBox,
            @NonNull byte[] meta, long primary, @NonNull List<Item> items,
            @NonNull List<Location> locations, @NonNull int[] ilocFormat,
            @NonNull List<byte[]> properties, @NonNull List<long[]> associations,
            @Nullable byte[] tiff, @NonNull Xmp xmp, boolean writable) throws IOException {
        super(writable);
        mAvif = avif;
        mMotionPhoto = MediaTags.MotionPhoto.describedBy(xmp, source);
        mTopLevel = topLevel;
        mMetaBox = metaBox;
        mMeta = meta;
        mPrimary = primary;
        mItems = items;
        mLocations = locations;
        mIlocFormat = ilocFormat;
        mProperties = properties;
        mAssociations = associations;
        mTiff = tiff;
        mXmp = xmp;
        setReadExif(tiff == null ? null : readTiff(tiff));
    }

    @WorkerThread
    @NonNull
    static HeifContainer read(@NonNull ByteSource source, boolean writable) throws IOException {
        List<IsoBoxes.Box> topLevel = IsoBoxes.children(source, 0, source.length());
        IsoBoxes.Box ftyp = IsoBoxes.find(topLevel, "ftyp");
        IsoBoxes.Box metaBox = IsoBoxes.find(topLevel, "meta");
        if (ftyp == null || metaBox == null || metaBox.size > MAX_META) {
            throw new IOException("A HEIF picture without a meta box this can read");
        }
        byte[] brands = source.bytes(ftyp.data(), Math.min(ftyp.dataSize(), 256));
        boolean avif = "avif".equals(Bytes.fourCc(brands, 0))
                || "avis".equals(Bytes.fourCc(brands, 0));
        byte[] meta = source.bytes(metaBox.offset, metaBox.size);
        IsoBoxes.Box root = new IsoBoxes.Box("meta", 0, metaBox.header, meta.length, false);
        long primary = -1;
        List<Item> items = new ArrayList<>();
        List<Location> locations = new ArrayList<>();
        int[] ilocFormat = {1, 4, 4, 0, 0};
        List<byte[]> properties = new ArrayList<>();
        List<long[]> associations = new ArrayList<>();
        for (IsoBoxes.Box child : IsoBoxes.children(meta, root, 4)) {
            int at = (int) child.data();
            switch (child.type) {
                case "pitm":
                    primary = meta[at] == 0 ? Bytes.u16(meta, at + 4) : Bytes.u32(meta, at + 4);
                    break;
                case "iinf":
                    items = items(meta, child);
                    break;
                case "iloc":
                    locations = locations(meta, child, ilocFormat);
                    break;
                case "iprp":
                    readProperties(meta, child, properties, associations);
                    break;
                default:
                    break;
            }
        }
        items = describingFirst(items, meta, root, primary);
        byte[] idat = idat(meta, root);
        byte[] tiff = null;
        Xmp xmp = null;
        for (Item item : items) {
            Location location = location(locations, item.id);
            if (location == null) {
                continue;
            }
            if ("Exif".equals(item.type) && tiff == null) {
                byte[] data = data(source, location, idat);
                if (data != null && data.length > 4) {
                    long offset = Bytes.u32(data, 0);
                    if (offset < data.length - 4) {
                        tiff = tiffOf(Arrays.copyOfRange(data, 4 + (int) offset, data.length));
                    }
                    if (tiff == null) {
                        tiff = tiffOf(Arrays.copyOfRange(data, 4, data.length));
                    }
                }
            } else if ("mime".equals(item.type) && XMP_TYPE.equals(item.contentType)
                    && xmp == null) {
                byte[] data = data(source, location, idat);
                if (data != null) {
                    xmp = Xmp.parse(data);
                }
            }
        }
        return new HeifContainer(avif, source, topLevel, metaBox, meta, primary, items,
                locations, ilocFormat, properties, associations, tiff,
                xmp == null ? Xmp.empty() : xmp, writable);
    }

    /**
     * The items, those that describe the primary item first, so that its Exif and XMP are the
     * ones read and written rather than, say, a gain map's.
     */
    @NonNull
    private static List<Item> describingFirst(@NonNull List<Item> items, @NonNull byte[] meta,
            @NonNull IsoBoxes.Box root, long primary) {
        List<Long> describing = new ArrayList<>();
        IsoBoxes.Box iref = IsoBoxes.find(IsoBoxes.children(meta, root, 4), "iref");
        if (iref != null && iref.dataSize() >= 4) {
            int idSize = meta[(int) iref.data()] == 0 ? 2 : 4;
            for (IsoBoxes.Box reference : IsoBoxes.children(meta, iref, 4)) {
                int at = (int) reference.data();
                if (!"cdsc".equals(reference.type) || at + idSize * 2 + 2 > reference.end()) {
                    continue;
                }
                long from = Bytes.uint(meta, at, idSize);
                int count = Bytes.u16(meta, at + idSize);
                for (int i = 0; i < count && at + idSize + 2 + (i + 1) * idSize
                        <= reference.end(); i++) {
                    if (Bytes.uint(meta, at + idSize + 2 + i * idSize, idSize) == primary) {
                        describing.add(from);
                    }
                }
            }
        }
        List<Item> ordered = new ArrayList<>();
        for (Item item : items) {
            if (describing.contains(item.id)) {
                ordered.add(item);
            }
        }
        for (Item item : items) {
            if (!describing.contains(item.id)) {
                ordered.add(item);
            }
        }
        return ordered;
    }

    /** An item's data in a file, found the way a decoder finds it, for tests. */
    @VisibleForTesting
    @Nullable
    static byte[] itemData(@NonNull byte[] file, long id) throws IOException {
        ByteSource source = ByteSource.of(file);
        IsoBoxes.Box metaBox = IsoBoxes.find(IsoBoxes.children(source, 0, file.length), "meta");
        if (metaBox == null) {
            return null;
        }
        byte[] meta = source.bytes(metaBox.offset, metaBox.size);
        IsoBoxes.Box root = new IsoBoxes.Box("meta", 0, metaBox.header, meta.length, false);
        IsoBoxes.Box iloc = IsoBoxes.find(IsoBoxes.children(meta, root, 4), "iloc");
        Location location = iloc == null ? null
                : location(locations(meta, iloc, new int[5]), id);
        return location == null ? null : data(source, location, idat(meta, root));
    }

    /** The items an item info box lists. */
    @NonNull
    private static List<Item> items(@NonNull byte[] meta, @NonNull IsoBoxes.Box iinf) {
        List<Item> items = new ArrayList<>();
        int skip = meta[(int) iinf.data()] == 0 ? 6 : 8;
        for (IsoBoxes.Box infe : IsoBoxes.children(meta, iinf, skip)) {
            if (!"infe".equals(infe.type) || infe.dataSize() < 8) {
                continue;
            }
            int at = (int) infe.data();
            int end = (int) infe.end();
            int version = meta[at];
            if (version < 2) {
                continue;
            }
            long id;
            int position;
            if (version == 2) {
                id = Bytes.u16(meta, at + 4);
                position = at + 8;
            } else {
                id = Bytes.u32(meta, at + 4);
                position = at + 10;
            }
            if (position + 4 > end) {
                continue;
            }
            String type = Bytes.fourCc(meta, position);
            position += 4;
            position = Bytes.terminator(meta, position, end) + 1;
            String contentType = null;
            if ("mime".equals(type) && position < end) {
                contentType = new String(meta, position,
                        Bytes.terminator(meta, position, end) - position, Bytes.UTF_8).trim();
            }
            items.add(new Item(id, type, contentType));
        }
        return items;
    }

    /** The locations an item location box lists, and its format into the array given. */
    @NonNull
    private static List<Location> locations(@NonNull byte[] meta, @NonNull IsoBoxes.Box iloc,
            @NonNull int[] format) {
        List<Location> locations = new ArrayList<>();
        int at = (int) iloc.data();
        int end = (int) iloc.end();
        int version = meta[at];
        int offsetSize = Bytes.u8(meta, at + 4) >> 4;
        int lengthSize = Bytes.u8(meta, at + 4) & 0xF;
        int baseSize = Bytes.u8(meta, at + 5) >> 4;
        int indexSize = version >= 1 ? Bytes.u8(meta, at + 5) & 0xF : 0;
        format[0] = version;
        format[1] = offsetSize;
        format[2] = lengthSize;
        format[3] = baseSize;
        format[4] = indexSize;
        int position = at + 6;
        long count;
        if (version < 2) {
            count = Bytes.u16(meta, position);
            position += 2;
        } else {
            count = Bytes.u32(meta, position);
            position += 4;
        }
        for (long i = 0; i < count; i++) {
            int idSize = version < 2 ? 2 : 4;
            if (position + idSize + 2 > end) {
                break;
            }
            long id = Bytes.uint(meta, position, idSize);
            position += idSize;
            int construction = 0;
            if (version >= 1) {
                construction = Bytes.u16(meta, position) & 0xF;
                position += 2;
            }
            int dataReference = Bytes.u16(meta, position);
            position += 2;
            long base = Bytes.uint(meta, position, baseSize);
            position += baseSize;
            int extentCount = Bytes.u16(meta, position);
            position += 2;
            List<Extent> extents = new ArrayList<>();
            for (int e = 0; e < extentCount; e++) {
                long index = version >= 1 && indexSize > 0
                        ? Bytes.uint(meta, position, indexSize) : 0;
                position += version >= 1 ? indexSize : 0;
                long offset = Bytes.uint(meta, position, offsetSize);
                position += offsetSize;
                long length = Bytes.uint(meta, position, lengthSize);
                position += lengthSize;
                extents.add(new Extent(index, offset, length));
            }
            if (position > end) {
                break;
            }
            locations.add(new Location(id, construction, dataReference, base, extents));
        }
        return locations;
    }

    /** The property boxes, and each item's associations with them. */
    private static void readProperties(@NonNull byte[] meta, @NonNull IsoBoxes.Box iprp,
            @NonNull List<byte[]> properties, @NonNull List<long[]> associations) {
        for (IsoBoxes.Box child : IsoBoxes.children(meta, iprp, 0)) {
            if ("ipco".equals(child.type)) {
                for (IsoBoxes.Box property : IsoBoxes.children(meta, child, 0)) {
                    properties.add(IsoBoxes.bytes(meta, property));
                }
            } else if ("ipma".equals(child.type) && associations.isEmpty()) {
                int at = (int) child.data();
                int version = meta[at];
                boolean wide = (Bytes.u8(meta, at + 3) & 1) != 0;
                long count = Bytes.u32(meta, at + 4);
                int position = at + 8;
                for (long i = 0; i < count && position < child.end(); i++) {
                    long id = version < 1 ? Bytes.u16(meta, position) : Bytes.u32(meta, position);
                    position += version < 1 ? 2 : 4;
                    int n = Bytes.u8(meta, position++);
                    long[] entry = new long[n + 1];
                    entry[0] = id;
                    for (int a = 0; a < n; a++) {
                        int value;
                        if (wide) {
                            value = Bytes.u16(meta, position);
                            position += 2;
                            entry[a + 1] = ((value & 0x8000L) << 16) | (value & 0x7FFF);
                        } else {
                            value = Bytes.u8(meta, position++);
                            entry[a + 1] = ((value & 0x80L) << 24) | (value & 0x7F);
                        }
                    }
                    associations.add(entry);
                }
            }
        }
    }

    /** The meta box's item data box's contents, or an empty array. */
    @NonNull
    private static byte[] idat(@NonNull byte[] meta, @NonNull IsoBoxes.Box root) {
        IsoBoxes.Box idat = IsoBoxes.find(IsoBoxes.children(meta, root, 4), "idat");
        return idat == null ? new byte[0] : IsoBoxes.data(meta, idat);
    }

    @Nullable
    private static Location location(@NonNull List<Location> locations, long id) {
        for (Location location : locations) {
            if (location.id == id) {
                return location;
            }
        }
        return null;
    }

    /** An item's data, from the file or the item data box, or null if it cannot be read. */
    @Nullable
    private static byte[] data(@NonNull ByteSource source, @NonNull Location location,
            @NonNull byte[] idat) throws IOException {
        if (location.dataReference != 0 || location.construction > 1) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Extent extent : location.extents) {
            long start = location.base + extent.offset;
            long length = extent.length;
            if (location.construction == 1) {
                if (length == 0) {
                    length = idat.length - start;
                }
                if (start < 0 || start + length > idat.length || length > MAX_META) {
                    return null;
                }
                out.write(idat, (int) start, (int) length);
            } else {
                if (length == 0) {
                    length = source.length() - start;
                }
                if (start < 0 || start + length > source.length() || length > MAX_META) {
                    return null;
                }
                byte[] bytes = source.bytes(start, length);
                out.write(bytes, 0, bytes.length);
            }
        }
        return out.toByteArray();
    }

    @Override
    int format() {
        return mAvif ? MediaTags.FORMAT_AVIF : MediaTags.FORMAT_HEIF;
    }

    @Override
    int kind() {
        return MediaTags.KIND_IMAGE;
    }

    @Nullable
    @Override
    byte[] tiffBlock() {
        return mTiff;
    }

    @Override
    boolean canWriteExif() {
        return super.canWriteExif() && canRewrite();
    }

    /** Whether the meta box can be rewritten: the primary item known, its locations all read. */
    private boolean canRewrite() {
        return isWritable() && mPrimary >= 0 && !mItems.isEmpty() && primaryAssociations() != null;
    }

    @Nullable
    private long[] primaryAssociations() {
        for (long[] entry : mAssociations) {
            if (entry[0] == mPrimary) {
                return entry;
            }
        }
        return null;
    }

    /** The type of a property box, by its index from 1, or null. */
    @Nullable
    private String propertyType(long index) {
        int i = (int) (index & 0x7FFF) - 1;
        return i >= 0 && i < mProperties.size() ? Bytes.fourCc(mProperties.get(i), 4) : null;
    }

    @Override
    int orientation() {
        if (mNewOrientation != 0) {
            return mNewOrientation;
        }
        long[] entry = primaryAssociations();
        if (entry == null) {
            return 0;
        }
        // Each transform as a matrix on (x, y), x right and y down, applied in turn.
        int[] matrix = {1, 0, 0, 1};
        for (int a = 1; a < entry.length; a++) {
            String type = propertyType(entry[a]);
            byte[] property = type == null ? null : mProperties.get((int) (entry[a] & 0x7FFF) - 1);
            if ("irot".equals(type) && property.length >= 9) {
                int quarters = property[8] & 3;
                for (int q = 0; q < quarters; q++) {
                    matrix = multiply(new int[] {0, 1, -1, 0}, matrix);
                }
            } else if ("imir".equals(type) && property.length >= 9) {
                matrix = multiply((property[8] & 1) == 0 ? new int[] {-1, 0, 0, 1}
                        : new int[] {1, 0, 0, -1}, matrix);
            }
        }
        return exifOrientationOf(matrix);
    }

    /** The product of two 2x2 matrices, each {a, b, c, d}. */
    @NonNull
    private static int[] multiply(@NonNull int[] left, @NonNull int[] right) {
        return new int[] {left[0] * right[0] + left[1] * right[2],
                left[0] * right[1] + left[1] * right[3], left[2] * right[0] + left[3] * right[2],
                left[2] * right[1] + left[3] * right[3]};
    }

    /** The Exif orientation of a transform from stored to upright coordinates. */
    @VisibleForTesting
    static int exifOrientationOf(@NonNull int[] matrix) {
        for (int orientation = 1; orientation <= 8; orientation++) {
            if (Arrays.equals(matrix, matrixOf(orientation))) {
                return orientation;
            }
        }
        return 1;
    }

    /** The transform of an Exif orientation: mirrored first if it says, then turned. */
    @NonNull
    private static int[] matrixOf(int orientation) {
        int[] matrix = MediaTags.isMirrored(orientation) ? new int[] {-1, 0, 0, 1}
                : new int[] {1, 0, 0, 1};
        int quarters = MediaTags.rotationOf(orientation) / 90;
        for (int q = 0; q < quarters; q++) {
            // A clockwise quarter turn, with y down.
            matrix = multiply(new int[] {0, -1, 1, 0}, matrix);
        }
        return matrix;
    }

    @Override
    boolean keeps(@Field int field) {
        if (field == ORIENTATION) {
            return canRewrite();
        }
        return super.keeps(field);
    }

    @Override
    boolean setOrientation(int orientation) throws IOException {
        mNewOrientation = orientation;
        markDirty();
        // The Exif says the same, where it says anything.
        Exif exif = exif();
        if (exif != null && exif.getAttribute("Orientation") != null && canWriteExif()) {
            super.setOrientation(orientation);
        }
        return true;
    }

    @NonNull
    @Override
    Xmp xmp() {
        return mXmp;
    }

    @Override
    int maxXmpBytes() {
        return canRewrite() ? Integer.MAX_VALUE : NO_XMP;
    }

    @Override
    void xmpChanged() {
        super.xmpChanged();
        mXmpDirty = true;
    }

    @Override
    int gainMap() {
        for (Item item : mItems) {
            if ("tmap".equals(item.type)) {
                return MediaTags.GAIN_MAP_ISO_21496_1;
            }
        }
        for (long[] entry : mAssociations) {
            for (int a = 1; a < entry.length; a++) {
                if ("auxC".equals(propertyType(entry[a]))) {
                    byte[] property = mProperties.get((int) (entry[a] & 0x7FFF) - 1);
                    String type = new String(property, 12, Bytes.terminator(property, 12,
                            property.length) - 12, Bytes.UTF_8);
                    if (APPLE_GAIN_MAP.equals(type)) {
                        return MediaTags.GAIN_MAP_APPLE;
                    }
                }
            }
        }
        return JpegContainer.gainMapIn(mXmp);
    }

    @Nullable
    @Override
    MediaTags.MotionPhoto motionPhoto() {
        return mMotionPhoto;
    }

    // ---------- Writing ----------

    @Override
    void save(@NonNull File file) throws IOException {
        byte[] tiff = saveWrappedExif();
        byte[] exifPayload = tiff == null ? null
                : Bytes.concat(new byte[] {0, 0, 0, 6}, EXIF_IDENTIFIER, tiff);
        boolean hasXmpItem = false;
        for (Item item : mItems) {
            hasXmpItem |= "mime".equals(item.type) && XMP_TYPE.equals(item.contentType);
        }
        // An emptied packet is written as an empty packet, rather than unpicking its item.
        byte[] xmpPayload = mXmpDirty && (hasXmpItem || !mXmp.isEmpty()) ? mXmp.toBytes()
                : null;
        if (exifPayload == null && xmpPayload == null && mNewOrientation == 0) {
            saved();
            return;
        }
        List<Item> items = new ArrayList<>(mItems);
        List<Location> locations = new ArrayList<>(mLocations);
        List<long[]> associations = new ArrayList<>();
        for (long[] entry : mAssociations) {
            associations.add(entry.clone());
        }
        List<byte[]> properties = new ArrayList<>(mProperties);
        List<long[]> newReferences = new ArrayList<>();
        long exifId = -1;
        long xmpId = -1;
        long nextId = 1;
        for (Item item : items) {
            nextId = Math.max(nextId, item.id + 1);
            if ("Exif".equals(item.type) && exifId < 0) {
                exifId = item.id;
            } else if ("mime".equals(item.type) && XMP_TYPE.equals(item.contentType)
                    && xmpId < 0) {
                xmpId = item.id;
            }
        }
        List<byte[]> newInfe = new ArrayList<>();
        if (exifPayload != null && exifId < 0) {
            exifId = nextId++;
            items.add(new Item(exifId, "Exif", null));
            newInfe.add(infe(exifId, "Exif", null));
            newReferences.add(new long[] {exifId, mPrimary});
        }
        if (xmpPayload != null && xmpId < 0) {
            xmpId = nextId++;
            items.add(new Item(xmpId, "mime", XMP_TYPE));
            newInfe.add(infe(xmpId, "mime", XMP_TYPE));
            newReferences.add(new long[] {xmpId, mPrimary});
        }
        if (mNewOrientation != 0) {
            reorient(associations, properties, mNewOrientation);
        }
        // The new data goes in an mdat straight after the meta box.
        ByteArrayOutputStream added = new ByteArrayOutputStream();
        long[] relativeOffsets = new long[2];
        if (exifPayload != null) {
            relativeOffsets[0] = added.size();
            added.write(exifPayload, 0, exifPayload.length);
        }
        if (xmpPayload != null) {
            relativeOffsets[1] = added.size();
            added.write(xmpPayload, 0, xmpPayload.length);
        }
        byte[] mdat = added.size() == 0 ? new byte[0] : IsoBoxes.box("mdat", added.toByteArray());
        long metaEnd = mMetaBox.end();
        // The new meta box's size and the offsets in it depend on each other; two rounds settle
        // it, since only the offset field size can change between them.
        byte[] meta = null;
        long newMetaSize = mMeta.length;
        for (int round = 0; round < 3; round++) {
            long shift = newMetaSize - mMetaBox.size + mdat.length;
            long dataStart = mMetaBox.offset + newMetaSize + 8;
            List<Location> placed = new ArrayList<>();
            for (Location location : locations) {
                if (location.id == exifId && exifPayload != null) {
                    placed.add(single(exifId, dataStart + relativeOffsets[0], exifPayload.length));
                } else if (location.id == xmpId && xmpPayload != null) {
                    placed.add(single(xmpId, dataStart + relativeOffsets[1], xmpPayload.length));
                } else {
                    placed.add(shifted(location, metaEnd, shift));
                }
            }
            if (exifPayload != null && location(locations, exifId) == null) {
                placed.add(single(exifId, dataStart + relativeOffsets[0], exifPayload.length));
            }
            if (xmpPayload != null && location(locations, xmpId) == null) {
                placed.add(single(xmpId, dataStart + relativeOffsets[1], xmpPayload.length));
            }
            meta = meta(placed, newInfe, newReferences, properties, associations);
            if (meta.length == newMetaSize) {
                break;
            }
            newMetaSize = meta.length;
        }
        final byte[] newMeta = meta;
        final long shift = newMeta.length - mMetaBox.size + mdat.length;
        final byte[] newMdat = mdat;
        try (ByteSource source = ByteSource.of(file)) {
            MediaFiles.replace(file, out -> {
                MediaFiles.copy(source, 0, mMetaBox.offset, out);
                out.write(newMeta);
                out.write(newMdat);
                long at = metaEnd;
                for (IsoBoxes.Box box : mTopLevel) {
                    if (box.offset < metaEnd) {
                        continue;
                    }
                    if ("moov".equals(box.type) && shift != 0 && box.size <= MAX_META) {
                        out.write(shiftChunkOffsets(source.bytes(box.offset, box.size), metaEnd,
                                shift));
                    } else {
                        MediaFiles.copy(source, box.offset, box.end(), out);
                    }
                    at = box.end();
                }
                MediaFiles.copy(source, at, source.length(), out);
            });
        }
        mXmpDirty = false;
        mNewOrientation = 0;
        saved();
    }

    /** A location of one extent in the file, at an absolute offset. */
    @NonNull
    private static Location single(long id, long offset, long length) {
        List<Extent> extents = new ArrayList<>();
        extents.add(new Extent(0, offset, length));
        return new Location(id, 0, 0, 0, extents);
    }

    /** A location moved along, if its data is in the file after the meta box. */
    @NonNull
    private static Location shifted(@NonNull Location location, long metaEnd, long shift) {
        if (location.construction != 0 || location.dataReference != 0 || shift == 0) {
            return location;
        }
        List<Extent> extents = new ArrayList<>();
        for (Extent extent : location.extents) {
            long absolute = location.base + extent.offset;
            extents.add(new Extent(extent.index, absolute >= metaEnd ? absolute + shift
                    : absolute, extent.length));
        }
        return new Location(location.id, 0, 0, 0, extents);
    }

    /**
     * Takes every rotation and mirror off the primary item and gives it new ones for an
     * orientation: mirrored first, then turned, which is the order they are applied in.
     */
    private void reorient(@NonNull List<long[]> associations, @NonNull List<byte[]> properties,
            int orientation) {
        for (int i = 0; i < associations.size(); i++) {
            long[] entry = associations.get(i);
            if (entry[0] != mPrimary) {
                continue;
            }
            List<Long> kept = new ArrayList<>();
            for (int a = 1; a < entry.length; a++) {
                String type = propertyType(entry[a]);
                if (!"irot".equals(type) && !"imir".equals(type)) {
                    kept.add(entry[a]);
                }
            }
            if (MediaTags.isMirrored(orientation)) {
                properties.add(IsoBoxes.box("imir", new byte[] {0}));
                kept.add(0x80000000L | properties.size());
            }
            int rotation = MediaTags.rotationOf(orientation);
            if (rotation != 0) {
                // irot turns anticlockwise.
                properties.add(IsoBoxes.box("irot", new byte[] {(byte) ((360 - rotation) / 90)}));
                kept.add(0x80000000L | properties.size());
            }
            long[] updated = new long[kept.size() + 1];
            updated[0] = entry[0];
            for (int k = 0; k < kept.size(); k++) {
                updated[k + 1] = kept.get(k);
            }
            associations.set(i, updated);
        }
    }

    /** An item info entry, version 2 or 3 as the id needs. */
    @NonNull
    private static byte[] infe(long id, @NonNull String type, @Nullable String contentType) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        boolean wide = id > 0xFFFF;
        body.write(wide ? 3 : 2);
        body.write(0);
        body.write(0);
        body.write(0);
        byte[] number = new byte[wide ? 4 : 2];
        Bytes.putUint(number, 0, number.length, id);
        body.write(number, 0, number.length);
        body.write(0);
        body.write(0);
        body.write(Bytes.fourCc(type), 0, 4);
        body.write(0);
        if (contentType != null) {
            byte[] content = contentType.getBytes(Bytes.UTF_8);
            body.write(content, 0, content.length);
            body.write(0);
        }
        return IsoBoxes.box("infe", body.toByteArray());
    }

    /** The meta box with new locations, items, references and properties. */
    @NonNull
    private byte[] meta(@NonNull List<Location> locations, @NonNull List<byte[]> newInfe,
            @NonNull List<long[]> newReferences, @NonNull List<byte[]> properties,
            @NonNull List<long[]> associations) {
        IsoBoxes.Box root = new IsoBoxes.Box("meta", 0, mMetaBox.header, mMeta.length, false);
        List<byte[]> children = new ArrayList<>();
        children.add(Arrays.copyOfRange(mMeta, mMetaBox.header, mMetaBox.header + 4));
        boolean referenced = false;
        for (IsoBoxes.Box child : IsoBoxes.children(mMeta, root, 4)) {
            switch (child.type) {
                case "iloc":
                    children.add(iloc(locations));
                    break;
                case "iinf":
                    children.add(iinf(child, newInfe));
                    break;
                case "iref":
                    children.add(iref(child, newReferences));
                    referenced = true;
                    break;
                case "iprp":
                    children.add(iprp(child, properties, associations));
                    break;
                default:
                    children.add(IsoBoxes.bytes(mMeta, child));
            }
        }
        if (!referenced && !newReferences.isEmpty()) {
            children.add(iref(null, newReferences));
        }
        return IsoBoxes.box("meta", children);
    }

    /** An item location box, in the version and sizes the locations need. */
    @NonNull
    private byte[] iloc(@NonNull List<Location> locations) {
        int version = mIlocFormat[0];
        long largestId = 0;
        long largestOffset = 0;
        long largestLength = 0;
        long largestBase = 0;
        for (Location location : locations) {
            largestId = Math.max(largestId, location.id);
            largestBase = Math.max(largestBase, location.base);
            if (location.construction != 0) {
                version = Math.max(version, 1);
            }
            for (Extent extent : location.extents) {
                largestOffset = Math.max(largestOffset, extent.offset);
                largestLength = Math.max(largestLength, extent.length);
            }
        }
        if (largestId > 0xFFFF) {
            version = 2;
        }
        int offsetSize = Math.max(mIlocFormat[1], largestOffset > 0xFFFFFFFFL ? 8 : 4);
        int lengthSize = Math.max(mIlocFormat[2], largestLength > 0xFFFFFFFFL ? 8 : 4);
        int baseSize = largestBase == 0 ? mIlocFormat[3]
                : Math.max(mIlocFormat[3], largestBase > 0xFFFFFFFFL ? 8 : 4);
        int indexSize = version >= 1 ? mIlocFormat[4] : 0;
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(version);
        body.write(0);
        body.write(0);
        body.write(0);
        body.write((offsetSize << 4) | lengthSize);
        body.write((baseSize << 4) | indexSize);
        writeUint(body, version < 2 ? 2 : 4, locations.size());
        for (Location location : locations) {
            writeUint(body, version < 2 ? 2 : 4, location.id);
            if (version >= 1) {
                writeUint(body, 2, location.construction);
            }
            writeUint(body, 2, location.dataReference);
            writeUint(body, baseSize, location.base);
            writeUint(body, 2, location.extents.size());
            for (Extent extent : location.extents) {
                if (version >= 1 && indexSize > 0) {
                    writeUint(body, indexSize, extent.index);
                }
                writeUint(body, offsetSize, extent.offset);
                writeUint(body, lengthSize, extent.length);
            }
        }
        return IsoBoxes.box("iloc", body.toByteArray());
    }

    private static void writeUint(@NonNull ByteArrayOutputStream out, int size, long value) {
        byte[] bytes = new byte[size];
        Bytes.putUint(bytes, 0, size, value);
        out.write(bytes, 0, size);
    }

    /** The item info box with new entries added. */
    @NonNull
    private byte[] iinf(@NonNull IsoBoxes.Box iinf, @NonNull List<byte[]> added) {
        int at = (int) iinf.data();
        int version = mMeta[at];
        int skip = version == 0 ? 6 : 8;
        List<byte[]> entries = new ArrayList<>();
        for (IsoBoxes.Box infe : IsoBoxes.children(mMeta, iinf, skip)) {
            entries.add(IsoBoxes.bytes(mMeta, infe));
        }
        entries.addAll(added);
        if (entries.size() > 0xFFFF) {
            version = 1;
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(version);
        body.write(mMeta, at + 1, 3);
        writeUint(body, version == 0 ? 2 : 4, entries.size());
        for (byte[] entry : entries) {
            body.write(entry, 0, entry.length);
        }
        return IsoBoxes.box("iinf", body.toByteArray());
    }

    /** The item reference box with "content describes" references added. */
    @NonNull
    private byte[] iref(@Nullable IsoBoxes.Box iref, @NonNull List<long[]> added) {
        int version = iref == null ? 0 : mMeta[(int) iref.data()];
        for (long[] reference : added) {
            if (reference[0] > 0xFFFF || reference[1] > 0xFFFF) {
                version = 1;
            }
        }
        if (iref != null && version != mMeta[(int) iref.data()]) {
            // Widening every existing reference is not worth the risk; ids this large are rare.
            version = mMeta[(int) iref.data()];
        }
        int idSize = version == 0 ? 2 : 4;
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(version);
        body.write(0);
        body.write(0);
        body.write(0);
        if (iref != null) {
            for (IsoBoxes.Box reference : IsoBoxes.children(mMeta, iref, 4)) {
                byte[] bytes = IsoBoxes.bytes(mMeta, reference);
                body.write(bytes, 0, bytes.length);
            }
        }
        for (long[] reference : added) {
            ByteArrayOutputStream box = new ByteArrayOutputStream();
            writeUint(box, idSize, reference[0]);
            writeUint(box, 2, 1);
            writeUint(box, idSize, reference[1]);
            byte[] cdsc = IsoBoxes.box("cdsc", box.toByteArray());
            body.write(cdsc, 0, cdsc.length);
        }
        return IsoBoxes.box("iref", body.toByteArray());
    }

    /** The item properties box with these properties and associations. */
    @NonNull
    private byte[] iprp(@NonNull IsoBoxes.Box iprp, @NonNull List<byte[]> properties,
            @NonNull List<long[]> associations) {
        List<byte[]> children = new ArrayList<>();
        boolean associated = false;
        for (IsoBoxes.Box child : IsoBoxes.children(mMeta, iprp, 0)) {
            if ("ipco".equals(child.type)) {
                children.add(IsoBoxes.box("ipco", properties));
            } else if ("ipma".equals(child.type) && !associated) {
                children.add(ipma(child, associations, properties.size()));
                associated = true;
            } else {
                children.add(IsoBoxes.bytes(mMeta, child));
            }
        }
        return IsoBoxes.box("iprp", children);
    }

    /** An item property association box, wide enough for every property index. */
    @NonNull
    private byte[] ipma(@NonNull IsoBoxes.Box ipma, @NonNull List<long[]> associations,
            int propertyCount) {
        int at = (int) ipma.data();
        int version = mMeta[at];
        boolean wide = (Bytes.u8(mMeta, at + 3) & 1) != 0 || propertyCount > 0x7F;
        for (long[] entry : associations) {
            if (entry[0] > 0xFFFF) {
                version = 1;
            }
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(version);
        body.write(mMeta, at + 1, 2);
        body.write((Bytes.u8(mMeta, at + 3) & ~1) | (wide ? 1 : 0));
        writeUint(body, 4, associations.size());
        for (long[] entry : associations) {
            writeUint(body, version < 1 ? 2 : 4, entry[0]);
            body.write(entry.length - 1);
            for (int a = 1; a < entry.length; a++) {
                boolean essential = (entry[a] & 0x80000000L) != 0;
                long index = entry[a] & 0x7FFF;
                if (wide) {
                    writeUint(body, 2, (essential ? 0x8000 : 0) | index);
                } else {
                    body.write((int) ((essential ? 0x80 : 0) | index));
                }
            }
        }
        return IsoBoxes.box("ipma", body.toByteArray());
    }

    /** A moov box with every chunk offset past a point moved along. */
    @VisibleForTesting
    @NonNull
    static byte[] shiftChunkOffsets(@NonNull byte[] moov, long after, long shift) {
        byte[] shifted = moov.clone();
        shiftIn(shifted, new IsoBoxes.Box("moov", 0, 8, moov.length, false), after, shift);
        return shifted;
    }

    private static void shiftIn(@NonNull byte[] data, @NonNull IsoBoxes.Box parent, long after,
            long shift) {
        for (IsoBoxes.Box child : IsoBoxes.children(data, parent, 0)) {
            int at = (int) child.data();
            if ("stco".equals(child.type) && child.dataSize() >= 8) {
                long count = Bytes.u32(data, at + 4);
                for (long i = 0; i < count && at + 8 + i * 4 + 4 <= child.end(); i++) {
                    int position = (int) (at + 8 + i * 4);
                    long offset = Bytes.u32(data, position);
                    if (offset >= after) {
                        Bytes.put32(data, position, offset + shift);
                    }
                }
            } else if ("co64".equals(child.type) && child.dataSize() >= 8) {
                long count = Bytes.u32(data, at + 4);
                for (long i = 0; i < count && at + 8 + i * 8 + 8 <= child.end(); i++) {
                    int position = (int) (at + 8 + i * 8);
                    long offset = Bytes.u64(data, position);
                    if (offset >= after) {
                        Bytes.put64(data, position, offset + shift);
                    }
                }
            } else if ("trak".equals(child.type) || "mdia".equals(child.type)
                    || "minf".equals(child.type) || "stbl".equals(child.type)) {
                shiftIn(data, child, after, shift);
            }
        }
    }
}
