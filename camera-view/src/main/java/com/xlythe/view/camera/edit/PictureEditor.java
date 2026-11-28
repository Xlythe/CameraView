package com.xlythe.view.camera.edit;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.IntDef;
import androidx.annotation.IntRange;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import com.xlythe.view.camera.EditorMetadata;
import com.xlythe.view.camera.Exif;
import com.xlythe.view.camera.MediaTags;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashMap;
import java.util.Map;

/**
 * Applies a {@link PictureEdits} to a picture, writing the result to a second file and leaving
 * the original alone. It also converts between formats: a picture with no edits is written in
 * the format asked for, and a DNG becomes a picture any app can open.
 *
 * <p>A fire-once class, like {@link VideoEditor}: construct one per picture, {@link #start()}
 * it, and the result arrives on the main thread through an {@link OnPictureEditedListener}.
 * {@link #cancel()} abandons an edit in progress, and reports nothing.</p>
 *
 * <pre>{@code
 * PictureEditor editor = new PictureEditor(context, picture, edited, new PictureEdits.Builder()
 *         .setRotationDegrees(90)
 *         .setColorMatrix(ColorMatrix.grayscale())
 *         .build());
 * editor.setOutputFormat(PictureEditor.FORMAT_WEBP);
 * editor.setOnPictureEditedListener(listener);
 * editor.start();
 * }</pre>
 *
 * <h2>What comes through</h2>
 * <ul>
 *   <li><b>Resolution:</b> the edit is made at the picture's full resolution, unless
 *   {@link #setMaxOutputPixels} asks for less. One that does not fit in memory fails rather
 *   than being made smaller.</li>
 *   <li><b>Turns and flips alone</b> change only what the file says about which way up it goes,
 *   so the picture's own pixels are kept exactly, as long as the format stays the same and can
 *   say it.</li>
 *   <li><b>Metadata:</b> everything {@link MediaTags#copyTo} copies, with the picture marked
 *   upright, its size and an Exif thumbnail updated, and a panorama's photo sphere metadata
 *   moved with a crop. A panorama that is turned, flipped or straightened is no longer a
 *   panorama, and loses it.</li>
 *   <li><b>HDR:</b> an Ultra HDR picture written as a JPEG keeps its gain map, from Android 14,
 *   edited as the picture is. Other formats are written in standard range.</li>
 *   <li><b>Motion photos:</b> see {@link #setMotionPhotoPolicy}. A clip goes only into a JPEG or
 *   HEIF.</li>
 * </ul>
 */
public class PictureEditor {
    /** The tag this logs under. */
    private static final String TAG = "PictureEditor";

    /** The formats a picture can be written in. */
    @IntDef({FORMAT_SAME, FORMAT_JPEG, FORMAT_PNG, FORMAT_WEBP, FORMAT_HEIF, FORMAT_AVIF})
    @Retention(RetentionPolicy.SOURCE)
    public @interface OutputFormat {}

    /**
     * The picture's own format where it can be written, and otherwise JPEG, or PNG for a GIF.
     * The default.
     */
    public static final int FORMAT_SAME = 0;
    public static final int FORMAT_JPEG = MediaTags.FORMAT_JPEG;
    /** PNG, which is lossless and keeps transparency. */
    public static final int FORMAT_PNG = MediaTags.FORMAT_PNG;
    /** WebP, lossy below quality 100 and lossless at it, keeping transparency. */
    public static final int FORMAT_WEBP = MediaTags.FORMAT_WEBP;
    /** HEIF, from Android 9 on a device with an HEVC encoder. */
    public static final int FORMAT_HEIF = MediaTags.FORMAT_HEIF;
    /** AVIF, from Android 14 on a device with an AV1 encoder. */
    public static final int FORMAT_AVIF = MediaTags.FORMAT_AVIF;

    /** What becomes of a motion photo's clip. */
    @IntDef({MOTION_PHOTO_AUTO, MOTION_PHOTO_KEEP, MOTION_PHOTO_DROP})
    @Retention(RetentionPolicy.SOURCE)
    public @interface MotionPhotoPolicy {}

    /**
     * The clip is kept when it still matches the picture: a quarter turn turns it too, but a
     * crop, a straightening or a flip, which the clip cannot follow, drops it. The default.
     */
    public static final int MOTION_PHOTO_AUTO = 0;
    /** The clip is always kept, turned with the picture where it can be, otherwise as it was. */
    public static final int MOTION_PHOTO_KEEP = 1;
    /** The clip is left out, along with everything that said it was there. */
    public static final int MOTION_PHOTO_DROP = 2;

    /** The quality a lossy format is written at unless asked otherwise. */
    @VisibleForTesting
    static final int DEFAULT_QUALITY = 95;

    /** The widest and tallest WebP can be. */
    private static final int MAX_WEBP_SIDE = 16383;

    /** The widest and tallest JPEG can be. */
    private static final int MAX_JPEG_SIDE = 65535;

    /** The largest share of the memory free that the pictures of one edit may take. */
    private static final float MEMORY_SHARE = 0.5f;

    /** The Exif tags for the picture's width and height. */
    private static final int TAG_PIXEL_X_DIMENSION = 0xA002;
    private static final int TAG_PIXEL_Y_DIMENSION = 0xA003;

    /** What Samsung puts in front of a motion photo's clip in its trailer. */
    private static final String SAMSUNG_CLIP_NAME = "MotionPhoto_Data";

    /** The bytes of the record header in front of that name. */
    private static final int SAMSUNG_RECORD_HEADER = 8;

    /** Encoders other modules add, by format. */
    private static final Map<Integer, Encoder> ADDED_ENCODERS = new HashMap<>();

    /** Delivers the result on the main thread, where the rest of the library's callbacks land. */
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    /** The app's context. */
    @NonNull private final Context mContext;
    /** The picture to read, which is never written to. */
    @NonNull private final Uri mSource;
    /** Where the edited picture goes. */
    @NonNull private final File mOutput;
    /** What to change. */
    @NonNull private final PictureEdits mEdits;

    /** Told how the edit went. */
    @Nullable private OnPictureEditedListener mListener;
    /** The format to write. */
    @OutputFormat private int mFormat = FORMAT_SAME;
    /** The quality of a lossy format. */
    private int mQuality = DEFAULT_QUALITY;
    /** The most pixels the result may have, or 0 for full resolution. */
    private long mMaxOutputPixels;
    /** What becomes of a motion photo's clip. */
    @MotionPhotoPolicy private int mMotionPhotoPolicy = MOTION_PHOTO_AUTO;

    /** True once {@link #start()} has been called. */
    private boolean mStarted;
    /** True while an edit is in flight. */
    private boolean mRunning;
    /** Set by {@link #cancel()}, read by the worker between steps. */
    private volatile boolean mCancelled;

    /**
     * @param source the picture to read, a file or content Uri, which is never written to
     * @param output where to write the edited picture, overwritten if it already exists
     * @param edits what to change
     */
    public PictureEditor(@NonNull Context context, @NonNull Uri source, @NonNull File output,
            @NonNull PictureEdits edits) {
        mContext = context.getApplicationContext();
        mSource = source;
        mOutput = output;
        mEdits = edits;
    }

    /** Says how the edit went, on the main thread. */
    @MainThread
    public void setOnPictureEditedListener(@Nullable OnPictureEditedListener listener) {
        mListener = listener;
    }

    @MainThread
    @Nullable
    public OnPictureEditedListener getOnPictureEditedListener() {
        return mListener;
    }

    /**
     * The format to write the picture in: {@link #FORMAT_SAME} by default. A format the device
     * cannot write, which {@link #isOutputFormatSupported} answers, makes the edit fail.
     */
    @MainThread
    public void setOutputFormat(@OutputFormat int format) {
        mFormat = format;
    }

    @MainThread
    @OutputFormat
    public int getOutputFormat() {
        return mFormat;
    }

    /** The quality of a lossy format, from 0 to 100: 95 by default. */
    @MainThread
    public void setQuality(@IntRange(from = 0, to = 100) int quality) {
        if (quality < 0 || quality > 100) {
            throw new IllegalArgumentException("Quality is 0 to 100: " + quality);
        }
        mQuality = quality;
    }

    @MainThread
    public int getQuality() {
        return mQuality;
    }

    /**
     * The most pixels the edited picture may have: a larger one is scaled down to fit, keeping
     * its proportions. 0, the default, keeps the full resolution.
     */
    @MainThread
    public void setMaxOutputPixels(@IntRange(from = 0) long pixels) {
        if (pixels < 0) {
            throw new IllegalArgumentException("No fewer than 0 pixels: " + pixels);
        }
        mMaxOutputPixels = pixels;
    }

    @MainThread
    public long getMaxOutputPixels() {
        return mMaxOutputPixels;
    }

    /** What becomes of a motion photo's clip: {@link #MOTION_PHOTO_AUTO} by default. */
    @MainThread
    public void setMotionPhotoPolicy(@MotionPhotoPolicy int policy) {
        mMotionPhotoPolicy = policy;
    }

    @MainThread
    @MotionPhotoPolicy
    public int getMotionPhotoPolicy() {
        return mMotionPhotoPolicy;
    }

    /** Begins the edit, on a thread of its own. Call once; a second call is ignored. */
    @MainThread
    public void start() {
        if (mStarted) {
            Log.w(TAG, "PictureEditor cannot be started more than once");
            return;
        }
        mStarted = true;
        mRunning = true;
        new Thread(this::run, TAG).start();
    }

    /** Abandons an edit in progress. Nothing is written, and no callback follows. */
    @MainThread
    public void cancel() {
        if (!mRunning) {
            return;
        }
        mRunning = false;
        mCancelled = true;
    }

    /** True between {@link #start()} and the callback that ends the edit. */
    @MainThread
    public boolean isRunning() {
        return mRunning;
    }

    /**
     * Whether this device can write pictures in a format. JPEG, PNG and WebP always can; HEIF
     * and AVIF need an encoder that not every device has.
     */
    @WorkerThread
    public static boolean isOutputFormatSupported(@OutputFormat int format) {
        switch (format) {
            case FORMAT_SAME:
            case FORMAT_JPEG:
            case FORMAT_PNG:
            case FORMAT_WEBP:
                return true;
            case FORMAT_HEIF:
                return Build.VERSION.SDK_INT >= 28 && HeifEncoder.isSupported(false);
            case FORMAT_AVIF:
                return Build.VERSION.SDK_INT >= 34 && HeifEncoder.isSupported(true);
            default:
                synchronized (ADDED_ENCODERS) {
                    return ADDED_ENCODERS.containsKey(format);
                }
        }
    }

    /**
     * Adds a format for another module of this library to write, such as one with an encoder of
     * its own. Its metadata is written by {@link MediaTags} where it knows the format.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    @AnyThread
    public static void registerEncoder(int format, @NonNull Encoder encoder) {
        synchronized (ADDED_ENCODERS) {
            ADDED_ENCODERS.put(format, encoder);
        }
    }

    // ---------- The edit ----------

    /** Makes the edit, then reports it. */
    @WorkerThread
    private void run() {
        File temporary = null;
        try {
            ensureWritableTempDir(mContext);
            File directory = mOutput.getAbsoluteFile().getParentFile();
            if (directory != null && !directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("Unable to make " + directory);
            }
            temporary = File.createTempFile("picture", ".edit", directory);
            edit(temporary);
            checkCancelled();
            if (!temporary.renameTo(mOutput)) {
                throw new IOException("Unable to move the edit to " + mOutput);
            }
            temporary = null;
            report(true);
        } catch (CancelledException cancelled) {
            Log.d(TAG, "Editing " + mSource + " was cancelled");
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "Unable to edit " + mSource, e);
            report(false);
        } finally {
            if (temporary != null && temporary.exists() && !temporary.delete()) {
                Log.w(TAG, "Unable to delete " + temporary);
            }
        }
    }

    /** Writes the edited picture into a file. */
    @VisibleForTesting
    @WorkerThread
    void edit(@NonNull File destination) throws IOException {
        MediaTags tags = null;
        try {
            tags = MediaTags.open(mContext, mSource);
        } catch (IOException e) {
            Log.w(TAG, "Unable to read the metadata of " + mSource, e);
        }
        int sourceFormat = tags == null ? MediaTags.FORMAT_UNKNOWN : tags.getFormat();
        int format = resolveFormat(mFormat, sourceFormat);
        checkCancelled();
        if (format == sourceFormat && tags != null && mEdits.changesOnlyOrientation()
                && mMaxOutputPixels == 0 && editOrientation(tags, destination)) {
            return;
        }
        checkCancelled();
        reencode(tags, format, destination);
    }

    /** The format a picture is written in. */
    @VisibleForTesting
    static int resolveFormat(@OutputFormat int asked, @MediaTags.Format int source) {
        if (asked != FORMAT_SAME) {
            return asked;
        }
        switch (source) {
            case MediaTags.FORMAT_PNG:
            case MediaTags.FORMAT_WEBP:
            case MediaTags.FORMAT_JPEG:
                return source;
            case MediaTags.FORMAT_HEIF:
            case MediaTags.FORMAT_AVIF:
                return isOutputFormatSupported(source) ? source : FORMAT_JPEG;
            case MediaTags.FORMAT_GIF:
                return FORMAT_PNG;
            default:
                return FORMAT_JPEG;
        }
    }

    /**
     * Saves turns and flips as the file's orientation alone, leaving its pixels as they are.
     *
     * @return false if the format cannot say the new orientation, and nothing was made
     */
    @WorkerThread
    private boolean editOrientation(@NonNull MediaTags source, @NonNull File destination)
            throws IOException {
        copySource(destination);
        int[] orientation = PictureGeometry.combine(source.getRotation(), source.isMirrored(),
                mEdits.getRotationDegrees(), mEdits.isFlippedHorizontally(),
                mEdits.isFlippedVertically());
        File clip = cutClip(source, destination);
        try {
            MediaTags tags = MediaTags.open(destination);
            boolean turned = orientation[0] != source.getRotation()
                    || (orientation[1] == 1) != source.isMirrored();
            if (turned && !tags.setOrientation(orientation[0], orientation[1] == 1)) {
                Log.d(TAG, "The picture cannot say its new orientation, so it is redrawn");
                return false;
            }
            describeClip(tags, source, clip);
            tags.save();
            return true;
        } finally {
            delete(clip);
        }
    }

    /** Draws the edited picture, and writes it and its metadata. */
    @WorkerThread
    private void reencode(@Nullable MediaTags tags, int format, @NonNull File destination)
            throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = openSource()) {
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException(mSource + " is not a picture this device can decode");
        }
        int rotation = tags == null ? 0 : tags.getRotation();
        boolean mirrored = tags != null && tags.isMirrored();
        PictureGeometry geometry = new PictureGeometry(bounds.outWidth, bounds.outHeight,
                rotation, mirrored, mEdits);
        long fullPixels = (long) geometry.getOutputWidth() * geometry.getOutputHeight();
        float scale = mMaxOutputPixels > 0 && fullPixels > mMaxOutputPixels
                ? (float) Math.sqrt(mMaxOutputPixels / (double) fullPixels) : 1f;
        int width = Math.max(1, (int) Math.floor(geometry.getOutputWidth() * scale));
        int height = Math.max(1, (int) Math.floor(geometry.getOutputHeight() * scale));
        int maxSide = format == FORMAT_WEBP ? MAX_WEBP_SIDE
                : format == FORMAT_JPEG ? MAX_JPEG_SIDE : Integer.MAX_VALUE;
        if (width > maxSide || height > maxSide) {
            throw new IOException("A " + width + "x" + height + " picture is too large for the "
                    + "format; set a maximum number of pixels");
        }
        int sample = sampleSize(scale);
        Rect region = geometry.storedRegion();
        long budget = memoryBudget(mContext);
        long needed = 4L * (pixels(region.width(), region.height(), sample)
                + (long) width * height);
        if (needed > budget) {
            throw new IOException("Editing " + mSource + " at " + width + "x" + height
                    + " needs " + needed + " bytes, but only " + budget + " are free; set a "
                    + "maximum number of pixels");
        }
        boolean wantsGainmap = Build.VERSION.SDK_INT >= 34 && format == FORMAT_JPEG
                && tags != null && (tags.getGainMap() == MediaTags.GAIN_MAP_ULTRA_HDR
                        || tags.getGainMap() == MediaTags.GAIN_MAP_ISO_21496_1);
        Decoded decoded = decode(region, sample, bounds, wantsGainmap, budget,
                4L * width * height);
        checkCancelled();

        Matrix decodedToOutput = new Matrix();
        decodedToOutput.setScale(decoded.region.width() / (float) decoded.bitmap.getWidth(),
                decoded.region.height() / (float) decoded.bitmap.getHeight());
        decodedToOutput.postTranslate(decoded.region.left, decoded.region.top);
        decodedToOutput.postConcat(geometry.storedToOutput());
        decodedToOutput.postScale(width / (float) geometry.getOutputWidth(),
                height / (float) geometry.getOutputHeight());
        Bitmap output = createOutput(width, height, decoded.bitmap);
        Canvas canvas = new Canvas(output);
        if (!keepsTransparency(format)) {
            canvas.drawColor(Color.WHITE);
        }
        float[] colors = mEdits.getColorMatrix();
        ColorMatrixColorFilter filter = colors == null ? null
                : new ColorMatrixColorFilter(Filters.toAndroid(colors));
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        paint.setColorFilter(filter);
        canvas.drawBitmap(decoded.bitmap, decodedToOutput, paint);

        Bitmap overlay = mEdits.getOverlay();
        Matrix overlayToOutput = null;
        if (overlay != null) {
            overlayToOutput = new Matrix();
            overlayToOutput.setScale(geometry.getUprightWidth() / (float) overlay.getWidth(),
                    geometry.getUprightHeight() / (float) overlay.getHeight());
            overlayToOutput.postConcat(geometry.uprightToOutput());
            overlayToOutput.postScale(width / (float) geometry.getOutputWidth(),
                    height / (float) geometry.getOutputHeight());
            Paint overlayPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            overlayPaint.setColorFilter(mEdits.isOverlayFiltered() ? filter : null);
            canvas.drawBitmap(overlay, overlayToOutput, overlayPaint);
        }
        if (wantsGainmap && Build.VERSION.SDK_INT >= 34 && decoded.bitmap.hasGainmap()) {
            GainmapEdits.carry(decoded.bitmap, output, decodedToOutput, overlay,
                    overlayToOutput);
        }
        decoded.bitmap.recycle();
        checkCancelled();

        try {
            encode(output, format, destination);
        } finally {
            output.recycle();
        }
        checkCancelled();
        File clip = tags == null ? null : keptClip(tags, format);
        try {
            if (clip != null) {
                append(clip, destination);
            }
            writeMetadata(tags, format, destination, geometry, width, height, clip);
        } finally {
            delete(clip);
        }
    }

    /** The largest power of two a picture can be decoded smaller by and still fill the result. */
    @VisibleForTesting
    static int sampleSize(float scale) {
        int sample = 1;
        while (sample * 2 * scale <= 1f) {
            sample *= 2;
        }
        return sample;
    }

    /** How many pixels a region decodes to at a sample size. */
    private static long pixels(int width, int height, int sample) {
        return (long) ((width + sample - 1) / sample) * ((height + sample - 1) / sample);
    }

    /** A decoded part of the picture. */
    private static final class Decoded {
        /** The pixels. */
        final Bitmap bitmap;
        /** The stored region they show. */
        final Rect region;

        Decoded(@NonNull Bitmap bitmap, @NonNull Rect region) {
            this.bitmap = bitmap;
            this.region = region;
        }
    }

    /**
     * Decodes the part of the picture the edit needs: just that region where the format can be
     * decoded in pieces, otherwise all of it.
     */
    @WorkerThread
    @NonNull
    private Decoded decode(@NonNull Rect region, int sample, @NonNull BitmapFactory.Options bounds,
            boolean wantsGainmap, long budget, long outputBytes) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        boolean whole = region.left == 0 && region.top == 0 && region.width() == bounds.outWidth
                && region.height() == bounds.outHeight;
        // Android 9's HEIF decoder can crash on a small region, so a HEIF is decoded whole there.
        boolean heif = "image/heif".equals(bounds.outMimeType)
                || "image/heic".equals(bounds.outMimeType);
        if (!whole && !(heif && Build.VERSION.SDK_INT < 29)) {
            Bitmap piece = decodeRegion(region, options);
            if (piece != null && (!wantsGainmap || hasGainmap(piece))) {
                return new Decoded(piece, region);
            }
            if (piece != null) {
                Log.d(TAG, "The region came without its gain map, so all of it is decoded");
                piece.recycle();
            }
        }
        long needed = 4L * pixels(bounds.outWidth, bounds.outHeight, sample) + outputBytes;
        if (needed > budget) {
            throw new IOException("Decoding all of " + mSource + " needs " + needed
                    + " bytes, but only " + budget + " are free; set a maximum number of pixels");
        }
        Bitmap bitmap;
        try (InputStream input = openSource()) {
            bitmap = BitmapFactory.decodeStream(input, null, options);
        }
        if (bitmap == null) {
            throw new IOException(mSource + " could not be decoded");
        }
        return new Decoded(bitmap, new Rect(0, 0, bounds.outWidth, bounds.outHeight));
    }

    /** Decodes a region, or returns null where the format cannot be decoded in pieces. */
    @WorkerThread
    @Nullable
    @SuppressWarnings("deprecation")
    private Bitmap decodeRegion(@NonNull Rect region, @NonNull BitmapFactory.Options options) {
        // From a stream, which the decoder holds encoded, rather than a descriptor: a content
        // Uri need not have one that can seek.
        try (InputStream input = openSource()) {
            BitmapRegionDecoder decoder = Build.VERSION.SDK_INT >= 31
                    ? BitmapRegionDecoder.newInstance(input)
                    : BitmapRegionDecoder.newInstance(input, false);
            if (decoder == null) {
                return null;
            }
            try {
                return decoder.decodeRegion(region, options);
            } finally {
                decoder.recycle();
            }
        } catch (IOException | RuntimeException e) {
            Log.d(TAG, mSource + " cannot be decoded in pieces", e);
            return null;
        }
    }

    private static boolean hasGainmap(@NonNull Bitmap bitmap) {
        return Build.VERSION.SDK_INT >= 34 && bitmap.hasGainmap();
    }

    /** The edited picture's pixels, in the colour space the picture was decoded in. */
    @NonNull
    private static Bitmap createOutput(int width, int height, @NonNull Bitmap decoded) {
        if (Build.VERSION.SDK_INT >= 26 && decoded.getColorSpace() != null) {
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, true,
                    decoded.getColorSpace());
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    }

    /** Whether a format keeps transparency. The rest are drawn over white. */
    @VisibleForTesting
    static boolean keepsTransparency(int format) {
        return format == FORMAT_PNG || format == FORMAT_WEBP;
    }

    /** Writes pixels in a format. */
    @WorkerThread
    @SuppressWarnings("deprecation")
    private void encode(@NonNull Bitmap bitmap, int format, @NonNull File destination)
            throws IOException {
        if (format == FORMAT_HEIF || format == FORMAT_AVIF) {
            if (Build.VERSION.SDK_INT < 28) {
                throw new IOException("HEIF and AVIF are written from Android 9");
            }
            HeifEncoder.encode(bitmap, mQuality, format == FORMAT_AVIF, destination);
            return;
        }
        Encoder added;
        synchronized (ADDED_ENCODERS) {
            added = ADDED_ENCODERS.get(format);
        }
        try (OutputStream output = new FileOutputStream(destination)) {
            if (added != null) {
                added.encode(bitmap, mQuality, output);
                return;
            }
            Bitmap.CompressFormat compress;
            int quality = mQuality;
            switch (format) {
                case FORMAT_JPEG:
                    compress = Bitmap.CompressFormat.JPEG;
                    break;
                case FORMAT_PNG:
                    compress = Bitmap.CompressFormat.PNG;
                    break;
                case FORMAT_WEBP:
                    compress = Build.VERSION.SDK_INT >= 30
                            ? (quality == 100 ? Bitmap.CompressFormat.WEBP_LOSSLESS
                                    : Bitmap.CompressFormat.WEBP_LOSSY)
                            : Bitmap.CompressFormat.WEBP;
                    break;
                default:
                    throw new IOException("No encoder for format " + format);
            }
            if (!bitmap.compress(compress, quality, output)) {
                throw new IOException("Unable to encode format " + format);
            }
        }
    }

    // ---------- Metadata ----------

    /** Copies the picture's metadata into the edited one, as it now is. */
    @WorkerThread
    private void writeMetadata(@Nullable MediaTags source, int format, @NonNull File destination,
            @NonNull PictureGeometry geometry, int width, int height, @Nullable File clip)
            throws IOException {
        MediaTags tags = MediaTags.open(destination);
        if (source != null && !source.copyTo(tags)) {
            Log.d(TAG, "The edited picture could not keep all of the original's metadata");
        }
        tags.setOrientation(0, false);
        if (format == FORMAT_JPEG) {
            tags.setExifTag(TAG_PIXEL_X_DIMENSION, Exif.TYPE_LONG, Integer.toString(width));
            tags.setExifTag(TAG_PIXEL_Y_DIMENSION, Exif.TYPE_LONG, Integer.toString(height));
            if (source != null && EditorMetadata.hasThumbnail(source)) {
                EditorMetadata.attachThumbnail(tags);
            }
        }
        MediaTags.Panorama panorama = source == null ? null : source.getPanorama();
        if (panorama != null) {
            tags.setPanorama(Panoramas.edited(panorama, geometry.getUprightWidth(),
                    geometry.getUprightHeight(), mEdits, width, height));
        }
        if (source != null) {
            describeClip(tags, source, clip);
        }
        tags.save();
    }

    // ---------- Motion photos ----------

    /**
     * Whether a motion photo's clip goes with the edited picture, and if so how far it turns.
     *
     * @return the clockwise turn to give the clip, or -1 to leave it out
     */
    @VisibleForTesting
    static int clipTurn(@MotionPhotoPolicy int policy, @NonNull PictureEdits edits, int format) {
        if (policy == MOTION_PHOTO_DROP || (format != FORMAT_JPEG && format != FORMAT_HEIF)) {
            return -1;
        }
        int[] net = PictureGeometry.combine(0, false, edits.getRotationDegrees(),
                edits.isFlippedHorizontally(), edits.isFlippedVertically());
        if (policy == MOTION_PHOTO_AUTO && (!edits.keepsFraming() || net[1] == 1)) {
            return -1;
        }
        return net[0];
    }

    /**
     * Cuts a copied motion photo's clip off the end of the copy, and makes the clip that goes
     * back on, if one does.
     *
     * @return the clip to append, which the caller deletes, or null for none
     */
    @WorkerThread
    @Nullable
    private File cutClip(@NonNull MediaTags source, @NonNull File copy) throws IOException {
        MediaTags.MotionPhoto motion = source.getMotionPhoto();
        if (motion == null) {
            return null;
        }
        File clip = keptClip(source, resolveFormat(mFormat, source.getFormat()));
        long cut = clipStart(motion, copy);
        try (RandomAccessFile file = new RandomAccessFile(copy, "rw")) {
            file.setLength(cut);
        }
        if (clip != null) {
            append(clip, copy);
        }
        return clip;
    }

    /**
     * Where a motion photo's clip begins in the file, with the record Samsung puts in front of
     * it, so cutting there leaves nothing of it behind.
     */
    @WorkerThread
    private static long clipStart(@NonNull MediaTags.MotionPhoto motion, @NonNull File file)
            throws IOException {
        long start = motion.getVideoOffset();
        if (motion.getFormat() != MediaTags.MotionPhoto.FORMAT_SAMSUNG) {
            return start;
        }
        long record = start - SAMSUNG_CLIP_NAME.length() - SAMSUNG_RECORD_HEADER;
        if (record < 0) {
            return start;
        }
        byte[] name = new byte[SAMSUNG_CLIP_NAME.length()];
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            input.seek(start - name.length);
            input.readFully(name);
        }
        return SAMSUNG_CLIP_NAME.equals(new String(name, "US-ASCII")) ? record : start;
    }

    /**
     * The motion photo's clip to put on the edited picture, turned to match it, or null when
     * the picture is not a motion photo or the clip is left out.
     */
    @WorkerThread
    @Nullable
    private File keptClip(@NonNull MediaTags source, int format) throws IOException {
        MediaTags.MotionPhoto motion = source.getMotionPhoto();
        int turn = clipTurn(mMotionPhotoPolicy, mEdits, format);
        if (motion == null || turn < 0) {
            return null;
        }
        File clip = File.createTempFile("clip", ".mp4", mContext.getCacheDir());
        try {
            try (InputStream input = openSource();
                    OutputStream output = new FileOutputStream(clip)) {
                skipFully(input, motion.getVideoOffset());
                copy(input, output, motion.getVideoLength());
            }
            if (turn != 0) {
                MediaTags tags = MediaTags.open(clip);
                if (tags.setOrientation(tags.getRotation() + turn, false)) {
                    tags.save();
                } else if (mMotionPhotoPolicy == MOTION_PHOTO_AUTO) {
                    Log.d(TAG, "The clip cannot be turned with the picture, so it is left out");
                    delete(clip);
                    return null;
                }
            }
            return clip;
        } catch (IOException | RuntimeException e) {
            delete(clip);
            if (mMotionPhotoPolicy == MOTION_PHOTO_KEEP) {
                throw new IOException("Unable to keep the motion photo's clip", e);
            }
            Log.w(TAG, "Unable to keep the motion photo's clip", e);
            return null;
        }
    }

    /** Says what the edited picture carries: the clip appended to it, or none. */
    @WorkerThread
    private static void describeClip(@NonNull MediaTags tags, @NonNull MediaTags source,
            @Nullable File clip) {
        if (clip != null) {
            if (!EditorMetadata.describeMotionPhoto(tags, clip.length(),
                    source.getMotionPhoto() == null ? -1
                            : source.getMotionPhoto().getPresentationTimestampUs())) {
                Log.w(TAG, "The edited picture cannot say it carries a motion photo's clip");
            }
        } else if (!EditorMetadata.removeMotionPhoto(tags)) {
            Log.w(TAG, "The edited picture still says it carries a motion photo's clip");
        }
    }

    // ---------- Files ----------

    @WorkerThread
    @NonNull
    private InputStream openSource() throws IOException {
        if (ContentResolver.SCHEME_FILE.equals(mSource.getScheme()) && mSource.getPath() != null) {
            return new FileInputStream(mSource.getPath());
        }
        InputStream input = mContext.getContentResolver().openInputStream(mSource);
        if (input == null) {
            throw new FileNotFoundException("Nothing to read at " + mSource);
        }
        return input;
    }

    /** Copies the picture's bytes into a file. */
    @WorkerThread
    private void copySource(@NonNull File destination) throws IOException {
        try (InputStream input = openSource();
                OutputStream output = new FileOutputStream(destination)) {
            copy(input, output, Long.MAX_VALUE);
        }
    }

    /** Appends one file to another. */
    @WorkerThread
    private static void append(@NonNull File from, @NonNull File to) throws IOException {
        try (InputStream input = new FileInputStream(from);
                OutputStream output = new FileOutputStream(to, true)) {
            copy(input, output, Long.MAX_VALUE);
        }
    }

    private static void copy(@NonNull InputStream input, @NonNull OutputStream output,
            long count) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long left = count;
        while (left > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (read < 0) {
                if (count != Long.MAX_VALUE) {
                    throw new IOException("The file ended " + left + " bytes early");
                }
                return;
            }
            output.write(buffer, 0, read);
            left -= read;
        }
    }

    private static void skipFully(@NonNull InputStream input, long count) throws IOException {
        long left = count;
        while (left > 0) {
            long skipped = input.skip(left);
            if (skipped <= 0) {
                if (input.read() < 0) {
                    throw new IOException("The file ended " + left + " bytes early");
                }
                skipped = 1;
            }
            left -= skipped;
        }
    }

    private static void delete(@Nullable File file) {
        if (file != null && file.exists() && !file.delete()) {
            Log.w(TAG, "Unable to delete " + file);
        }
    }

    /**
     * How many bytes the pictures of one edit may take: half the memory the system has free,
     * from Android 8 where pictures live outside the app's heap, and otherwise most of what is
     * left of the heap.
     */
    @VisibleForTesting
    @WorkerThread
    static long memoryBudget(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            ActivityManager manager =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null) {
                ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
                manager.getMemoryInfo(info);
                if (info.availMem > 0) {
                    return (long) (info.availMem * MEMORY_SHARE);
                }
            }
        }
        Runtime runtime = Runtime.getRuntime();
        long free = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
        return (long) (free * 0.8f);
    }

    /**
     * Gives the metadata writers somewhere to write. They save through copies made in
     * java.io.tmpdir, which newer releases point at the app's cache and Android 4.2 and older
     * leave at /sdcard, which an app without storage permission cannot write.
     */
    @WorkerThread
    private static void ensureWritableTempDir(@NonNull Context context) {
        File directory = new File(System.getProperty("java.io.tmpdir", ""));
        if (!directory.canWrite()) {
            System.setProperty("java.io.tmpdir", context.getCacheDir().getAbsolutePath());
        }
    }

    private void checkCancelled() throws CancelledException {
        if (mCancelled) {
            throw new CancelledException();
        }
    }

    private void report(boolean edited) {
        mMainHandler.post(() -> {
            if (mCancelled) {
                return;
            }
            mRunning = false;
            OnPictureEditedListener listener = mListener;
            if (listener == null) {
                return;
            }
            if (edited) {
                listener.onPictureEdited(mOutput);
            } else {
                listener.onFailure();
            }
        });
    }

    /** Thrown inside the worker to stop it once the edit is cancelled. */
    private static final class CancelledException extends IOException {
        CancelledException() {
            super("Cancelled");
        }
    }

    /**
     * Reports the edited picture. Both methods are called on the main thread.
     */
    public interface OnPictureEditedListener {
        /** The edited picture has been written. */
        void onPictureEdited(@NonNull File file);

        /** The picture could not be edited, and nothing was written. */
        void onFailure();
    }

    /** Writes pixels in a format another module of this library adds. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public interface Encoder {
        /**
         * @param quality 0 to 100, as {@link Bitmap#compress} takes it
         */
        @WorkerThread
        void encode(@NonNull Bitmap bitmap, int quality, @NonNull OutputStream output)
                throws IOException;
    }
}
