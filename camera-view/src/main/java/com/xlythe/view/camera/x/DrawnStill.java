package com.xlythe.view.camera.x;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapRegionDecoder;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.location.Location;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;
import androidx.camera.core.ImageProxy;

import com.xlythe.view.camera.DrawnPictures;
import com.xlythe.view.camera.ExifCaptureParameters;
import com.xlythe.view.camera.ICameraModule;
import com.xlythe.view.camera.gl.GlMath;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A picture taken at the camera's still resolution and then drawn through the frame renderer.
 *
 * <p>CameraX draws an effect's still from the stream it shares with the preview, at the
 * preview's resolution. So the still is taken from the camera on its own, as an in-memory JPEG,
 * and put through the renderer here, as Camera2 puts the frame its camera writes into the
 * renderer: the camera's frame in, neither turned nor mirrored, drawn the right way up, read
 * back and written as a JPEG.</p>
 *
 * <p>The bytes are copied out when this is made and the image goes straight back, for the
 * reason {@link CopiedFrame} gives. The rest runs on a worker thread, holding at most the decoded
 * frame and then the drawing at once: the decoded frame is let go as soon as it is in the
 * renderer.</p>
 */
final class DrawnStill implements Runnable {
    /** Log tag shared with the camera modules. */
    private static final String TAG = ICameraModule.TAG;

    /** How long the renderer may take to draw the still. */
    private static final long DRAW_TIMEOUT_MILLIS = 10_000;

    /** How long the drawing may take to reach its reader once drawn. */
    private static final long READBACK_TIMEOUT_MILLIS = 1_000;

    /** Hears whether the picture was written. */
    interface Callback {
        /** Called on the worker thread, once. */
        @WorkerThread
        void onWritten(boolean written);
    }

    /** The JPEG the camera encoded. */
    @NonNull
    private final byte[] mBytes;

    /** The part of the frame the preview showed, which is what gets drawn. */
    @NonNull
    private final Rect mCrop;

    /** How far the frame is turned clockwise to be shown. */
    @GlMath.Rotation
    private final int mRotation;

    /** Whether the picture is shown mirrored, as a front lens's preview is. */
    private final boolean mMirrored;

    /** When the camera took the picture, on the clock its preview frames carry. */
    private final long mTimestampNanos;

    /** What draws the still. */
    @NonNull
    private final FrameEffect mEffect;

    /** Where the JPEG goes. */
    @NonNull
    private final File mFile;

    /** The JPEG quality, from 1 to 100. */
    private final int mQuality;

    /** How the picture was taken, for the tags its Exif carries. */
    @NonNull
    private final ExifCaptureParameters mExifParameters;

    /** Where the picture was taken, or null to leave it untagged. */
    @Nullable
    private final Location mLocation;

    /** The application, whose name and version the picture records as its software. */
    @NonNull
    private final Context mContext;

    /** Hears how it went. */
    @NonNull
    private final Callback mCallback;

    /** Copies the JPEG out of the image and closes it. */
    @MainThread
    DrawnStill(@NonNull Context context, @NonNull ImageProxy image, boolean mirrored,
            @NonNull FrameEffect effect, @NonNull File file, int quality,
            @NonNull ExifCaptureParameters exifParameters, @Nullable Location location,
            @NonNull Callback callback) {
        try {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            mBytes = new byte[buffer.remaining()];
            buffer.get(mBytes);
            mCrop = new Rect(image.getCropRect());
            mRotation = toRotation(image.getImageInfo().getRotationDegrees());
            mTimestampNanos = image.getImageInfo().getTimestamp();
        } finally {
            image.close();
        }
        mMirrored = mirrored;
        mEffect = effect;
        mFile = file;
        mQuality = quality;
        mExifParameters = exifParameters;
        mLocation = location;
        mContext = context.getApplicationContext();
        mCallback = callback;
    }

    @WorkerThread
    @Override
    public void run() {
        long started = SystemClock.elapsedRealtime();
        Bitmap drawn;
        try {
            drawn = draw();
        } catch (IOException | RuntimeException | OutOfMemoryError | InterruptedException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Log.e(TAG, "Failed to draw a still through the renderer", error);
            mCallback.onWritten(false);
            return;
        }
        boolean written = DrawnPictures.write(
                mContext, drawn, mFile, mQuality, mExifParameters, mLocation);
        if (ICameraModule.DEBUG) {
            Log.d(TAG, "Drew a still through the renderer in "
                    + (SystemClock.elapsedRealtime() - started) + " ms");
        }
        mCallback.onWritten(written);
    }

    /** Decodes the frame, puts it through the renderer and reads the drawing back. */
    @WorkerThread
    @NonNull
    private Bitmap draw() throws IOException, InterruptedException {
        Bitmap frame = decode();
        Size shown = FrameEffect.shownSize(new Size(frame.getWidth(), frame.getHeight()),
                mRotation);
        ImageReader reader = ImageReader.newInstance(
                shown.getWidth(), shown.getHeight(), PixelFormat.RGBA_8888, 1);
        try {
            // Heard on the main thread, because this one has no looper to hear it on.
            CountDownLatch readable = new CountDownLatch(1);
            reader.setOnImageAvailableListener(available -> readable.countDown(),
                    new Handler(Looper.getMainLooper()));
            CountDownLatch drawnLatch = new CountDownLatch(1);
            boolean started;
            try {
                started = mEffect.drawStill(frame, mRotation, mMirrored, reader.getSurface(),
                        drawnLatch::countDown, mTimestampNanos);
            } finally {
                // In the renderer's input now, or never going there.
                frame.recycle();
            }
            if (!started) {
                throw new IOException("The renderer has stopped");
            }
            if (!drawnLatch.await(DRAW_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new IOException("The renderer never drew the still");
            }
            if (!readable.await(READBACK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new IOException("The drawing never reached its reader");
            }
            Image image = reader.acquireLatestImage();
            if (image == null) {
                throw new IOException("The drawing never reached its reader");
            }
            try {
                return DrawnPictures.toBitmap(image);
            } finally {
                image.close();
            }
        } finally {
            reader.close();
        }
    }

    /** The camera's frame, cropped to what the preview showed, as the camera wrote it. */
    @WorkerThread
    @NonNull
    private Bitmap decode() throws IOException {
        BitmapRegionDecoder decoder = Build.VERSION.SDK_INT >= 31
                ? BitmapRegionDecoder.newInstance(mBytes, 0, mBytes.length)
                : BitmapRegionDecoder.newInstance(mBytes, 0, mBytes.length, false);
        if (decoder == null) {
            throw new IOException("The camera's JPEG does not decode");
        }
        Bitmap bitmap;
        try {
            Rect crop = new Rect(mCrop);
            if (!crop.intersect(0, 0, decoder.getWidth(), decoder.getHeight())) {
                crop.set(0, 0, decoder.getWidth(), decoder.getHeight());
            }
            bitmap = decoder.decodeRegion(crop, null);
        } finally {
            decoder.recycle();
        }
        if (bitmap == null) {
            throw new IOException("The camera's JPEG does not decode");
        }
        return bitmap;
    }

    /** A rotation CameraX reported, as one of the four the renderer draws with. */
    @VisibleForTesting
    @GlMath.Rotation
    static int toRotation(int degrees) {
        switch ((degrees % 360 + 360) % 360) {
            case 90: return 90;
            case 180: return 180;
            case 270: return 270;
            case 0:
            default: return 0;
        }
    }
}
