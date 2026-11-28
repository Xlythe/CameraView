package com.xlythe.view.camera;

import android.content.ContentResolver;
import android.content.Context;
import android.location.Location;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;
import androidx.exifinterface.media.ExifInterface;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The metadata of a picture, video or sound file, read and written the same way whatever its
 * format: when and where it was captured, by what and by whom, what it is called, which way up
 * it goes, and what a panorama, motion photo, HDR picture or 360° video needs said about it.
 *
 * <pre>{@code
 * MediaTags tags = MediaTags.open(file);
 * tags.setTitle("Birthday");
 * tags.setLocation(location);
 * tags.save();
 * }</pre>
 *
 * <p>The format is told from the file's first bytes, never its name. Pictures can be JPEG, PNG,
 * WebP, HEIF or AVIF, and DNG, TIFF or a camera's other raw formats to read; video can be MP4,
 * QuickTime or 3GP, and Matroska or WebM to read; sound can be M4A, MP3, WAV, FLAC, Ogg Vorbis or
 * Opus. A GIF says only whether it is animated. Any other file opens with no fields.</p>
 *
 * <p>A file opened from a content Uri is written where the app may write that Uri: a document it
 * was granted write access to, or, from Android 10, a MediaStore item it inserted itself. Every
 * setter returns false for one it may only read. A fragmented MP4 whose fragments locate their
 * data from the start of the file, rather than from themselves, is read only, since growing its
 * metadata would move them.</p>
 *
 * <h2>Where each field goes</h2>
 * <p>A field is written where the format itself keeps it, since that is where galleries look:
 * Exif in a picture, PNG's own text for its title, author, description, copyright and software,
 * QuickTime metadata in a video, and a sound format's tags. A field the format has no place for
 * goes in the file's XMP, where the format has XMP. A field that cannot be saved either way
 * makes its setter return false, with nothing changed. If the file already carries a field in
 * XMP as well as natively, the XMP copy is changed to match.</p>
 *
 * <p>When a field is both in the format's own metadata and in XMP and the two disagree, the
 * format's own is the one read: it is what the camera wrote, and what most galleries show.</p>
 *
 * <p>Changes are kept until {@link #save()} writes them all at once. Opening reads only the
 * file's metadata, and saving rewrites only what holds it where it can: a video's samples never
 * move.</p>
 */
public final class MediaTags {
    private static final String TAG = "MediaTags";

    /** What a file is, told from its first bytes. */
    @IntDef({FORMAT_UNKNOWN, FORMAT_JPEG, FORMAT_PNG, FORMAT_WEBP, FORMAT_HEIF, FORMAT_AVIF,
            FORMAT_DNG, FORMAT_TIFF, FORMAT_GIF, FORMAT_MP4, FORMAT_QUICKTIME, FORMAT_3GP,
            FORMAT_M4A, FORMAT_MATROSKA, FORMAT_MP3, FORMAT_WAV, FORMAT_FLAC, FORMAT_OGG,
            FORMAT_OPUS, FORMAT_AAC, FORMAT_AMR})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Format {}

    /** A file whose format this does not know. It has no fields. */
    public static final int FORMAT_UNKNOWN = 0;
    public static final int FORMAT_JPEG = 1;
    public static final int FORMAT_PNG = 2;
    public static final int FORMAT_WEBP = 3;
    /** A HEIF picture, such as a HEIC. */
    public static final int FORMAT_HEIF = 4;
    public static final int FORMAT_AVIF = 5;
    /** A DNG raw picture, which is read but not written. */
    public static final int FORMAT_DNG = 6;
    /** A TIFF, or a raw picture built on TIFF, which is read but not written. */
    public static final int FORMAT_TIFF = 7;
    /** A GIF, which has no fields. */
    public static final int FORMAT_GIF = 8;
    public static final int FORMAT_MP4 = 9;
    /** A QuickTime movie, such as a MOV. */
    public static final int FORMAT_QUICKTIME = 10;
    public static final int FORMAT_3GP = 11;
    /** An MP4 of sound alone, such as an M4A. */
    public static final int FORMAT_M4A = 12;
    /** A Matroska or WebM file, which has no fields. */
    public static final int FORMAT_MATROSKA = 13;
    public static final int FORMAT_MP3 = 14;
    public static final int FORMAT_WAV = 15;
    public static final int FORMAT_FLAC = 16;
    /** An Ogg file of Vorbis, FLAC or other sound. */
    public static final int FORMAT_OGG = 17;
    /** An Ogg file of Opus. */
    public static final int FORMAT_OPUS = 18;
    /** Bare AAC, which has no fields beyond an ID3 tag. */
    public static final int FORMAT_AAC = 19;
    /** AMR speech, which has no fields. */
    public static final int FORMAT_AMR = 20;

    /** Whether a file holds pictures, video or sound. */
    @IntDef({KIND_UNKNOWN, KIND_IMAGE, KIND_VIDEO, KIND_AUDIO})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Kind {}

    public static final int KIND_UNKNOWN = 0;
    /** A still picture, which may carry a motion photo's clip as well. */
    public static final int KIND_IMAGE = 1;
    /** A video, with or without sound. */
    public static final int KIND_VIDEO = 2;
    /** Sound alone. */
    public static final int KIND_AUDIO = 3;

    /** The kind of gain map an HDR picture carries. */
    @IntDef({GAIN_MAP_NONE, GAIN_MAP_ULTRA_HDR, GAIN_MAP_ISO_21496_1, GAIN_MAP_APPLE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface GainMap {}

    /** No gain map: the picture is standard dynamic range. */
    public static final int GAIN_MAP_NONE = 0;
    /**
     * Ultra HDR's, as Android's cameras write. A picture with ISO 21496-1's metadata as well,
     * as Ultra HDR 1.1 writes, reports this.
     */
    public static final int GAIN_MAP_ULTRA_HDR = 1;
    /** ISO 21496-1's, alone. */
    public static final int GAIN_MAP_ISO_21496_1 = 2;
    /** Apple's, as an iPhone writes. */
    public static final int GAIN_MAP_APPLE = 3;

    /** How a video's brightness is encoded. */
    @IntDef({TRANSFER_UNKNOWN, TRANSFER_SDR, TRANSFER_HLG, TRANSFER_PQ})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Transfer {}

    /** Not said, or not a video. */
    public static final int TRANSFER_UNKNOWN = 0;
    /** Standard dynamic range. */
    public static final int TRANSFER_SDR = 1;
    /** HDR, as hybrid log-gamma. */
    public static final int TRANSFER_HLG = 2;
    /** HDR, as the perceptual quantizer of HDR10 and Dolby Vision. */
    public static final int TRANSFER_PQ = 3;

    /** The namespaces {@link #copyTo} leaves out: they describe data the copy does not have. */
    private static final Set<String> NOT_COPIED = new HashSet<>(Arrays.asList(Xmp.NS_CAMERA,
            Xmp.NS_CONTAINER, Xmp.NS_ITEM, Xmp.NS_HDR_GAIN_MAP, Xmp.NS_APPLE_GAIN_MAP,
            Xmp.NS_APPLE_PIXEL_DATA, Xmp.NS_XMP_NOTE));

    /** The Exif tags that describe how a picture's own bytes are laid out, never copied. */
    private static final String[] LAYOUT_TAGS = {ExifInterface.TAG_IMAGE_WIDTH,
            ExifInterface.TAG_IMAGE_LENGTH, ExifInterface.TAG_PIXEL_X_DIMENSION,
            ExifInterface.TAG_PIXEL_Y_DIMENSION, ExifInterface.TAG_BITS_PER_SAMPLE,
            ExifInterface.TAG_COMPRESSION, ExifInterface.TAG_PHOTOMETRIC_INTERPRETATION,
            ExifInterface.TAG_STRIP_OFFSETS, ExifInterface.TAG_STRIP_BYTE_COUNTS,
            ExifInterface.TAG_ROWS_PER_STRIP, ExifInterface.TAG_SAMPLES_PER_PIXEL,
            ExifInterface.TAG_PLANAR_CONFIGURATION, ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT,
            ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT_LENGTH,
            ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH, ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH,
            ExifInterface.TAG_NEW_SUBFILE_TYPE, ExifInterface.TAG_SUBFILE_TYPE,
            ExifInterface.TAG_Y_CB_CR_SUB_SAMPLING, ExifInterface.TAG_DEFAULT_CROP_SIZE,
            ExifInterface.TAG_ORF_ASPECT_FRAME, ExifInterface.TAG_ORF_PREVIEW_IMAGE_LENGTH,
            ExifInterface.TAG_ORF_PREVIEW_IMAGE_START, ExifInterface.TAG_ORF_THUMBNAIL_IMAGE,
            ExifInterface.TAG_RW2_JPG_FROM_RAW, ExifInterface.TAG_XMP};

    /** An XML name, which a property's name must be. */
    private static final Pattern XML_NAME = Pattern.compile("[A-Za-z_][\\w.-]*");

    /** XMP's GPS coordinate: degrees, decimal minutes or minutes and seconds, a direction. */
    private static final Pattern XMP_COORDINATE = Pattern.compile(
            "\\s*(\\d+)\\s*,\\s*(\\d+(?:\\.\\d+)?)(?:\\s*,\\s*(\\d+(?:\\.\\d+)?))?"
                    + "\\s*([NSEW])\\s*");

    /** Opens a file again from its start, for readers that need a stream of their own. */
    interface StreamOpener {
        @WorkerThread
        @NonNull
        InputStream open() throws IOException;
    }

    /** Where copies of content to edit are kept, under the app's cache. */
    private static final String EDIT_DIRECTORY = "cameraview-tags";

    /** How old a copy left behind by an edit that was never saved is before it is deleted. */
    private static final long STALE_EDIT_MILLIS = 24L * 60 * 60 * 1000;

    /** The formats this writes, for answering whether a Uri not yet edited is read only. */
    private static final Set<Integer> WRITTEN_FORMATS = new HashSet<>(Arrays.asList(
            FORMAT_JPEG, FORMAT_PNG, FORMAT_WEBP, FORMAT_HEIF, FORMAT_AVIF, FORMAT_MP4,
            FORMAT_QUICKTIME, FORMAT_3GP, FORMAT_M4A, FORMAT_MP3, FORMAT_WAV, FORMAT_FLAC,
            FORMAT_OGG, FORMAT_OPUS));

    /**
     * The file edits are made in: the file itself, a copy of a Uri's content once an edit
     * begins, or null while nothing can be or has been edited.
     */
    @Nullable
    private File mFile;

    /** Opens the content again, for a reader that wants its own stream. */
    @Nullable
    private final StreamOpener mOpener;

    /** The app's context, for a content Uri, or null. */
    @Nullable
    private final Context mContext;

    /** The content Uri, or null for a file. */
    @Nullable
    private final Uri mUri;

    /** Whether the content Uri can be written: null until asked. */
    @Nullable
    private Boolean mUriWritable;

    /** The format's own metadata. */
    @NonNull
    private MediaContainer mContainer;

    private MediaTags(@Nullable File file, @Nullable StreamOpener opener,
            @NonNull MediaContainer container) {
        this(file, opener, null, null, container);
    }

    private MediaTags(@Nullable File file, @Nullable StreamOpener opener,
            @Nullable Context context, @Nullable Uri uri, @NonNull MediaContainer container) {
        mFile = file;
        mOpener = opener;
        mContext = context;
        mUri = uri;
        mContainer = container;
    }

    /**
     * Opens a file's metadata, to read and write.
     *
     * @throws IOException if the file cannot be read, or its metadata is malformed beyond reading
     */
    @WorkerThread
    @NonNull
    public static MediaTags open(@NonNull File file) throws IOException {
        StreamOpener opener = () -> new FileInputStream(file);
        // Everything is read while opening, so nothing holds the file open afterwards.
        try (ByteSource source = ByteSource.of(file)) {
            return new MediaTags(file, opener, read(source, file, opener));
        } catch (RuntimeException e) {
            throw new IOException("Unable to read the metadata of " + file, e);
        }
    }

    /**
     * Opens the metadata of a file behind a Uri, such as one in the gallery. It can be written
     * where the app may write the Uri: a file, a document it was granted write access to, or,
     * from Android 10, a MediaStore item it created. For any other, every setter returns false.
     *
     * @throws IOException if the Uri cannot be read, or its metadata is malformed beyond reading
     */
    @WorkerThread
    @NonNull
    public static MediaTags open(@NonNull Context context, @NonNull Uri uri)
            throws IOException {
        if (ContentResolver.SCHEME_FILE.equals(uri.getScheme()) && uri.getPath() != null) {
            return open(new File(uri.getPath()));
        }
        final ContentResolver resolver = context.getContentResolver();
        StreamOpener opener = () -> {
            InputStream stream = resolver.openInputStream(uri);
            if (stream == null) {
                throw new FileNotFoundException("Nothing to read at " + uri);
            }
            return stream;
        };
        try (ByteSource source = ByteSource.of(opener.open())) {
            return new MediaTags(null, opener, context.getApplicationContext(), uri,
                    read(source, null, opener));
        } catch (RuntimeException e) {
            throw new IOException("Unable to read the metadata of " + uri, e);
        }
    }

    /** Reads bytes in memory, which can only be read, for tests and other readers. */
    @VisibleForTesting
    @WorkerThread
    @NonNull
    static MediaTags read(@NonNull byte[] bytes) throws IOException {
        StreamOpener opener = () -> new ByteArrayInputStream(bytes);
        return new MediaTags(null, opener, read(ByteSource.of(bytes), null, opener));
    }

    /**
     * The container for a file, chosen by its first bytes, told where the file is for the
     * temporary copies its writes may need.
     */
    @WorkerThread
    @NonNull
    private static MediaContainer read(@NonNull ByteSource source, @Nullable File file,
            @Nullable StreamOpener opener) throws IOException {
        MediaContainer container = container(source, file, opener);
        if (file != null) {
            container.setFolder(file.getAbsoluteFile().getParentFile());
        }
        return container;
    }

    /** The container for a file, chosen by its first bytes. */
    @WorkerThread
    @NonNull
    private static MediaContainer container(@NonNull ByteSource source, @Nullable File file,
            @Nullable StreamOpener opener) throws IOException {
        byte[] head = source.bytesUpTo(0, 64);
        boolean writable = file != null && file.canWrite();
        int format = detect(head);
        switch (format) {
            case FORMAT_JPEG:
                return JpegContainer.read(source, writable ? file : null);
            case FORMAT_PNG:
                return PngContainer.read(source, writable);
            case FORMAT_WEBP:
                return WebpContainer.read(source, writable);
            case FORMAT_TIFF:
                return TiffContainer.read(source, file, opener);
            case FORMAT_HEIF:
                if (Bytes.startsWith(head, 8, "mif3")) {
                    // The compact form of HEIF, with no meta box to read.
                    return new PlainContainer(FORMAT_HEIF, KIND_IMAGE);
                }
                return HeifContainer.read(source, writable);
            case FORMAT_MP4:
                if (Bytes.startsWith(head, 8, "crx ")) {
                    // Canon's raw pictures, which are boxes but not a movie.
                    return new PlainContainer(FORMAT_UNKNOWN, KIND_IMAGE);
                }
                return Mp4Container.read(source, writable);
            case FORMAT_MP3:
            case FORMAT_AAC: {
                long tag = Id3Container.tagSize(head);
                // FLAC is sometimes given an ID3 tag in front of it.
                if (tag > 0 && Bytes.startsWith(source.bytesUpTo(tag, 4), 0, "fLaC")) {
                    return FlacContainer.read(source, writable);
                }
                return tag > 0 || format == FORMAT_MP3 ? Id3Container.read(source, writable)
                        : new PlainContainer(format);
            }
            case FORMAT_WAV:
                return WavContainer.read(source, writable);
            case FORMAT_FLAC:
                return FlacContainer.read(source, writable);
            case FORMAT_OGG:
                return OggContainer.read(source, writable);
            case FORMAT_MATROSKA:
                return MatroskaContainer.read(source);
            case FORMAT_GIF:
                return GifContainer.read(source);
            default:
                return new PlainContainer(format);
        }
    }

    /**
     * What a file is, from its first bytes. ISO files, MP4 and HEIF alike, answer
     * {@link #FORMAT_MP4} or {@link #FORMAT_HEIF} here and are told apart when read; TIFF-based
     * files answer {@link #FORMAT_TIFF}.
     */
    @VisibleForTesting
    @Format
    static int detect(@NonNull byte[] head) {
        if (head.length >= 3 && Bytes.u8(head, 0) == 0xFF && Bytes.u8(head, 1) == 0xD8
                && Bytes.u8(head, 2) == 0xFF) {
            return FORMAT_JPEG;
        }
        if (Bytes.startsWith(head, 0, PngContainer.SIGNATURE)) {
            return FORMAT_PNG;
        }
        if (Bytes.startsWith(head, 0, "RIFF") && Bytes.startsWith(head, 8, "WEBP")) {
            return FORMAT_WEBP;
        }
        if ((Bytes.startsWith(head, 0, "RIFF") || Bytes.startsWith(head, 0, "RF64")
                || Bytes.startsWith(head, 0, "BW64")) && Bytes.startsWith(head, 8, "WAVE")) {
            return FORMAT_WAV;
        }
        if (TiffContainer.isRawHeader(head)) {
            return FORMAT_TIFF;
        }
        if (head.length >= 12 && Bytes.startsWith(head, 4, "ftyp")) {
            String brand = Bytes.fourCc(head, 8);
            return isHeifBrand(head) ? FORMAT_HEIF : brand.isEmpty() ? FORMAT_UNKNOWN
                    : FORMAT_MP4;
        }
        if (head.length >= 8 && (Bytes.startsWith(head, 4, "moov")
                || Bytes.startsWith(head, 4, "mdat") || Bytes.startsWith(head, 4, "wide")
                || Bytes.startsWith(head, 4, "free") || Bytes.startsWith(head, 4, "skip"))) {
            // A QuickTime movie from before brands.
            return FORMAT_MP4;
        }
        if (Bytes.startsWith(head, 0, "GIF87a") || Bytes.startsWith(head, 0, "GIF89a")) {
            return FORMAT_GIF;
        }
        if (Bytes.startsWith(head, 0, "fLaC")) {
            return FORMAT_FLAC;
        }
        if (Bytes.startsWith(head, 0, "OggS")) {
            return FORMAT_OGG;
        }
        if (Bytes.startsWith(head, 0, "ID3")) {
            // MP3 most often; what follows the tag says for certain once it is read.
            return FORMAT_MP3;
        }
        if (Bytes.startsWith(head, 0, "#!AMR")) {
            return FORMAT_AMR;
        }
        if (head.length >= 4 && Bytes.u32(head, 0) == 0x1A45DFA3L) {
            return FORMAT_MATROSKA;
        }
        if (head.length >= 2 && Bytes.u8(head, 0) == 0xFF && (Bytes.u8(head, 1) & 0xF6) == 0xF0) {
            return FORMAT_AAC;
        }
        if (head.length >= 2 && Bytes.u8(head, 0) == 0xFF && (Bytes.u8(head, 1) & 0xE0) == 0xE0) {
            return FORMAT_MP3;
        }
        return FORMAT_UNKNOWN;
    }

    /** Whether an ftyp names HEIF or AVIF among its brands, as a still picture does. */
    private static boolean isHeifBrand(@NonNull byte[] head) {
        long size = Math.min(head.length, Bytes.u32(head, 0));
        for (int at = 8; at + 4 <= size; at += 4) {
            if (at == 12) {
                // The minor version, not a brand.
                continue;
            }
            String brand = Bytes.fourCc(head, at);
            if ("heic".equals(brand) || "heix".equals(brand) || "mif1".equals(brand)
                    || "avif".equals(brand) || "heim".equals(brand) || "heis".equals(brand)
                    || "mif2".equals(brand) || "mif3".equals(brand)) {
                return true;
            }
        }
        return false;
    }

    // ---------- What the file is ----------

    /** What the file is, told from its first bytes. */
    @AnyThread
    @Format
    public int getFormat() {
        return mContainer.format();
    }

    /** Whether the file holds pictures, video or sound. */
    @AnyThread
    @Kind
    public int getKind() {
        return mContainer.kind();
    }

    /**
     * Whether nothing can be written, because of the file's format or how it was opened. For a
     * content Uri, this asks its provider whether the app may write it.
     */
    @WorkerThread
    public boolean isReadOnly() {
        if (mFile == null) {
            return mUri == null || !WRITTEN_FORMATS.contains(getFormat()) || !uriWritable();
        }
        return !(mContainer.maxXmpBytes() > 0 || mContainer.canWriteExif() || anyNative());
    }

    /**
     * Whether an edit can be made, beginning one if it can: a content Uri the app may write is
     * copied, and its metadata read again from the copy, the first time.
     */
    @WorkerThread
    private boolean editable() {
        if (mFile != null) {
            return true;
        }
        if (mUri == null || mContext == null || !WRITTEN_FORMATS.contains(getFormat())
                || !uriWritable()) {
            return false;
        }
        File copy = null;
        try {
            File directory = new File(mContext.getCacheDir(), EDIT_DIRECTORY);
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("Unable to make " + directory);
            }
            deleteStaleCopies(directory);
            copy = File.createTempFile("edit", ".tmp", directory);
            try (InputStream input = Objects.requireNonNull(mOpener).open();
                    OutputStream output = new FileOutputStream(copy)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }
            MediaContainer container;
            try (ByteSource source = ByteSource.of(copy)) {
                container = read(source, copy, mOpener);
            }
            mContainer.release();
            mContainer = container;
            mFile = copy;
            return true;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Unable to begin editing " + mUri, e);
            if (copy != null && !copy.delete()) {
                Log.w(TAG, "Unable to delete " + copy);
            }
            return false;
        }
    }

    /** Whether the app may write the content Uri, asked of its provider once. */
    @WorkerThread
    private boolean uriWritable() {
        if (mUriWritable == null) {
            boolean writable;
            try (ParcelFileDescriptor descriptor = Objects.requireNonNull(mContext)
                    .getContentResolver().openFileDescriptor(Objects.requireNonNull(mUri),
                            "rw")) {
                writable = descriptor != null;
            } catch (IOException | SecurityException | IllegalArgumentException
                    | UnsupportedOperationException e) {
                writable = false;
            }
            mUriWritable = writable;
        }
        return mUriWritable;
    }

    /** Deletes copies earlier edits left behind without saving. */
    @WorkerThread
    private static void deleteStaleCopies(@NonNull File directory) {
        File[] copies = directory.listFiles();
        if (copies == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (File old : copies) {
            if (now - old.lastModified() > STALE_EDIT_MILLIS && !old.delete()) {
                Log.w(TAG, "Unable to delete " + old);
            }
        }
    }

    /** Writes an edited copy back over the content Uri it was made from. */
    @WorkerThread
    private void writeBack(@NonNull File copy) throws IOException {
        Uri uri = Objects.requireNonNull(mUri);
        ContentResolver resolver = Objects.requireNonNull(mContext).getContentResolver();
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "rw")) {
            if (descriptor == null) {
                throw new FileNotFoundException("Unable to write " + uri);
            }
            try (FileOutputStream output = new FileOutputStream(descriptor.getFileDescriptor());
                    FileInputStream input = new FileInputStream(copy)) {
                FileChannel to = output.getChannel();
                FileChannel from = input.getChannel();
                long length = from.size();
                ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
                long position = 0;
                while (true) {
                    buffer.clear();
                    int read = from.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    buffer.flip();
                    while (buffer.hasRemaining()) {
                        position += to.write(buffer, position);
                    }
                }
                if (position != length) {
                    throw new IOException("Wrote " + position + " of " + length + " bytes");
                }
                to.truncate(length);
                to.force(true);
            }
        }
    }

    private boolean anyNative() {
        for (int field = MediaContainer.TITLE; field <= MediaContainer.COVER_ART; field++) {
            if (mContainer.keeps(field)) {
                return true;
            }
        }
        return false;
    }

    // ---------- Text ----------

    /** What the file is called, or null. */
    @AnyThread
    @Nullable
    public String getTitle() {
        return text(MediaContainer.TITLE, Xmp.NS_DC, "title");
    }

    /**
     * Sets what the file is called, or removes it for null.
     *
     * @return false, with nothing changed, if the file cannot keep a title
     */
    @WorkerThread
    public boolean setTitle(@Nullable String title) {
        return setText(MediaContainer.TITLE, Xmp.NS_DC, "title", title, FORM_ALT);
    }

    /** A description of what the file shows or holds, or null. */
    @AnyThread
    @Nullable
    public String getDescription() {
        return text(MediaContainer.DESCRIPTION, Xmp.NS_DC, "description");
    }

    /**
     * Sets the description, or removes it for null.
     *
     * @return false, with nothing changed, if the file cannot keep one
     */
    @WorkerThread
    public boolean setDescription(@Nullable String description) {
        return setText(MediaContainer.DESCRIPTION, Xmp.NS_DC, "description", description,
                FORM_ALT);
    }

    /** Who made the file: the photographer, or a recording's artist, or null. */
    @AnyThread
    @Nullable
    public String getAuthor() {
        return text(MediaContainer.AUTHOR, Xmp.NS_DC, "creator");
    }

    /**
     * Sets who made the file, or removes it for null.
     *
     * @return false, with nothing changed, if the file cannot keep it
     */
    @WorkerThread
    public boolean setAuthor(@Nullable String author) {
        return setText(MediaContainer.AUTHOR, Xmp.NS_DC, "creator", author, FORM_SEQ);
    }

    /** The copyright notice, or null. */
    @AnyThread
    @Nullable
    public String getCopyright() {
        return text(MediaContainer.COPYRIGHT, Xmp.NS_DC, "rights");
    }

    /**
     * Sets the copyright notice, or removes it for null.
     *
     * @return false, with nothing changed, if the file cannot keep one
     */
    @WorkerThread
    public boolean setCopyright(@Nullable String copyright) {
        return setText(MediaContainer.COPYRIGHT, Xmp.NS_DC, "rights", copyright, FORM_ALT);
    }

    /** The maker of the device that captured the file, or null. */
    @AnyThread
    @Nullable
    public String getMake() {
        return text(MediaContainer.MAKE, Xmp.NS_TIFF, "Make");
    }

    /**
     * Sets the maker of the capturing device, or removes it for null.
     *
     * @return false, with nothing changed, if the file cannot keep it
     */
    @WorkerThread
    public boolean setMake(@Nullable String make) {
        return setText(MediaContainer.MAKE, Xmp.NS_TIFF, "Make", make, FORM_SIMPLE);
    }

    /** The model of the device that captured the file, or null. */
    @AnyThread
    @Nullable
    public String getModel() {
        return text(MediaContainer.MODEL, Xmp.NS_TIFF, "Model");
    }

    /**
     * Sets the model of the capturing device, or removes it for null.
     *
     * @return false, with nothing changed, if the file cannot keep it
     */
    @WorkerThread
    public boolean setModel(@Nullable String model) {
        return setText(MediaContainer.MODEL, Xmp.NS_TIFF, "Model", model, FORM_SIMPLE);
    }

    /** The software that made the file, or null. */
    @AnyThread
    @Nullable
    public String getSoftware() {
        return text(MediaContainer.SOFTWARE, Xmp.NS_XMP, "CreatorTool");
    }

    /**
     * Sets the software that made the file, or removes it for null.
     *
     * @return false, with nothing changed, if the file cannot keep it
     */
    @WorkerThread
    public boolean setSoftware(@Nullable String software) {
        return setText(MediaContainer.SOFTWARE, Xmp.NS_XMP, "CreatorTool", software,
                FORM_SIMPLE);
    }

    /** A simple XMP property. */
    private static final int FORM_SIMPLE = 0;
    /** A language alternative, written with a default entry. */
    private static final int FORM_ALT = 1;
    /** An ordered array. */
    private static final int FORM_SEQ = 2;

    @Nullable
    private String text(@MediaContainer.Field int field, @NonNull String namespace,
            @NonNull String name) {
        String value = mContainer.text(field);
        return value != null ? value : xmpValue(namespace, name);
    }

    private boolean setText(@MediaContainer.Field int field, @NonNull String namespace,
            @NonNull String name, @Nullable String value, int form) {
        if (!editable()) {
            return false;
        }
        String text = value == null || value.trim().isEmpty() ? null : value;
        try {
            if (mContainer.keeps(field)) {
                if (mContainer.setText(field, text)) {
                    syncXmp(namespace, name, text, form);
                    return true;
                }
                // The format's own form cannot hold this text, so XMP holds it, and the format
                // keeps nothing that would be read instead.
                if (putXmp(namespace, name, text, form)) {
                    mContainer.setText(field, null);
                    return true;
                }
                return false;
            }
            return !mContainer.hasNativeHome(field) && putXmp(namespace, name, text, form);
        } catch (IOException e) {
            Log.w(TAG, "Unable to stage a change to " + name, e);
            return false;
        }
    }

    // ---------- XMP ----------

    /** The packet to read: the file's, or the extended packet that continues it. */
    @Nullable
    private String xmpValue(@NonNull String namespace, @NonNull String name) {
        String value = mContainer.xmp().get(namespace, name);
        if (value == null && mContainer.extendedXmp() != null) {
            value = mContainer.extendedXmp().get(namespace, name);
        }
        return value == null || value.isEmpty() ? null : value;
    }

    /** The packet, if the file can keep one and it can be edited, or null. */
    @Nullable
    private Xmp writableXmp() {
        if (!editable()) {
            return null;
        }
        Xmp xmp = mContainer.xmp();
        return mFile != null && mContainer.maxXmpBytes() > 0 && xmp.isWritable() ? xmp : null;
    }

    /** An edit to a packet. */
    private interface XmpEdit {
        void apply(@NonNull Xmp xmp);
    }

    /**
     * Makes an edit to the packet, if the file keeps one and the edited packet still fits.
     *
     * @return whether it was made
     */
    private boolean editXmp(@NonNull XmpEdit edit) {
        Xmp xmp = writableXmp();
        if (xmp == null) {
            return false;
        }
        if (mContainer.maxXmpBytes() < Integer.MAX_VALUE) {
            Xmp trial = xmp.copy();
            edit.apply(trial);
            if (trial.toBytes().length > mContainer.maxXmpBytes()) {
                return false;
            }
        }
        edit.apply(xmp);
        mContainer.xmpChanged();
        return true;
    }

    private boolean putXmp(@NonNull String namespace, @NonNull String name,
            @Nullable String value, int form) {
        return editXmp(xmp -> putValue(xmp, namespace, name, value, form));
    }

    private static void putValue(@NonNull Xmp xmp, @NonNull String namespace,
            @NonNull String name, @Nullable String value, int form) {
        switch (form) {
            case FORM_ALT:
                xmp.setAlt(namespace, name, value);
                break;
            case FORM_SEQ:
                xmp.setSeq(namespace, name, value);
                break;
            default:
                xmp.set(namespace, name, value);
        }
    }

    /** Changes a property the packet already has to match a field written natively. */
    private void syncXmp(@NonNull String namespace, @NonNull String name,
            @Nullable String value, int form) {
        if (mContainer.xmp().get(namespace, name) != null) {
            editXmp(xmp -> putValue(xmp, namespace, name, value, form));
        }
    }

    /**
     * Reads a simple XMP property by its namespace and name, from whichever packet the format
     * keeps. An array's value is its default language entry, or its first.
     *
     * @return the value, or null when it is absent or is a structure
     */
    @AnyThread
    @Nullable
    public String getXmp(@NonNull String namespace, @NonNull String property) {
        return xmpValue(namespace, property);
    }

    /**
     * Sets a simple XMP property by its namespace and name, or removes it for null. This reaches
     * any property of any namespace, in any format that keeps XMP.
     *
     * @return false, with nothing changed, if the file keeps no XMP, its packet could not be
     *     read as XML, the name is not one XML allows, or the packet would outgrow its place
     */
    @WorkerThread
    public boolean setXmp(@NonNull String namespace, @NonNull String property,
            @Nullable String value) {
        if (namespace.isEmpty() || !XML_NAME.matcher(property).matches()
                || Xmp.NS_RDF.equals(namespace) || Xmp.NS_XML.equals(namespace)
                || Xmp.NS_XMLNS.equals(namespace)) {
            return false;
        }
        return editXmp(xmp -> xmp.set(namespace, property, value));
    }

    // ---------- Capture ----------

    /** When the file was captured, in milliseconds since the epoch, or -1 if it does not say. */
    @AnyThread
    public long getCaptureTime() {
        long time = mContainer.captureTime();
        if (time != -1) {
            return time;
        }
        for (String[] property : new String[][] {{Xmp.NS_EXIF, "DateTimeOriginal"},
                {Xmp.NS_PHOTOSHOP, "DateCreated"}, {Xmp.NS_XMP, "CreateDate"}}) {
            time = parseIsoTime(xmpValue(property[0], property[1]));
            if (time != -1) {
                return time;
            }
        }
        return -1;
    }

    /**
     * Sets when the file was captured, in milliseconds since the epoch, or removes it for -1.
     * It is written in the device's time zone, with the offset where the format keeps one. A
     * format that keeps only the day, as a WAV does, keeps only the day.
     *
     * @return false, with nothing changed, if the file cannot keep a capture time
     */
    @WorkerThread
    public boolean setCaptureTime(long millis) {
        if (!editable()) {
            return false;
        }
        final String iso = millis < 0 ? null : formatIsoTime(millis, TimeZone.getDefault());
        try {
            if (mContainer.keeps(MediaContainer.CAPTURE_TIME)) {
                mContainer.setCaptureTime(millis < 0 ? -1 : millis);
                for (String[] property : new String[][] {{Xmp.NS_EXIF, "DateTimeOriginal"},
                        {Xmp.NS_PHOTOSHOP, "DateCreated"}, {Xmp.NS_XMP, "CreateDate"}}) {
                    syncXmp(property[0], property[1], iso, FORM_SIMPLE);
                }
                return true;
            }
            return !mContainer.hasNativeHome(MediaContainer.CAPTURE_TIME)
                    && putXmp(Xmp.NS_EXIF, "DateTimeOriginal", iso, FORM_SIMPLE);
        } catch (IOException e) {
            Log.w(TAG, "Unable to stage the capture time", e);
            return false;
        }
    }

    /**
     * Where the file was captured: its latitude, longitude and, if recorded, altitude, or null.
     */
    @AnyThread
    @Nullable
    public Location getLocation() {
        Location location = mContainer.location();
        return location != null ? location : xmpLocation();
    }

    /**
     * Sets where the file was captured, or removes it for null. The latitude, longitude and
     * altitude are written everywhere; a picture's Exif also keeps the fix's time, speed and
     * accuracy.
     *
     * @return false, with nothing changed, if the file cannot keep a location
     */
    @WorkerThread
    public boolean setLocation(@Nullable Location location) {
        if (!editable()) {
            return false;
        }
        try {
            if (mContainer.keeps(MediaContainer.LOCATION)) {
                mContainer.setLocation(location);
                if (mContainer.xmp().get(Xmp.NS_EXIF, "GPSLatitude") != null) {
                    editXmp(xmp -> putLocation(xmp, location));
                }
                return true;
            }
            return !mContainer.hasNativeHome(MediaContainer.LOCATION)
                    && editXmp(xmp -> putLocation(xmp, location));
        } catch (IOException e) {
            Log.w(TAG, "Unable to stage the location", e);
            return false;
        }
    }

    @Nullable
    private Location xmpLocation() {
        double latitude = parseXmpCoordinate(xmpValue(Xmp.NS_EXIF, "GPSLatitude"));
        double longitude = parseXmpCoordinate(xmpValue(Xmp.NS_EXIF, "GPSLongitude"));
        if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
            return null;
        }
        Location location = new Location(TAG);
        location.setLatitude(latitude);
        location.setLongitude(longitude);
        double altitude = ExifCaptureParameters.number(xmpValue(Xmp.NS_EXIF, "GPSAltitude"));
        if (!Double.isNaN(altitude)) {
            location.setAltitude("1".equals(xmpValue(Xmp.NS_EXIF, "GPSAltitudeRef"))
                    ? -altitude : altitude);
        }
        return location;
    }

    private static void putLocation(@NonNull Xmp xmp, @Nullable Location location) {
        String[] names = {"GPSLatitude", "GPSLongitude", "GPSAltitude", "GPSAltitudeRef",
                "GPSVersionID", "GPSMapDatum"};
        for (String name : names) {
            xmp.remove(Xmp.NS_EXIF, name);
        }
        if (location == null) {
            return;
        }
        xmp.set(Xmp.NS_EXIF, "GPSVersionID", "2.3.0.0");
        xmp.set(Xmp.NS_EXIF, "GPSMapDatum", "WGS-84");
        xmp.set(Xmp.NS_EXIF, "GPSLatitude", formatXmpCoordinate(location.getLatitude(),
                'N', 'S'));
        xmp.set(Xmp.NS_EXIF, "GPSLongitude", formatXmpCoordinate(location.getLongitude(),
                'E', 'W'));
        if (location.hasAltitude()) {
            xmp.set(Xmp.NS_EXIF, "GPSAltitudeRef", location.getAltitude() < 0 ? "1" : "0");
            xmp.set(Xmp.NS_EXIF, "GPSAltitude",
                    Math.round(Math.abs(location.getAltitude()) * 1000) + "/1000");
        }
    }

    /** A coordinate as XMP writes it: degrees, decimal minutes and a direction. */
    @VisibleForTesting
    @NonNull
    static String formatXmpCoordinate(double degrees, char positive, char negative) {
        double magnitude = Math.abs(degrees);
        int whole = (int) magnitude;
        double minutes = (magnitude - whole) * 60;
        return String.format(Locale.US, "%d,%.6f%c", whole, minutes,
                degrees < 0 ? negative : positive);
    }

    /** A coordinate in XMP's form, or NaN. */
    @VisibleForTesting
    static double parseXmpCoordinate(@Nullable String text) {
        if (text == null) {
            return Double.NaN;
        }
        Matcher matcher = XMP_COORDINATE.matcher(text);
        if (!matcher.matches()) {
            return Double.NaN;
        }
        double value = Integer.parseInt(matcher.group(1))
                + Double.parseDouble(matcher.group(2)) / 60
                + (matcher.group(3) == null ? 0 : Double.parseDouble(matcher.group(3)) / 3600);
        char direction = matcher.group(4).charAt(0);
        return direction == 'S' || direction == 'W' ? -value : value;
    }

    /**
     * Which way the camera faced, in degrees clockwise from north, or NaN if it does not say.
     * North is true north unless the file itself said magnetic.
     */
    @AnyThread
    public float getHeading() {
        float heading = mContainer.heading();
        if (!Float.isNaN(heading)) {
            return heading;
        }
        double xmp = ExifCaptureParameters.number(xmpValue(Xmp.NS_EXIF, "GPSImgDirection"));
        return Double.isNaN(xmp) ? Float.NaN : (float) xmp;
    }

    /**
     * Sets which way the camera faced, in degrees clockwise from true north, or removes it for
     * NaN.
     *
     * @return false, with nothing changed, if the file cannot keep a heading
     */
    @WorkerThread
    public boolean setHeading(float degrees) {
        if (!editable()) {
            return false;
        }
        try {
            if (mContainer.keeps(MediaContainer.HEADING)) {
                mContainer.setHeading(degrees);
                if (mContainer.xmp().get(Xmp.NS_EXIF, "GPSImgDirection") != null) {
                    editXmp(xmp -> putHeading(xmp, degrees));
                }
                return true;
            }
            return !mContainer.hasNativeHome(MediaContainer.HEADING)
                    && editXmp(xmp -> putHeading(xmp, degrees));
        } catch (IOException e) {
            Log.w(TAG, "Unable to stage the heading", e);
            return false;
        }
    }

    private static void putHeading(@NonNull Xmp xmp, float degrees) {
        if (Float.isNaN(degrees)) {
            xmp.remove(Xmp.NS_EXIF, "GPSImgDirection");
            xmp.remove(Xmp.NS_EXIF, "GPSImgDirectionRef");
            return;
        }
        double normalized = ((degrees % 360) + 360) % 360;
        xmp.set(Xmp.NS_EXIF, "GPSImgDirectionRef", "T");
        xmp.set(Xmp.NS_EXIF, "GPSImgDirection", Math.round(normalized * 100) + "/100");
    }

    // ---------- Orientation ----------

    /**
     * How far to turn the stored picture or video clockwise to stand it upright, after
     * mirroring it if {@link #isMirrored()}: 0, 90, 180 or 270.
     */
    @AnyThread
    public int getRotation() {
        return rotationOf(orientation());
    }

    /** Whether the stored picture or video is mirrored left to right before it is turned. */
    @AnyThread
    public boolean isMirrored() {
        return isMirrored(orientation());
    }

    /** The orientation, as Exif numbers them, or 1 for upright when nothing says. */
    private int orientation() {
        int orientation = mContainer.orientation();
        if (orientation >= 1 && orientation <= 8) {
            return orientation;
        }
        String xmp = xmpValue(Xmp.NS_TIFF, "Orientation");
        try {
            orientation = xmp == null ? 1 : Integer.parseInt(xmp.trim());
        } catch (NumberFormatException malformed) {
            orientation = 1;
        }
        return orientation >= 1 && orientation <= 8 ? orientation : 1;
    }

    /**
     * Sets how the stored picture or video turns upright: mirrored left to right first if asked,
     * then turned clockwise by a right angle.
     *
     * @return false, with nothing changed, if the angle is not a right angle or the file cannot
     *     keep this orientation, such as a mirrored video or any sound file
     */
    @WorkerThread
    public boolean setOrientation(int rotation, boolean mirrored) {
        if (rotation % 90 != 0 || mContainer.kind() == KIND_AUDIO || !editable()) {
            return false;
        }
        final int orientation = toExifOrientation(((rotation % 360) + 360) % 360, mirrored);
        try {
            if (mContainer.keeps(MediaContainer.ORIENTATION)) {
                if (!mContainer.setOrientation(orientation)) {
                    return false;
                }
                syncXmp(Xmp.NS_TIFF, "Orientation", Integer.toString(orientation), FORM_SIMPLE);
                return true;
            }
            return !mContainer.hasNativeHome(MediaContainer.ORIENTATION)
                    && putXmp(Xmp.NS_TIFF, "Orientation", Integer.toString(orientation),
                    FORM_SIMPLE);
        } catch (IOException e) {
            Log.w(TAG, "Unable to stage the orientation", e);
            return false;
        }
    }

    /** An Exif orientation from a clockwise turn and whether it is mirrored first. */
    static int toExifOrientation(int rotation, boolean mirrored) {
        switch (rotation) {
            case 90:
                return mirrored ? ExifInterface.ORIENTATION_TRANSVERSE
                        : ExifInterface.ORIENTATION_ROTATE_90;
            case 180:
                return mirrored ? ExifInterface.ORIENTATION_FLIP_VERTICAL
                        : ExifInterface.ORIENTATION_ROTATE_180;
            case 270:
                return mirrored ? ExifInterface.ORIENTATION_TRANSPOSE
                        : ExifInterface.ORIENTATION_ROTATE_270;
            default:
                return mirrored ? ExifInterface.ORIENTATION_FLIP_HORIZONTAL
                        : ExifInterface.ORIENTATION_NORMAL;
        }
    }

    /** The clockwise turn of an Exif orientation, after any mirroring. */
    static int rotationOf(int orientation) {
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
            case ExifInterface.ORIENTATION_TRANSVERSE:
                return 90;
            case ExifInterface.ORIENTATION_ROTATE_180:
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                return 180;
            case ExifInterface.ORIENTATION_ROTATE_270:
            case ExifInterface.ORIENTATION_TRANSPOSE:
                return 270;
            default:
                return 0;
        }
    }

    /** Whether an Exif orientation mirrors left to right before turning. */
    static boolean isMirrored(int orientation) {
        return orientation == ExifInterface.ORIENTATION_FLIP_HORIZONTAL
                || orientation == ExifInterface.ORIENTATION_FLIP_VERTICAL
                || orientation == ExifInterface.ORIENTATION_TRANSPOSE
                || orientation == ExifInterface.ORIENTATION_TRANSVERSE;
    }

    // ---------- Conditions ----------

    /** The conditions the file was captured in, or null if none were recorded. */
    @AnyThread
    @Nullable
    public AmbientConditions getAmbientConditions() {
        AmbientConditions conditions = mContainer.ambient();
        if (conditions != null) {
            return conditions;
        }
        AmbientConditions xmp = new AmbientConditions.Builder()
                .setTemperature(xmpNumber("Temperature"))
                .setHumidity(xmpNumber("Humidity"))
                .setPressure(xmpNumber("Pressure"))
                .setWaterDepth(xmpNumber("WaterDepth"))
                .setAcceleration(xmpNumber("Acceleration"))
                .setCameraElevationAngle(xmpNumber("CameraElevationAngle"))
                .build();
        return xmp.isEmpty() ? null : xmp;
    }

    private double xmpNumber(@NonNull String name) {
        return ExifCaptureParameters.number(xmpValue(Xmp.NS_EXIF_EX, name));
    }

    /**
     * Records the conditions the file was captured in, in place of any recorded before, or
     * removes them for null.
     *
     * @return false, with nothing changed, if the file cannot keep them
     */
    @WorkerThread
    public boolean setAmbientConditions(@Nullable AmbientConditions conditions) {
        if (!editable()) {
            return false;
        }
        try {
            if (mContainer.keeps(MediaContainer.AMBIENT)) {
                mContainer.setAmbient(conditions);
                if (mContainer.xmp().namespaces().contains(Xmp.NS_EXIF_EX)) {
                    editXmp(xmp -> putAmbient(xmp, conditions));
                }
                return true;
            }
            return !mContainer.hasNativeHome(MediaContainer.AMBIENT)
                    && editXmp(xmp -> putAmbient(xmp, conditions));
        } catch (IOException e) {
            Log.w(TAG, "Unable to stage the conditions", e);
            return false;
        }
    }

    private static void putAmbient(@NonNull Xmp xmp, @Nullable AmbientConditions conditions) {
        String[] names = {"Temperature", "Humidity", "Pressure", "WaterDepth", "Acceleration",
                "CameraElevationAngle"};
        double[] values = conditions == null ? null : new double[] {conditions.getTemperature(),
                conditions.getHumidity(), conditions.getPressure(), conditions.getWaterDepth(),
                conditions.getAcceleration(), conditions.getCameraElevationAngle()};
        for (int i = 0; i < names.length; i++) {
            double value = values == null ? Double.NaN : values[i];
            xmp.set(Xmp.NS_EXIF_EX, names[i], Double.isNaN(value) ? null
                    : Math.round(value * 1000) + "/1000");
        }
    }

    // ---------- Panoramas, motion photos, HDR, 360° video ----------

    /** The photo sphere (GPano) metadata of a panorama or photo sphere, or null. */
    @AnyThread
    @Nullable
    public Panorama getPanorama() {
        Panorama panorama = Panorama.from(mContainer.xmp());
        if (panorama == null && mContainer.extendedXmp() != null) {
            panorama = Panorama.from(mContainer.extendedXmp());
        }
        return panorama;
    }

    /**
     * Sets the photo sphere (GPano) metadata, in place of any the file had, or removes it for
     * null. It is XMP in every format.
     *
     * @return false, with nothing changed, if the file keeps no XMP or it would not fit
     */
    @WorkerThread
    public boolean setPanorama(@Nullable Panorama panorama) {
        if (!editable()) {
            return false;
        }
        return editXmp(xmp -> {
            xmp.removeNamespace(Xmp.NS_GPANO);
            if (panorama != null) {
                panorama.writeTo(xmp);
            }
        });
    }

    /** The motion photo's clip, if the file is a motion photo, or null. */
    @WorkerThread
    @Nullable
    public MotionPhoto getMotionPhoto() {
        return mContainer.motionPhoto();
    }

    /** The camera namespace's properties that describe a motion photo's clip. */
    private static final String[] MOTION_PHOTO_PROPERTIES = {"MotionPhoto", "MotionPhotoVersion",
            "MotionPhotoPresentationTimestampUs", "MicroVideo", "MicroVideoVersion",
            "MicroVideoOffset", "MicroVideoPresentationTimestampUs"};

    /**
     * Says the picture carries a motion photo's clip of the given length at its very end, in
     * Google's form: the camera namespace's flags, and an entry after the items the container
     * directory already lists, which begin with the primary picture. Any earlier description of
     * a clip is replaced. The clip itself is the caller's to append.
     *
     * @param presentationTimestampUs the time in the clip the still shows, or -1
     * @return false, with nothing changed, if the file keeps no XMP or it would not fit
     */
    @WorkerThread
    boolean describeMotionPhoto(long videoLength, long presentationTimestampUs) {
        if (!editable() || mContainer.kind() != KIND_IMAGE) {
            return false;
        }
        final String primaryMime = getFormat() == FORMAT_HEIF ? "image/heic" : "image/jpeg";
        return editXmp(xmp -> {
            List<Map<String, String>> items = withoutClip(xmp);
            if (items.isEmpty() || !"Primary".equals(items.get(0).get(Xmp.NS_ITEM + "Semantic"))) {
                items.add(0, directoryItem(primaryMime, "Primary", 0));
            }
            items.add(directoryItem("video/mp4", "MotionPhoto", videoLength));
            for (String property : MOTION_PHOTO_PROPERTIES) {
                xmp.remove(Xmp.NS_CAMERA, property);
            }
            xmp.set(Xmp.NS_CAMERA, "MotionPhoto", "1");
            xmp.set(Xmp.NS_CAMERA, "MotionPhotoVersion", "1");
            if (presentationTimestampUs >= 0) {
                xmp.set(Xmp.NS_CAMERA, "MotionPhotoPresentationTimestampUs",
                        Long.toString(presentationTimestampUs));
            }
            xmp.setDirectory(items);
        });
    }

    /**
     * Takes out what says the picture carries a motion photo's clip, for one whose clip has been
     * cut off, leaving any other item the container directory lists, such as a gain map.
     *
     * @return false if the file says it has a clip and that could not be changed
     */
    @WorkerThread
    boolean removeMotionPhotoDescription() {
        boolean described = false;
        for (String property : MOTION_PHOTO_PROPERTIES) {
            described |= mContainer.xmp().get(Xmp.NS_CAMERA, property) != null;
        }
        List<Map<String, String>> items = mContainer.xmp().getStructs(Xmp.NS_CONTAINER,
                "Directory");
        if (!described && withoutClip(mContainer.xmp()).size() == items.size()) {
            return true;
        }
        if (!editable()) {
            return false;
        }
        return editXmp(xmp -> {
            for (String property : MOTION_PHOTO_PROPERTIES) {
                xmp.remove(Xmp.NS_CAMERA, property);
            }
            List<Map<String, String>> kept = withoutClip(xmp);
            // A directory that lists nothing after the primary picture says nothing.
            xmp.setDirectory(kept.size() > 1 ? kept : new ArrayList<>());
        });
    }

    /** The container directory's items, leaving out a clip. */
    @NonNull
    private static List<Map<String, String>> withoutClip(@NonNull Xmp xmp) {
        List<Map<String, String>> items = new ArrayList<>();
        for (Map<String, String> item : xmp.getStructs(Xmp.NS_CONTAINER, "Directory")) {
            String mime = item.get(Xmp.NS_ITEM + "Mime");
            if ("MotionPhoto".equals(item.get(Xmp.NS_ITEM + "Semantic"))
                    || (mime != null && mime.startsWith("video/"))) {
                continue;
            }
            items.add(item);
        }
        return items;
    }

    @NonNull
    private static Map<String, String> directoryItem(@NonNull String mime,
            @NonNull String semantic, long length) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put(Xmp.NS_ITEM + "Mime", mime);
        item.put(Xmp.NS_ITEM + "Semantic", semantic);
        item.put(Xmp.NS_ITEM + "Length", Long.toString(length));
        if ("Primary".equals(semantic)) {
            // Only the primary picture may have padding after it, as the format defines it.
            item.put(Xmp.NS_ITEM + "Padding", "0");
        }
        return item;
    }

    /** Whether the file's Exif holds a thumbnail. */
    @AnyThread
    boolean hasExifThumbnail() {
        Exif exif = mContainer.exif();
        return exif != null && exif.hasThumbnail();
    }

    /**
     * Has {@link #save()} write a thumbnail into the file's Exif if it has none.
     *
     * @return false if the format keeps no Exif or it cannot be written
     */
    @WorkerThread
    boolean attachExifThumbnail() {
        if (!editable()) {
            return false;
        }
        try {
            Exif exif = mContainer.writableExif();
            if (exif == null) {
                return false;
            }
            exif.attachThumbnail();
            mContainer.markDirty();
            return true;
        } catch (IOException e) {
            Log.w(TAG, "Unable to stage a thumbnail", e);
            return false;
        }
    }

    /**
     * Whether a picture is animated, showing more than one frame in turn: an animated GIF, WebP
     * or PNG, or an AVIF or HEIF image sequence.
     */
    @AnyThread
    public boolean isAnimated() {
        return mContainer.animated();
    }

    /** The kind of gain map an HDR picture carries, or {@link #GAIN_MAP_NONE}. */
    @WorkerThread
    @GainMap
    public int getGainMap() {
        return mContainer.gainMap();
    }

    /** How the video's brightness is encoded, or {@link #TRANSFER_UNKNOWN} if it does not say. */
    @AnyThread
    @Transfer
    public int getVideoTransfer() {
        return mContainer.videoTransfer();
    }

    /** The spherical video metadata of a 360° video, or null. */
    @AnyThread
    @Nullable
    public SphericalVideo getSphericalVideo() {
        return mContainer.sphericalVideo();
    }

    /** The settings the camera captured a still with, or a slow-motion video's frame rate. */
    @AnyThread
    @Nullable
    public CaptureSettings getCaptureSettings() {
        return mContainer.captureSettings();
    }

    /**
     * The identifier Apple gives both halves of a Live Photo, the still and its movie, so they
     * can be paired; null when the file has none.
     */
    @AnyThread
    @Nullable
    public String getLivePhotoId() {
        return mContainer.livePhotoId();
    }

    /** The video's size, upright, or null if the file does not say or is not a video. */
    @Nullable
    int[] getVideoSize() {
        return mContainer.videoSize();
    }

    // ---------- Cover art ----------

    /** The encoded picture that stands for a recording, such as an album cover, or null. */
    @AnyThread
    @Nullable
    public byte[] getCoverArt() {
        byte[] art = mContainer.coverArt();
        return art == null ? null : art.clone();
    }

    /**
     * Sets the picture that stands for a recording, encoded as a JPEG or PNG, or removes it for
     * null.
     *
     * @return false, with nothing changed, if the format keeps no cover picture or not one of
     *     this kind
     */
    @WorkerThread
    public boolean setCoverArt(@Nullable byte[] image) {
        if (!editable() || !mContainer.keeps(MediaContainer.COVER_ART)) {
            return false;
        }
        try {
            return mContainer.setCoverArt(image == null ? null : image.clone());
        } catch (IOException e) {
            Log.w(TAG, "Unable to stage the cover art", e);
            return false;
        }
    }

    // ---------- Every tag ----------

    /**
     * Reads a tag of the file's Exif private directory by its number, as
     * {@link Exif#getTag(int)} does.
     *
     * @return the value as text, or null when the tag is absent or the format keeps no Exif
     */
    @AnyThread
    @Nullable
    public String getExifTag(int tag) {
        Exif exif = mContainer.exif();
        return exif == null ? null : exif.getTag(tag);
    }

    /**
     * Sets a tag of the file's Exif private directory by its number, as
     * {@link Exif#setTag(int, int, String)} does, in any format that keeps Exif.
     *
     * @return false, with nothing changed, if the format keeps no Exif or cannot write it, or
     *     the value does not suit the type
     */
    @WorkerThread
    public boolean setExifTag(int tag, @Exif.TagType int type, @Nullable String value) {
        if (!editable()) {
            return false;
        }
        try {
            Exif exif = mContainer.writableExif();
            return exif != null && exif.setTag(tag, type, value);
        } catch (IOException e) {
            Log.w(TAG, "Unable to stage an Exif tag", e);
            return false;
        }
    }

    /**
     * Reads a QuickTime field from an MP4, QuickTime, 3GP or M4A file, as
     * {@link VideoMetadata#read} does.
     *
     * @return its text, or null when the file has no such field or keeps none
     */
    @AnyThread
    @Nullable
    public String getVideoTag(@NonNull VideoMetadata.Tag tag) {
        return mContainer.videoTag(tag);
    }

    /**
     * Sets a QuickTime field in an MP4, QuickTime, 3GP or M4A file, in every form QuickTime
     * gives it, or removes it for null.
     *
     * @return false, with nothing changed, if the file keeps no QuickTime metadata, or the field
     *     holds a number and this is not one
     */
    @WorkerThread
    public boolean setVideoTag(@NonNull VideoMetadata.Tag tag, @Nullable String value) {
        return editable() && mContainer.setVideoTag(tag, value);
    }

    // ---------- Copying ----------

    /**
     * Copies every field this file has into another file's metadata, to be written by its
     * {@link #save()}, even across formats: the fields above, a picture's whole Exif, a video's
     * QuickTime fields and the XMP packet. What describes data the other file does not have is
     * left out: the layout of this picture's bytes, and the motion photo, gain map and container
     * XMP.
     *
     * @return false if the other file could not keep some of what this one has
     */
    @WorkerThread
    @SuppressWarnings("deprecation")
    public boolean copyTo(@NonNull MediaTags destination) {
        if (!destination.editable()) {
            return false;
        }
        boolean complete = true;
        Exif from = mContainer.exif();
        if (from != null) {
            try {
                Exif to = destination.mContainer.writableExif();
                if (to != null) {
                    String[] layout = new String[LAYOUT_TAGS.length];
                    for (int i = 0; i < LAYOUT_TAGS.length; i++) {
                        layout[i] = to.getAttribute(LAYOUT_TAGS[i]);
                    }
                    to.copyFrom(from);
                    for (int i = 0; i < LAYOUT_TAGS.length; i++) {
                        to.setAttribute(LAYOUT_TAGS[i], layout[i]);
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "Unable to copy the Exif", e);
            }
        }
        complete &= copy(getTitle(), destination::setTitle);
        complete &= copy(getDescription(), destination::setDescription);
        complete &= copy(getAuthor(), destination::setAuthor);
        complete &= copy(getCopyright(), destination::setCopyright);
        complete &= copy(getMake(), destination::setMake);
        complete &= copy(getModel(), destination::setModel);
        complete &= copy(getSoftware(), destination::setSoftware);
        long time = getCaptureTime();
        complete &= time == -1 || destination.setCaptureTime(time);
        Location location = getLocation();
        complete &= location == null || destination.setLocation(location);
        float heading = getHeading();
        complete &= Float.isNaN(heading) || destination.setHeading(heading);
        int orientation = orientation();
        if (orientation != ExifInterface.ORIENTATION_NORMAL) {
            complete &= destination.setOrientation(rotationOf(orientation),
                    isMirrored(orientation));
        }
        AmbientConditions conditions = getAmbientConditions();
        complete &= conditions == null || destination.setAmbientConditions(conditions);
        byte[] cover = getCoverArt();
        complete &= cover == null || destination.setCoverArt(cover);
        if (mContainer.videoTag(VideoMetadata.Tag.TITLE) != null
                || mContainer.format() == FORMAT_MP4 || mContainer.format() == FORMAT_QUICKTIME
                || mContainer.format() == FORMAT_3GP || mContainer.format() == FORMAT_M4A) {
            for (VideoMetadata.Tag tag : VideoMetadata.Tag.values()) {
                String value = mContainer.videoTag(tag);
                if (value != null && destination.getVideoTag(tag) == null) {
                    complete &= destination.setVideoTag(tag, value);
                }
            }
        }
        complete &= copyXmp(destination);
        return complete;
    }

    /** A setter, for {@link #copyTo}. */
    private interface Setter {
        boolean set(@NonNull String value);
    }

    private static boolean copy(@Nullable String value, @NonNull Setter setter) {
        return value == null || setter.set(value);
    }

    /** Copies the packet, without what describes data the destination does not have. */
    private boolean copyXmp(@NonNull MediaTags destination) {
        Set<String> carried = new HashSet<>(mContainer.xmp().namespaces());
        carried.removeAll(NOT_COPIED);
        if (carried.isEmpty()) {
            return true;
        }
        Xmp source = mContainer.xmp();
        return destination.editXmp(xmp -> xmp.merge(source, NOT_COPIED))
                || onlyNativeFields(carried);
    }

    /**
     * Whether a packet holds nothing but the fields above, which were copied on their own, so
     * a destination without XMP has lost nothing.
     */
    private static boolean onlyNativeFields(@NonNull Set<String> namespaces) {
        Set<String> native_ = new HashSet<>(Arrays.asList(Xmp.NS_DC, Xmp.NS_TIFF, Xmp.NS_EXIF,
                Xmp.NS_XMP, Xmp.NS_PHOTOSHOP, Xmp.NS_EXIF_EX));
        return native_.containsAll(namespaces);
    }

    // ---------- Saving ----------

    /**
     * Writes every change since the file was opened or last saved. Only a file opened from a
     * {@link File} or a file Uri can be saved.
     *
     * @throws IOException if the file cannot be written, which includes metadata that has
     *     outgrown the room a JPEG gives its Exif
     */
    @WorkerThread
    public void save() throws IOException {
        File file = mFile;
        if (file == null) {
            if (mUri != null) {
                // Nothing was edited.
                return;
            }
            throw new IOException("Opened to read only");
        }
        if (!mContainer.isDirty()) {
            return;
        }
        if (mUri == null) {
            try {
                mContainer.save(file);
            } finally {
                mContainer.release();
                // What was read before is out of date; read it all again.
                try (ByteSource source = ByteSource.of(file)) {
                    mContainer = read(source, file, mOpener);
                }
            }
            return;
        }
        // A content Uri: the copy is saved, then written back, and the next edit copies again.
        try {
            mContainer.save(file);
            writeBack(file);
        } finally {
            mContainer.release();
            mFile = null;
            try (ByteSource source = ByteSource.of(file)) {
                mContainer = read(source, null, mOpener);
            } finally {
                if (!file.delete()) {
                    Log.w(TAG, "Unable to delete " + file);
                }
            }
        }
    }

    // ---------- Times ----------

    /** ISO 8601, as XMP and QuickTime write a time with its offset. */
    @NonNull
    static String formatIsoTime(long millis, @NonNull TimeZone zone) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        format.setTimeZone(zone);
        return format.format(new Date(millis))
                + ExifImageContainer.formatOffset(zone.getOffset(millis));
    }

    /**
     * An ISO 8601 time, with or without seconds, fractions and an offset, or a bare date or
     * year. One without an offset is in the device's time zone.
     *
     * @return the time in milliseconds since the epoch, or -1
     */
    static long parseIsoTime(@Nullable String text) {
        if (text == null) {
            return -1;
        }
        // The date's colons too, as Exif writes it and some panorama writers copied.
        Matcher matcher = Pattern.compile("^\\s*(\\d{4})(?:[-:]?(\\d{2})(?:[-:]?(\\d{2})"
                + "(?:[T ](\\d{2}):?(\\d{2})(?::?(\\d{2})(?:[.,](\\d+))?)?)?)?)?"
                + "\\s*(Z|[+-]\\d{2}(?::?\\d{2})?)?\\s*$").matcher(text);
        if (!matcher.matches()) {
            return -1;
        }
        TimeZone zone = matcher.group(8) == null ? TimeZone.getDefault()
                : ExifImageContainer.parseOffset(matcher.group(8));
        if (zone == null) {
            return -1;
        }
        String date = matcher.group(1) + "-" + orDefault(matcher.group(2), "01") + "-"
                + orDefault(matcher.group(3), "01") + " " + orDefault(matcher.group(4), "00")
                + ":" + orDefault(matcher.group(5), "00") + ":"
                + orDefault(matcher.group(6), "00");
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        format.setLenient(false);
        format.setTimeZone(zone);
        try {
            long millis = format.parse(date).getTime();
            String fraction = matcher.group(7);
            if (fraction != null) {
                millis += Exif.parseSubSecond(fraction);
            }
            return millis;
        } catch (ParseException notATime) {
            return -1;
        }
    }

    @NonNull
    private static String orDefault(@Nullable String value, @NonNull String fallback) {
        return value == null ? fallback : value;
    }

    @NonNull
    @Override
    public String toString() {
        return "MediaTags{format=" + getFormat() + ", kind=" + getKind() + ", title="
                + getTitle() + ", captureTime=" + getCaptureTime() + ", location="
                + getLocation() + ", rotation=" + getRotation() + ", mirrored=" + isMirrored()
                + ", panorama=" + (getPanorama() != null) + "}";
    }

    // ---------- Values ----------

    /**
     * The photo sphere (GPano) metadata: where an equirectangular picture sits on the sphere it
     * was cut from, how a viewer should open it, and how it was made. Every property the
     * namespace defines is here, each getter named for the property it reads; a real number
     * that is absent is NaN, a date -1, and anything else null.
     */
    public static final class Panorama {
        /** The projection a photo sphere uses, and the only one Google's viewers show. */
        public static final String PROJECTION_EQUIRECTANGULAR = "equirectangular";

        /** Each property's name in the namespace, as written. */
        private static final String[] NAMES = {"UsePanoramaViewer", "CaptureSoftware",
                "StitchingSoftware", "ProjectionType", "PoseHeadingDegrees", "PosePitchDegrees",
                "PoseRollDegrees", "InitialViewHeadingDegrees", "InitialViewPitchDegrees",
                "InitialViewRollDegrees", "InitialHorizontalFOVDegrees",
                "InitialVerticalFOVDegrees", "FirstPhotoDate", "LastPhotoDate",
                "SourcePhotosCount", "ExposureLockUsed", "CroppedAreaImageWidthPixels",
                "CroppedAreaImageHeightPixels", "FullPanoWidthPixels", "FullPanoHeightPixels",
                "CroppedAreaLeftPixels", "CroppedAreaTopPixels", "InitialCameraDolly",
                "LargestValidInteriorRectLeft", "LargestValidInteriorRectTop",
                "LargestValidInteriorRectWidth", "LargestValidInteriorRectHeight"};

        /** Whether to show the picture in a panorama viewer, or null. */
        @Nullable private final Boolean mUsePanoramaViewer;
        /** The app the source photos were taken with, or null. */
        @Nullable private final String mCaptureSoftware;
        /** What stitched them, or null. */
        @Nullable private final String mStitchingSoftware;
        /** The projection, or null. */
        @Nullable private final String mProjectionType;
        /** The compass heading of the middle of the full panorama, or NaN. */
        private final float mPoseHeadingDegrees;
        /** How far the middle of the full panorama is above the horizon, or NaN. */
        private final float mPosePitchDegrees;
        /** How far the panorama is rolled, or NaN. */
        private final float mPoseRollDegrees;
        /** The heading a viewer opens at, or null. */
        @Nullable private final Integer mInitialViewHeadingDegrees;
        /** The pitch a viewer opens at, or null. */
        @Nullable private final Integer mInitialViewPitchDegrees;
        /** The roll a viewer opens at, or null. */
        @Nullable private final Integer mInitialViewRollDegrees;
        /** How wide a view a viewer opens with, or NaN. */
        private final float mInitialHorizontalFovDegrees;
        /** How tall a view a viewer opens with, or NaN. */
        private final float mInitialVerticalFovDegrees;
        /** When the first source photo was taken, or -1. */
        private final long mFirstPhotoDate;
        /** When the last source photo was taken, or -1. */
        private final long mLastPhotoDate;
        /** How many source photos there were, or null. */
        @Nullable private final Integer mSourcePhotosCount;
        /** Whether exposure was locked, or null. */
        @Nullable private final Boolean mExposureLockUsed;
        /** The stored picture's width in the metadata's pixels, or null. */
        @Nullable private final Integer mCroppedAreaImageWidthPixels;
        /** Its height, or null. */
        @Nullable private final Integer mCroppedAreaImageHeightPixels;
        /** The full panorama's width, or null. */
        @Nullable private final Integer mFullPanoWidthPixels;
        /** Its height, or null. */
        @Nullable private final Integer mFullPanoHeightPixels;
        /** Where the stored picture's left edge falls in the full panorama, or null. */
        @Nullable private final Integer mCroppedAreaLeftPixels;
        /** Where its top edge falls, or null. */
        @Nullable private final Integer mCroppedAreaTopPixels;
        /** How far back from the centre a viewer's camera sits, or NaN. */
        private final float mInitialCameraDolly;
        /** The left of the largest rectangle with no empty pixels, or null. */
        @Nullable private final Integer mLargestValidInteriorRectLeft;
        /** Its top, or null. */
        @Nullable private final Integer mLargestValidInteriorRectTop;
        /** Its width, or null. */
        @Nullable private final Integer mLargestValidInteriorRectWidth;
        /** Its height, or null. */
        @Nullable private final Integer mLargestValidInteriorRectHeight;

        private Panorama(@NonNull Builder builder) {
            mUsePanoramaViewer = builder.mUsePanoramaViewer;
            mCaptureSoftware = builder.mCaptureSoftware;
            mStitchingSoftware = builder.mStitchingSoftware;
            mProjectionType = builder.mProjectionType;
            mPoseHeadingDegrees = builder.mPoseHeadingDegrees;
            mPosePitchDegrees = builder.mPosePitchDegrees;
            mPoseRollDegrees = builder.mPoseRollDegrees;
            mInitialViewHeadingDegrees = builder.mInitialViewHeadingDegrees;
            mInitialViewPitchDegrees = builder.mInitialViewPitchDegrees;
            mInitialViewRollDegrees = builder.mInitialViewRollDegrees;
            mInitialHorizontalFovDegrees = builder.mInitialHorizontalFovDegrees;
            mInitialVerticalFovDegrees = builder.mInitialVerticalFovDegrees;
            mFirstPhotoDate = builder.mFirstPhotoDate;
            mLastPhotoDate = builder.mLastPhotoDate;
            mSourcePhotosCount = builder.mSourcePhotosCount;
            mExposureLockUsed = builder.mExposureLockUsed;
            mCroppedAreaImageWidthPixels = builder.mCroppedAreaImageWidthPixels;
            mCroppedAreaImageHeightPixels = builder.mCroppedAreaImageHeightPixels;
            mFullPanoWidthPixels = builder.mFullPanoWidthPixels;
            mFullPanoHeightPixels = builder.mFullPanoHeightPixels;
            mCroppedAreaLeftPixels = builder.mCroppedAreaLeftPixels;
            mCroppedAreaTopPixels = builder.mCroppedAreaTopPixels;
            mInitialCameraDolly = builder.mInitialCameraDolly;
            mLargestValidInteriorRectLeft = builder.mLargestValidInteriorRectLeft;
            mLargestValidInteriorRectTop = builder.mLargestValidInteriorRectTop;
            mLargestValidInteriorRectWidth = builder.mLargestValidInteriorRectWidth;
            mLargestValidInteriorRectHeight = builder.mLargestValidInteriorRectHeight;
        }

        @Nullable public Boolean getUsePanoramaViewer() { return mUsePanoramaViewer; }
        @Nullable public String getCaptureSoftware() { return mCaptureSoftware; }
        @Nullable public String getStitchingSoftware() { return mStitchingSoftware; }
        @Nullable public String getProjectionType() { return mProjectionType; }
        public float getPoseHeadingDegrees() { return mPoseHeadingDegrees; }
        public float getPosePitchDegrees() { return mPosePitchDegrees; }
        public float getPoseRollDegrees() { return mPoseRollDegrees; }
        @Nullable public Integer getInitialViewHeadingDegrees() {
            return mInitialViewHeadingDegrees;
        }
        @Nullable public Integer getInitialViewPitchDegrees() {
            return mInitialViewPitchDegrees;
        }
        @Nullable public Integer getInitialViewRollDegrees() {
            return mInitialViewRollDegrees;
        }
        public float getInitialHorizontalFovDegrees() { return mInitialHorizontalFovDegrees; }
        public float getInitialVerticalFovDegrees() { return mInitialVerticalFovDegrees; }
        /** When the first source photo was taken, in milliseconds since the epoch, or -1. */
        public long getFirstPhotoDate() { return mFirstPhotoDate; }
        /** When the last source photo was taken, in milliseconds since the epoch, or -1. */
        public long getLastPhotoDate() { return mLastPhotoDate; }
        @Nullable public Integer getSourcePhotosCount() { return mSourcePhotosCount; }
        @Nullable public Boolean getExposureLockUsed() { return mExposureLockUsed; }
        @Nullable public Integer getCroppedAreaImageWidthPixels() {
            return mCroppedAreaImageWidthPixels;
        }
        @Nullable public Integer getCroppedAreaImageHeightPixels() {
            return mCroppedAreaImageHeightPixels;
        }
        @Nullable public Integer getFullPanoWidthPixels() { return mFullPanoWidthPixels; }
        @Nullable public Integer getFullPanoHeightPixels() { return mFullPanoHeightPixels; }
        @Nullable public Integer getCroppedAreaLeftPixels() { return mCroppedAreaLeftPixels; }
        @Nullable public Integer getCroppedAreaTopPixels() { return mCroppedAreaTopPixels; }
        public float getInitialCameraDolly() { return mInitialCameraDolly; }
        @Nullable public Integer getLargestValidInteriorRectLeft() {
            return mLargestValidInteriorRectLeft;
        }
        @Nullable public Integer getLargestValidInteriorRectTop() {
            return mLargestValidInteriorRectTop;
        }
        @Nullable public Integer getLargestValidInteriorRectWidth() {
            return mLargestValidInteriorRectWidth;
        }
        @Nullable public Integer getLargestValidInteriorRectHeight() {
            return mLargestValidInteriorRectHeight;
        }

        /** Each property's value as text, in the order of {@link #NAMES}; null when absent. */
        @NonNull
        private String[] values() {
            return new String[] {bool(mUsePanoramaViewer), mCaptureSoftware, mStitchingSoftware,
                    mProjectionType, real(mPoseHeadingDegrees), real(mPosePitchDegrees),
                    real(mPoseRollDegrees), integer(mInitialViewHeadingDegrees),
                    integer(mInitialViewPitchDegrees), integer(mInitialViewRollDegrees),
                    real(mInitialHorizontalFovDegrees), real(mInitialVerticalFovDegrees),
                    date(mFirstPhotoDate), date(mLastPhotoDate), integer(mSourcePhotosCount),
                    bool(mExposureLockUsed), integer(mCroppedAreaImageWidthPixels),
                    integer(mCroppedAreaImageHeightPixels), integer(mFullPanoWidthPixels),
                    integer(mFullPanoHeightPixels), integer(mCroppedAreaLeftPixels),
                    integer(mCroppedAreaTopPixels), real(mInitialCameraDolly),
                    integer(mLargestValidInteriorRectLeft), integer(mLargestValidInteriorRectTop),
                    integer(mLargestValidInteriorRectWidth),
                    integer(mLargestValidInteriorRectHeight)};
        }

        /** Writes every property that is set into a packet. */
        void writeTo(@NonNull Xmp xmp) {
            String[] values = values();
            for (int i = 0; i < NAMES.length; i++) {
                if (values[i] != null) {
                    xmp.set(Xmp.NS_GPANO, NAMES[i], values[i]);
                }
            }
        }

        /** The metadata a packet carries, or null if it has no GPano property at all. */
        @Nullable
        static Panorama from(@NonNull Xmp xmp) {
            String[] values = new String[NAMES.length];
            boolean found = false;
            for (int i = 0; i < NAMES.length; i++) {
                values[i] = xmp.get(Xmp.NS_GPANO, NAMES[i]);
                found |= values[i] != null;
            }
            if (!found) {
                return null;
            }
            Builder builder = new Builder()
                    .setUsePanoramaViewer(parseBool(values[0]))
                    .setCaptureSoftware(values[1])
                    .setStitchingSoftware(values[2])
                    .setProjectionType(values[3] == null ? null : values[3].trim())
                    .setPoseHeadingDegrees(parseFloat(values[4]))
                    .setPosePitchDegrees(parseFloat(values[5]))
                    .setPoseRollDegrees(parseFloat(values[6]))
                    .setInitialViewHeadingDegrees(parseInteger(values[7]))
                    .setInitialViewPitchDegrees(parseInteger(values[8]))
                    .setInitialViewRollDegrees(parseInteger(values[9]))
                    .setInitialHorizontalFovDegrees(parseFloat(values[10]))
                    .setInitialVerticalFovDegrees(parseFloat(values[11]))
                    .setFirstPhotoDate(parseIsoTime(values[12]))
                    .setLastPhotoDate(parseIsoTime(values[13]))
                    .setSourcePhotosCount(parseInteger(values[14]))
                    .setExposureLockUsed(parseBool(values[15]))
                    .setCroppedAreaImageWidthPixels(parseInteger(values[16]))
                    .setCroppedAreaImageHeightPixels(parseInteger(values[17]))
                    .setFullPanoWidthPixels(parseInteger(values[18]))
                    .setFullPanoHeightPixels(parseInteger(values[19]))
                    .setCroppedAreaLeftPixels(parseInteger(values[20]))
                    .setCroppedAreaTopPixels(parseInteger(values[21]))
                    .setInitialCameraDolly(parseFloat(values[22]))
                    .setLargestValidInteriorRectLeft(parseInteger(values[23]))
                    .setLargestValidInteriorRectTop(parseInteger(values[24]))
                    .setLargestValidInteriorRectWidth(parseInteger(values[25]))
                    .setLargestValidInteriorRectHeight(parseInteger(values[26]));
            return builder.build();
        }

        @Nullable
        private static String bool(@Nullable Boolean value) {
            // XMP spells its booleans with a capital, unlike Java.
            return value == null ? null : value ? "True" : "False";
        }

        @Nullable
        private static String integer(@Nullable Integer value) {
            return value == null ? null : value.toString();
        }

        /** A real number the way XMP wants it: plain digits, never an exponent. */
        @Nullable
        private static String real(float value) {
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                return null;
            }
            return new BigDecimal(value).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros()
                    .toPlainString();
        }

        @Nullable
        private static String date(long millis) {
            if (millis < 0) {
                return null;
            }
            SimpleDateFormat format =
                    new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            return format.format(new Date(millis));
        }

        @Nullable
        private static Boolean parseBool(@Nullable String value) {
            return value == null ? null : Boolean.parseBoolean(value.trim());
        }

        private static float parseFloat(@Nullable String value) {
            try {
                return value == null ? Float.NaN : Float.parseFloat(value.trim());
            } catch (NumberFormatException malformed) {
                return Float.NaN;
            }
        }

        /** An integer, which some writers give a fractional part. */
        @Nullable
        private static Integer parseInteger(@Nullable String value) {
            float parsed = parseFloat(value);
            return Float.isNaN(parsed) || Float.isInfinite(parsed) ? null : Math.round(parsed);
        }

        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof Panorama
                    && Arrays.equals(values(), ((Panorama) other).values());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(values());
        }

        @NonNull
        @Override
        public String toString() {
            StringBuilder text = new StringBuilder("Panorama{");
            String[] values = values();
            for (int i = 0; i < NAMES.length; i++) {
                if (values[i] != null) {
                    text.append(NAMES[i]).append('=').append(values[i]).append(", ");
                }
            }
            return text.append('}').toString();
        }

        /** Builds the metadata. Everything starts absent. */
        public static final class Builder {
            @Nullable private Boolean mUsePanoramaViewer;
            @Nullable private String mCaptureSoftware;
            @Nullable private String mStitchingSoftware;
            @Nullable private String mProjectionType;
            private float mPoseHeadingDegrees = Float.NaN;
            private float mPosePitchDegrees = Float.NaN;
            private float mPoseRollDegrees = Float.NaN;
            @Nullable private Integer mInitialViewHeadingDegrees;
            @Nullable private Integer mInitialViewPitchDegrees;
            @Nullable private Integer mInitialViewRollDegrees;
            private float mInitialHorizontalFovDegrees = Float.NaN;
            private float mInitialVerticalFovDegrees = Float.NaN;
            private long mFirstPhotoDate = -1;
            private long mLastPhotoDate = -1;
            @Nullable private Integer mSourcePhotosCount;
            @Nullable private Boolean mExposureLockUsed;
            @Nullable private Integer mCroppedAreaImageWidthPixels;
            @Nullable private Integer mCroppedAreaImageHeightPixels;
            @Nullable private Integer mFullPanoWidthPixels;
            @Nullable private Integer mFullPanoHeightPixels;
            @Nullable private Integer mCroppedAreaLeftPixels;
            @Nullable private Integer mCroppedAreaTopPixels;
            private float mInitialCameraDolly = Float.NaN;
            @Nullable private Integer mLargestValidInteriorRectLeft;
            @Nullable private Integer mLargestValidInteriorRectTop;
            @Nullable private Integer mLargestValidInteriorRectWidth;
            @Nullable private Integer mLargestValidInteriorRectHeight;

            @NonNull public Builder setUsePanoramaViewer(@Nullable Boolean value) {
                mUsePanoramaViewer = value;
                return this;
            }
            @NonNull public Builder setCaptureSoftware(@Nullable String value) {
                mCaptureSoftware = value;
                return this;
            }
            @NonNull public Builder setStitchingSoftware(@Nullable String value) {
                mStitchingSoftware = value;
                return this;
            }
            /** The projection, which is {@link #PROJECTION_EQUIRECTANGULAR} for photo spheres. */
            @NonNull public Builder setProjectionType(@Nullable String value) {
                mProjectionType = value;
                return this;
            }
            @NonNull public Builder setPoseHeadingDegrees(float value) {
                mPoseHeadingDegrees = value;
                return this;
            }
            @NonNull public Builder setPosePitchDegrees(float value) {
                mPosePitchDegrees = value;
                return this;
            }
            @NonNull public Builder setPoseRollDegrees(float value) {
                mPoseRollDegrees = value;
                return this;
            }
            @NonNull public Builder setInitialViewHeadingDegrees(@Nullable Integer value) {
                mInitialViewHeadingDegrees = value;
                return this;
            }
            @NonNull public Builder setInitialViewPitchDegrees(@Nullable Integer value) {
                mInitialViewPitchDegrees = value;
                return this;
            }
            @NonNull public Builder setInitialViewRollDegrees(@Nullable Integer value) {
                mInitialViewRollDegrees = value;
                return this;
            }
            @NonNull public Builder setInitialHorizontalFovDegrees(float value) {
                mInitialHorizontalFovDegrees = value;
                return this;
            }
            @NonNull public Builder setInitialVerticalFovDegrees(float value) {
                mInitialVerticalFovDegrees = value;
                return this;
            }
            /** In milliseconds since the epoch, or -1 for none. */
            @NonNull public Builder setFirstPhotoDate(long millis) {
                mFirstPhotoDate = millis;
                return this;
            }
            /** In milliseconds since the epoch, or -1 for none. */
            @NonNull public Builder setLastPhotoDate(long millis) {
                mLastPhotoDate = millis;
                return this;
            }
            @NonNull public Builder setSourcePhotosCount(@Nullable Integer value) {
                mSourcePhotosCount = value;
                return this;
            }
            @NonNull public Builder setExposureLockUsed(@Nullable Boolean value) {
                mExposureLockUsed = value;
                return this;
            }
            @NonNull public Builder setCroppedAreaImageWidthPixels(@Nullable Integer value) {
                mCroppedAreaImageWidthPixels = value;
                return this;
            }
            @NonNull public Builder setCroppedAreaImageHeightPixels(@Nullable Integer value) {
                mCroppedAreaImageHeightPixels = value;
                return this;
            }
            @NonNull public Builder setFullPanoWidthPixels(@Nullable Integer value) {
                mFullPanoWidthPixels = value;
                return this;
            }
            @NonNull public Builder setFullPanoHeightPixels(@Nullable Integer value) {
                mFullPanoHeightPixels = value;
                return this;
            }
            @NonNull public Builder setCroppedAreaLeftPixels(@Nullable Integer value) {
                mCroppedAreaLeftPixels = value;
                return this;
            }
            @NonNull public Builder setCroppedAreaTopPixels(@Nullable Integer value) {
                mCroppedAreaTopPixels = value;
                return this;
            }
            @NonNull public Builder setInitialCameraDolly(float value) {
                mInitialCameraDolly = value;
                return this;
            }
            @NonNull public Builder setLargestValidInteriorRectLeft(@Nullable Integer value) {
                mLargestValidInteriorRectLeft = value;
                return this;
            }
            @NonNull public Builder setLargestValidInteriorRectTop(@Nullable Integer value) {
                mLargestValidInteriorRectTop = value;
                return this;
            }
            @NonNull public Builder setLargestValidInteriorRectWidth(@Nullable Integer value) {
                mLargestValidInteriorRectWidth = value;
                return this;
            }
            @NonNull public Builder setLargestValidInteriorRectHeight(@Nullable Integer value) {
                mLargestValidInteriorRectHeight = value;
                return this;
            }

            @NonNull
            public Panorama build() {
                return new Panorama(this);
            }
        }
    }

    /**
     * Where a motion photo keeps its clip: an MP4 inside the picture's file, which a player can
     * open at its offset and length.
     */
    public static final class MotionPhoto {
        /** The forms a motion photo's clip is described in. */
        @IntDef({FORMAT_MOTION_PHOTO, FORMAT_MICRO_VIDEO, FORMAT_SAMSUNG})
        @Retention(RetentionPolicy.SOURCE)
        public @interface MotionFormat {}

        /** Google's motion photo, described by its XMP container directory. */
        public static final int FORMAT_MOTION_PHOTO = 1;
        /** Google's older micro video, described by an offset from the end of the file. */
        public static final int FORMAT_MICRO_VIDEO = 2;
        /** Samsung's, in the trailer its cameras append. */
        public static final int FORMAT_SAMSUNG = 3;

        /** The form. */
        @MotionFormat private final int mFormat;
        /** The form's version, or 1 when it does not say. */
        private final int mVersion;
        /** Where the clip starts in the file. */
        private final long mVideoOffset;
        /** How long the clip is, in bytes. */
        private final long mVideoLength;
        /** The time in the clip the still was taken at, in microseconds, or -1. */
        private final long mPresentationTimestampUs;

        MotionPhoto(@MotionFormat int format, int version, long videoOffset, long videoLength,
                long presentationTimestampUs) {
            mFormat = format;
            mVersion = version;
            mVideoOffset = videoOffset;
            mVideoLength = videoLength;
            mPresentationTimestampUs = presentationTimestampUs;
        }

        /** The form the clip is described in. */
        @MotionFormat
        public int getFormat() {
            return mFormat;
        }

        /** The version of that form, or 1 when it does not say. */
        public int getVersion() {
            return mVersion;
        }

        /** Where the clip starts in the file, in bytes. */
        public long getVideoOffset() {
            return mVideoOffset;
        }

        /** How long the clip is, in bytes. */
        public long getVideoLength() {
            return mVideoLength;
        }

        /** The time in the clip the still was taken at, in microseconds, or -1. */
        public long getPresentationTimestampUs() {
            return mPresentationTimestampUs;
        }

        /** Whether an MP4 starts at an offset: its first box an ftyp. */
        static boolean isVideoAt(@NonNull ByteSource source, long offset) {
            try {
                if (offset < 0 || offset + 8 > source.length()) {
                    return false;
                }
                byte[] header = source.bytes(offset, 8);
                return Bytes.startsWith(header, 4, "ftyp");
            } catch (IOException unreadable) {
                return false;
            }
        }

        /** The clip a packet describes, if it is there, or null. */
        @Nullable
        static MotionPhoto describedBy(@NonNull Xmp xmp, @NonNull ByteSource source) {
            long length = source.length();
            if ("1".equals(trim(xmp.get(Xmp.NS_CAMERA, "MotionPhoto")))) {
                List<Map<String, String>> items = xmp.getStructs(Xmp.NS_CONTAINER, "Directory");
                long end = length;
                long start = length;
                for (int i = items.size() - 1; i > 0; i--) {
                    Map<String, String> item = items.get(i);
                    long itemLength = parseLong(item.get(Xmp.NS_ITEM + "Length"));
                    long padding = parseLong(item.get(Xmp.NS_ITEM + "Padding"));
                    end = start;
                    start = end - Math.max(0, itemLength);
                    String mime = item.get(Xmp.NS_ITEM + "Mime");
                    String semantic = item.get(Xmp.NS_ITEM + "Semantic");
                    if (("MotionPhoto".equals(semantic) || (mime != null
                            && mime.startsWith("video/"))) && itemLength > 0) {
                        if (isVideoAt(source, start)) {
                            return new MotionPhoto(FORMAT_MOTION_PHOTO,
                                    (int) Math.max(1, parseLong(
                                            xmp.get(Xmp.NS_CAMERA, "MotionPhotoVersion"))),
                                    start, itemLength, presentation(xmp,
                                    "MotionPhotoPresentationTimestampUs"));
                        }
                    }
                    start -= Math.max(0, padding);
                }
            }
            if ("1".equals(trim(xmp.get(Xmp.NS_CAMERA, "MicroVideo")))) {
                long offset = parseLong(xmp.get(Xmp.NS_CAMERA, "MicroVideoOffset"));
                if (offset > 0 && offset < length && isVideoAt(source, length - offset)) {
                    return new MotionPhoto(FORMAT_MICRO_VIDEO, (int) Math.max(1,
                            parseLong(xmp.get(Xmp.NS_CAMERA, "MicroVideoVersion"))),
                            length - offset, offset,
                            presentation(xmp, "MicroVideoPresentationTimestampUs"));
                }
            }
            return null;
        }

        private static long presentation(@NonNull Xmp xmp, @NonNull String name) {
            long value = parseLong(xmp.get(Xmp.NS_CAMERA, name));
            return value < 0 ? -1 : value;
        }

        @Nullable
        private static String trim(@Nullable String value) {
            return value == null ? null : value.trim();
        }

        private static long parseLong(@Nullable String value) {
            try {
                return value == null ? -1 : Long.parseLong(value.trim());
            } catch (NumberFormatException malformed) {
                return -1;
            }
        }

        @NonNull
        @Override
        public String toString() {
            return "MotionPhoto{format=" + mFormat + ", version=" + mVersion + ", offset="
                    + mVideoOffset + ", length=" + mVideoLength + ", presentationUs="
                    + mPresentationTimestampUs + "}";
        }
    }

    /**
     * What makes a video a 360° video: Google's spherical video metadata, in either of its
     * versions.
     */
    public static final class SphericalVideo {
        /** How the sphere is laid out in each frame. */
        @IntDef({PROJECTION_EQUIRECTANGULAR, PROJECTION_CUBEMAP, PROJECTION_MESH,
                PROJECTION_OTHER})
        @Retention(RetentionPolicy.SOURCE)
        public @interface Projection {}

        /** Latitude and longitude, as a photo sphere is. */
        public static final int PROJECTION_EQUIRECTANGULAR = 1;
        /** The six faces of a cube. */
        public static final int PROJECTION_CUBEMAP = 2;
        /** A mesh the file defines. */
        public static final int PROJECTION_MESH = 3;
        /** Something else. */
        public static final int PROJECTION_OTHER = 4;

        /** Whether each frame holds one view or one for each eye. */
        @IntDef({STEREO_MONO, STEREO_TOP_BOTTOM, STEREO_LEFT_RIGHT})
        @Retention(RetentionPolicy.SOURCE)
        public @interface StereoMode {}

        /** One view. */
        public static final int STEREO_MONO = 0;
        /** The left eye's view above the right eye's. */
        public static final int STEREO_TOP_BOTTOM = 1;
        /** The left eye's view beside the right eye's. */
        public static final int STEREO_LEFT_RIGHT = 2;

        /** The projection. */
        @Projection private final int mProjection;
        /** The stereo layout. */
        @StereoMode private final int mStereoMode;
        /** The compass heading of the middle of the frame, in degrees. */
        private final float mPoseYawDegrees;
        /** How far the middle is above the horizon. */
        private final float mPosePitchDegrees;
        /** How far it is rolled. */
        private final float mPoseRollDegrees;
        /** How much of the sphere's height is missing above the frame, as a fraction. */
        private final double mCropTop;
        /** And below it. */
        private final double mCropBottom;
        /** And of its width, to its left. */
        private final double mCropLeft;
        /** And to its right. */
        private final double mCropRight;

        SphericalVideo(@Projection int projection, int stereoMode, float poseYawDegrees,
                float posePitchDegrees, float poseRollDegrees, double cropTop,
                double cropBottom, double cropLeft, double cropRight) {
            mProjection = projection;
            mStereoMode = stereoMode == STEREO_TOP_BOTTOM || stereoMode == STEREO_LEFT_RIGHT
                    ? stereoMode : STEREO_MONO;
            mPoseYawDegrees = poseYawDegrees;
            mPosePitchDegrees = posePitchDegrees;
            mPoseRollDegrees = poseRollDegrees;
            mCropTop = cropTop;
            mCropBottom = cropBottom;
            mCropLeft = cropLeft;
            mCropRight = cropRight;
        }

        @Projection public int getProjection() { return mProjection; }
        @StereoMode public int getStereoMode() { return mStereoMode; }
        public float getPoseYawDegrees() { return mPoseYawDegrees; }
        public float getPosePitchDegrees() { return mPosePitchDegrees; }
        public float getPoseRollDegrees() { return mPoseRollDegrees; }
        /** How much of the sphere's height is missing above the frame, as a fraction. */
        public double getCropTop() { return mCropTop; }
        /** How much of the sphere's height is missing below the frame. */
        public double getCropBottom() { return mCropBottom; }
        /** How much of the sphere's width is missing to the frame's left. */
        public double getCropLeft() { return mCropLeft; }
        /** How much of the sphere's width is missing to the frame's right. */
        public double getCropRight() { return mCropRight; }

        /** Version 1, an RDF document in the video track, or null if it says not spherical. */
        @Nullable
        static SphericalVideo fromVersion1(@NonNull Xmp document) {
            String spherical = document.get(Xmp.NS_SPHERICAL, "Spherical");
            if (spherical == null || !Boolean.parseBoolean(spherical.trim())) {
                return null;
            }
            String projection = document.get(Xmp.NS_SPHERICAL, "ProjectionType");
            String stereo = document.get(Xmp.NS_SPHERICAL, "StereoMode");
            int mode = "top-bottom".equalsIgnoreCase(trim(stereo)) ? STEREO_TOP_BOTTOM
                    : "left-right".equalsIgnoreCase(trim(stereo)) ? STEREO_LEFT_RIGHT
                    : STEREO_MONO;
            double fullWidth = number(document, "FullPanoWidthPixels");
            double fullHeight = number(document, "FullPanoHeightPixels");
            double width = number(document, "CroppedAreaImageWidthPixels");
            double height = number(document, "CroppedAreaImageHeightPixels");
            double left = number(document, "CroppedAreaLeftPixels");
            double top = number(document, "CroppedAreaTopPixels");
            double[] crop = {0, 0, 0, 0};
            if (fullWidth > 0 && fullHeight > 0 && width > 0 && height > 0) {
                crop[0] = clamp(top / fullHeight);
                crop[1] = clamp((fullHeight - top - height) / fullHeight);
                crop[2] = clamp(left / fullWidth);
                crop[3] = clamp((fullWidth - left - width) / fullWidth);
            }
            return new SphericalVideo(projection == null || "equirectangular"
                    .equalsIgnoreCase(projection.trim()) ? PROJECTION_EQUIRECTANGULAR
                    : PROJECTION_OTHER, mode, (float) orZero(number(document,
                    "InitialViewHeadingDegrees")), (float) orZero(number(document,
                    "InitialViewPitchDegrees")), (float) orZero(number(document,
                    "InitialViewRollDegrees")), crop[0], crop[1], crop[2], crop[3]);
        }

        @Nullable
        private static String trim(@Nullable String value) {
            return value == null ? null : value.trim();
        }

        private static double number(@NonNull Xmp document, @NonNull String name) {
            return ExifCaptureParameters.number(document.get(Xmp.NS_SPHERICAL, name));
        }

        private static double orZero(double value) {
            return Double.isNaN(value) ? 0 : value;
        }

        private static double clamp(double value) {
            return Double.isNaN(value) ? 0 : Math.max(0, Math.min(1, value));
        }

        @NonNull
        @Override
        public String toString() {
            return "SphericalVideo{projection=" + mProjection + ", stereo=" + mStereoMode
                    + ", pose=" + mPoseYawDegrees + "/" + mPosePitchDegrees + "/"
                    + mPoseRollDegrees + ", crop=" + mCropTop + "/" + mCropBottom + "/"
                    + mCropLeft + "/" + mCropRight + "}";
        }
    }

    /**
     * The settings a camera recorded with a still, or the frame rate a slow-motion video was
     * captured at. Each is NaN, -1 or null when not recorded.
     */
    public static final class CaptureSettings {
        /** The exposure time in seconds, or NaN. */
        private final double mExposureTime;
        /** The f-number, or NaN. */
        private final double mFNumber;
        /** The ISO sensitivity, or -1. */
        private final int mIsoSensitivity;
        /** The lens's focal length in millimetres, or NaN. */
        private final double mFocalLength;
        /** The focal length a full-frame camera would need for the same view, or -1. */
        private final int mFocalLengthIn35mmFilm;
        /** The Exif flash bits, or -1. */
        private final int mFlash;
        /** The Exif white balance, 0 automatic or 1 manual, or -1. */
        private final int mWhiteBalance;
        /** The exposure compensation in stops, or NaN. */
        private final double mExposureBias;
        /** The digital zoom ratio, or NaN. */
        private final double mDigitalZoomRatio;
        /** The lens's maker, or null. */
        @Nullable private final String mLensMake;
        /** The lens's model, or null. */
        @Nullable private final String mLensModel;
        /** The frame rate a video was captured at before it plays back slower, or NaN. */
        private final float mCaptureFrameRate;

        private CaptureSettings(double exposureTime, double fNumber, int isoSensitivity,
                double focalLength, int focalLengthIn35mmFilm, int flash, int whiteBalance,
                double exposureBias, double digitalZoomRatio, @Nullable String lensMake,
                @Nullable String lensModel, float captureFrameRate) {
            mExposureTime = exposureTime;
            mFNumber = fNumber;
            mIsoSensitivity = isoSensitivity;
            mFocalLength = focalLength;
            mFocalLengthIn35mmFilm = focalLengthIn35mmFilm;
            mFlash = flash;
            mWhiteBalance = whiteBalance;
            mExposureBias = exposureBias;
            mDigitalZoomRatio = digitalZoomRatio;
            mLensMake = lensMake;
            mLensModel = lensModel;
            mCaptureFrameRate = captureFrameRate;
        }

        /** The exposure time in seconds, or NaN. */
        public double getExposureTime() { return mExposureTime; }
        /** The f-number, or NaN. */
        public double getFNumber() { return mFNumber; }
        /** The ISO sensitivity, or -1. */
        public int getIsoSensitivity() { return mIsoSensitivity; }
        /** The lens's focal length in millimetres, or NaN. */
        public double getFocalLength() { return mFocalLength; }
        /** The focal length a full-frame camera would need for the same view, or -1. */
        public int getFocalLengthIn35mmFilm() { return mFocalLengthIn35mmFilm; }
        /** The flash, as Exif's Flash tag records it, or -1. */
        public int getFlash() { return mFlash; }
        /** Whether the flash fired, from {@link #getFlash()}. */
        public boolean isFlashFired() { return mFlash > 0 && (mFlash & 1) != 0; }
        /** The white balance, 0 automatic or 1 manual, or -1. */
        public int getWhiteBalance() { return mWhiteBalance; }
        /** The exposure compensation in stops, or NaN. */
        public double getExposureBias() { return mExposureBias; }
        /** The digital zoom ratio, or NaN. */
        public double getDigitalZoomRatio() { return mDigitalZoomRatio; }
        @Nullable public String getLensMake() { return mLensMake; }
        @Nullable public String getLensModel() { return mLensModel; }
        /** The frame rate a video was captured at before it plays back slower, or NaN. */
        public float getCaptureFrameRate() { return mCaptureFrameRate; }

        /** The settings a picture's Exif records, or null if it records none. */
        @Nullable
        static CaptureSettings of(@NonNull Exif exif) {
            CaptureSettings settings = new CaptureSettings(
                    number(exif, ExifInterface.TAG_EXPOSURE_TIME),
                    number(exif, ExifInterface.TAG_F_NUMBER),
                    integer(exif, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY),
                    number(exif, ExifInterface.TAG_FOCAL_LENGTH),
                    integer(exif, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM),
                    integer(exif, ExifInterface.TAG_FLASH),
                    integer(exif, ExifInterface.TAG_WHITE_BALANCE),
                    number(exif, ExifInterface.TAG_EXPOSURE_BIAS_VALUE),
                    number(exif, ExifInterface.TAG_DIGITAL_ZOOM_RATIO),
                    text(exif, ExifInterface.TAG_LENS_MAKE),
                    text(exif, ExifInterface.TAG_LENS_MODEL), Float.NaN);
            return settings.isEmpty() ? null : settings;
        }

        /** A video's capture frame rate. */
        @NonNull
        static CaptureSettings ofFrameRate(float rate) {
            return new CaptureSettings(Double.NaN, Double.NaN, -1, Double.NaN, -1, -1, -1,
                    Double.NaN, Double.NaN, null, null, rate);
        }

        private boolean isEmpty() {
            return Double.isNaN(mExposureTime) && Double.isNaN(mFNumber) && mIsoSensitivity < 0
                    && Double.isNaN(mFocalLength) && mFocalLengthIn35mmFilm < 0 && mFlash < 0
                    && mWhiteBalance < 0 && Double.isNaN(mExposureBias)
                    && Double.isNaN(mDigitalZoomRatio) && mLensMake == null
                    && mLensModel == null && Float.isNaN(mCaptureFrameRate);
        }

        private static double number(@NonNull Exif exif, @NonNull String tag) {
            return ExifCaptureParameters.number(exif.getAttribute(tag));
        }

        private static int integer(@NonNull Exif exif, @NonNull String tag) {
            double value = number(exif, tag);
            return Double.isNaN(value) ? -1 : (int) Math.round(value);
        }

        @Nullable
        private static String text(@NonNull Exif exif, @NonNull String tag) {
            String value = exif.getAttribute(tag);
            return value == null || value.trim().isEmpty() ? null : value.trim();
        }

        @NonNull
        @Override
        public String toString() {
            return "CaptureSettings{exposure=" + mExposureTime + ", f=" + mFNumber + ", iso="
                    + mIsoSensitivity + ", focal=" + mFocalLength + ", focal35="
                    + mFocalLengthIn35mmFilm + ", flash=" + mFlash + ", whiteBalance="
                    + mWhiteBalance + ", bias=" + mExposureBias + ", zoom="
                    + mDigitalZoomRatio + ", lens=" + mLensMake + " " + mLensModel
                    + ", captureFps=" + mCaptureFrameRate + "}";
        }
    }
}
