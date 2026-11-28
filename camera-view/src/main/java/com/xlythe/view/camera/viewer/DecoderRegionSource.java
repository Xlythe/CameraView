package com.xlythe.view.camera.viewer;

import static com.xlythe.view.camera.ICameraModule.TAG;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.FileDescriptor;
import java.io.IOException;

/**
 * A picture decoded a piece at a time by the platform's region decoder, which reads JPEG, PNG and
 * WebP (and HEIF on newer releases) straight from the file without holding it in memory.
 */
final class DecoderRegionSource implements RegionSource {
    /** Guards the decoder and descriptor, which close while a worker may be decoding. */
    private final Object mLock = new Object();

    /** The decoder, or null once closed. */
    @GuardedBy("mLock")
    @Nullable
    private BitmapRegionDecoder mDecoder;

    /** The file the decoder reads, held open for as long as it does. */
    @GuardedBy("mLock")
    @Nullable
    private ParcelFileDescriptor mDescriptor;

    /** The stored width, kept so it can be asked for after closing. */
    private final int mWidth;

    /** The stored height. */
    private final int mHeight;

    private DecoderRegionSource(@NonNull BitmapRegionDecoder decoder,
            @NonNull ParcelFileDescriptor descriptor) {
        mDecoder = decoder;
        mDescriptor = descriptor;
        mWidth = decoder.getWidth();
        mHeight = decoder.getHeight();
    }

    /**
     * Opens a picture for decoding in pieces.
     *
     * @throws IOException if it cannot be: a format the region decoder does not read, such as DNG,
     *     or a provider that hands out a pipe the decoder cannot seek in
     */
    @WorkerThread
    @NonNull
    static DecoderRegionSource open(@NonNull ParcelFileDescriptor descriptor) throws IOException {
        try {
            BitmapRegionDecoder decoder = newDecoder(descriptor);
            if (decoder == null) {
                throw new IOException("The picture cannot be decoded in pieces");
            }
            return new DecoderRegionSource(decoder, descriptor);
        } catch (IOException | RuntimeException e) {
            closeQuietly(descriptor);
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    @Nullable
    private static BitmapRegionDecoder newDecoder(@NonNull ParcelFileDescriptor descriptor)
            throws IOException {
        if (Build.VERSION.SDK_INT >= 31) {
            return BitmapRegionDecoder.newInstance(descriptor);
        }
        return newDecoderBefore31(descriptor.getFileDescriptor());
    }

    /** The only form there is before API 31, where it is not yet deprecated. */
    @SuppressWarnings("deprecation")
    @Nullable
    private static BitmapRegionDecoder newDecoderBefore31(@NonNull FileDescriptor descriptor)
            throws IOException {
        return BitmapRegionDecoder.newInstance(descriptor, false);
    }

    @AnyThread
    @Override
    public int getWidth() {
        return mWidth;
    }

    @AnyThread
    @Override
    public int getHeight() {
        return mHeight;
    }

    @WorkerThread
    @Nullable
    @Override
    public Bitmap decode(@NonNull Rect region, int sampleSize) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        // Held for the decode, so a close in the middle waits rather than freeing what the
        // decoder is reading from. The decoder serialises its own calls anyway.
        synchronized (mLock) {
            if (mDecoder == null) {
                return null;
            }
            try {
                return mDecoder.decodeRegion(region, options);
            } catch (RuntimeException | OutOfMemoryError e) {
                Log.w(TAG, "Unable to decode " + region + " at 1/" + sampleSize, e);
                return null;
            }
        }
    }

    @AnyThread
    @Override
    public void close() {
        synchronized (mLock) {
            if (mDecoder != null) {
                mDecoder.recycle();
                mDecoder = null;
            }
            if (mDescriptor != null) {
                closeQuietly(mDescriptor);
                mDescriptor = null;
            }
        }
    }

    private static void closeQuietly(@NonNull ParcelFileDescriptor descriptor) {
        try {
            descriptor.close();
        } catch (IOException e) {
            Log.w(TAG, "Unable to close a picture", e);
        }
    }
}
