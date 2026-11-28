package com.xlythe.view.camera.viewer;

import static com.xlythe.view.camera.ICameraModule.TAG;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;
import androidx.exifinterface.media.ExifInterface;

import com.xlythe.view.camera.MediaTags;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Opens a picture for the viewers: how big it is, which way up, where it sits on the sphere, and
 * a source to decode it from a piece at a time.
 *
 * <p>Most pictures are decoded in pieces straight from the file. One the platform cannot decode
 * in pieces, a DNG above all, is decoded whole once and written to the app's cache as a JPEG
 * that can be; after that it is viewed the same way, and opening it again finds the copy. The
 * whole decode is the most memory a viewer ever uses, and it is held only while the copy is
 * written.</p>
 */
final class ImageSources {
    /** Where the copies go, under the app's cache. */
    @VisibleForTesting
    static final String CACHE_DIRECTORY = "cameraview-viewer";

    /** How many copies to keep. Each is a few megabytes; the oldest go first. */
    @VisibleForTesting
    static final int MAX_COPIES = 4;

    /**
     * The most pixels a picture is decoded whole at to make a copy: 50 MP, 200 MB. A larger
     * picture is decoded at half the size, or a quarter, until it fits. 50 MP is the most a phone
     * writes as RAW, so a DNG from one is copied at full detail.
     */
    @VisibleForTesting
    static final long MAX_COPY_PIXELS = 50_000_000L;

    /** How hard to compress a copy. High: it stands in for the picture at full detail. */
    private static final int COPY_QUALITY = 95;

    /** The largest sample size worth trying before deciding a picture cannot be decoded. */
    private static final int MAX_SAMPLE_SIZE = 64;

    /** Opens pictures; the viewers take another in tests, to stand in for huge ones. */
    interface Opener {
        /** As {@link ImageSources#open}. */
        @WorkerThread
        @NonNull
        Opened open(@NonNull Context context, @NonNull ImageLocation location) throws IOException;
    }

    /** What an opened picture is. */
    static final class Opened {
        /** Decodes the picture in pieces, in its stored pixels. */
        @NonNull
        final RegionSource source;

        /** Which way up it is, as an Exif orientation. */
        final int orientation;

        /** Where it sits on the sphere, or null if it does not say. */
        @Nullable
        final GPano gpano;

        Opened(@NonNull RegionSource source, int orientation, @Nullable GPano gpano) {
            this.source = source;
            this.orientation = orientation;
            this.gpano = gpano;
        }
    }

    private ImageSources() {}

    /**
     * Opens a picture.
     *
     * @throws IOException if it is not a picture this release can decode, or cannot be read
     */
    @WorkerThread
    @NonNull
    static Opened open(@NonNull Context context, @NonNull ImageLocation location)
            throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = location.open(context)) {
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException(location + " is not a picture this device can decode");
        }
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        GPano gpano = null;
        try {
            MediaTags tags = location.openTags(context);
            orientation = Orientation.exifOrientation(tags.getRotation(), tags.isMirrored());
            gpano = GPano.from(tags.getPanorama(), bounds.outWidth, bounds.outHeight);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Unable to read the metadata of " + location, e);
        }
        RegionSource source;
        try {
            source = DecoderRegionSource.open(location.openDescriptor(context),
                    HdrStills.config(bounds), declaredHdrColorSpace(context, location, bounds));
        } catch (IOException e) {
            Log.d(TAG, location + " cannot be decoded in pieces, so it is copied", e);
            source = copy(context, location, bounds.outWidth, bounds.outHeight, MAX_COPY_PIXELS);
        }
        return new Opened(source, orientation, gpano);
    }

    /**
     * The HDR colour space a picture names that the platform does not decode it into, or null.
     */
    @WorkerThread
    @Nullable
    private static ColorSpace declaredHdrColorSpace(@NonNull Context context,
            @NonNull ImageLocation location, @NonNull BitmapFactory.Options bounds) {
        if (Build.VERSION.SDK_INT < 34 || HdrStills.isHdr(bounds)) {
            return null;
        }
        try (InputStream input = location.open(context)) {
            return HdrStills.declaredHdrColorSpace(input);
        } catch (IOException | RuntimeException e) {
            Log.d(TAG, "Unable to read the colour of " + location, e);
            return null;
        }
    }

    /**
     * A copy of the picture that can be decoded in pieces, made by decoding it whole once, or
     * found from an earlier time.
     */
    @VisibleForTesting
    @WorkerThread
    @NonNull
    static RegionSource copy(@NonNull Context context, @NonNull ImageLocation location,
            int width, int height, long maxPixels) throws IOException {
        File directory = new File(context.getCacheDir(), CACHE_DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Unable to make " + directory);
        }
        File copy = new File(directory, digest(location.fingerprint(context)) + ".jpg");
        if (copy.isFile()) {
            try {
                return scaled(context, copy, width, height);
            } catch (IOException e) {
                Log.w(TAG, "The copy of " + location + " is unreadable; making it again", e);
            }
        }
        Bitmap whole = decodeWhole(context, location, width, height, maxPixels);
        File partial = new File(directory, copy.getName() + ".partial");
        try {
            try (FileOutputStream output = new FileOutputStream(partial)) {
                if (!whole.compress(Bitmap.CompressFormat.JPEG, COPY_QUALITY, output)) {
                    throw new IOException("Unable to write a copy of " + location);
                }
            }
        } finally {
            whole.recycle();
        }
        if (!partial.renameTo(copy)) {
            throw new IOException("Unable to keep the copy of " + location);
        }
        trim(directory);
        return scaled(context, copy, width, height);
    }

    @WorkerThread
    @NonNull
    private static RegionSource scaled(@NonNull Context context, @NonNull File copy, int width,
            int height) throws IOException {
        return new ScaledRegionSource(DecoderRegionSource.open(
                ImageLocation.of(copy).openDescriptor(context)), width, height);
    }

    /**
     * The picture decoded whole, at full size if it is no more than a number of pixels, and
     * otherwise at the largest power-of-two fraction that is. A decode that runs out of memory is
     * tried again at half the size.
     */
    @WorkerThread
    @NonNull
    private static Bitmap decodeWhole(@NonNull Context context, @NonNull ImageLocation location,
            int width, int height, long maxPixels) throws IOException {
        int sampleSize = Tiles.sampleSizeWithin(width, height, maxPixels);
        for (; sampleSize <= MAX_SAMPLE_SIZE; sampleSize *= 2) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream input = location.open(context)) {
                Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
                if (bitmap != null) {
                    return bitmap;
                }
            } catch (OutOfMemoryError e) {
                Log.w(TAG, "Out of memory decoding " + location + " at 1/" + sampleSize, e);
            }
        }
        throw new IOException("Unable to decode " + location);
    }

    /** Deletes all but the newest copies. */
    @WorkerThread
    private static void trim(@NonNull File directory) {
        File[] copies = directory.listFiles();
        if (copies == null || copies.length <= MAX_COPIES) {
            return;
        }
        Arrays.sort(copies, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                long difference = b.lastModified() - a.lastModified();
                return difference > 0 ? 1 : difference < 0 ? -1 : 0;
            }
        });
        for (int i = MAX_COPIES; i < copies.length; i++) {
            if (!copies[i].delete()) {
                Log.w(TAG, "Unable to delete " + copies[i]);
            }
        }
    }

    /** A file name for a fingerprint. */
    @NonNull
    private static String digest(@NonNull String fingerprint) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(fingerprint.getBytes("UTF-8"));
            StringBuilder name = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                name.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return name.toString();
        } catch (NoSuchAlgorithmException | IOException impossible) {
            // Every Android release has SHA-1 and UTF-8; fall back to something unique anyway.
            return Integer.toHexString(fingerprint.hashCode());
        }
    }
}
