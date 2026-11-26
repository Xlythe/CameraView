package com.xlythe.view.camera.x;

import android.graphics.Bitmap;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Build;
import android.util.Log;

import androidx.annotation.IntRange;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.camera.core.ImageProxy;

import com.xlythe.view.camera.Exif;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import static com.xlythe.view.camera.ICameraModule.TAG;

/**
 * Writes the JPEG half of a RAW_JPEG capture from the image CameraX hands over in memory.
 *
 * <p>CameraX only writes a capture to disk itself when it writes all of it, DNG included, so for
 * RAW_JPEG both images are taken in memory and this writes the JPEG the way CameraX would have:
 * cropped to the viewport the preview showed, turned the right way up, and mirrored on a front
 * lens. The bytes are copied out when the saver is made, for the reason {@link CopiedFrame}
 * gives, and the image goes straight back.</p>
 */
class JpegSaver extends AsyncTask<Void, Void, Boolean> {
    /** The JPEG the camera encoded. */
    @NonNull
    private final byte[] mBytes;

    /** The part of the frame the preview showed, which is what gets written. */
    @NonNull
    private final Rect mCrop;

    /** Whether {@link #mCrop} leaves anything out, which means encoding the JPEG again. */
    private final boolean mCropped;

    /** How far the frame is turned from upright. */
    private final int mRotation;

    /** The quality a cropped JPEG is encoded again at, which is the one the caller asked for. */
    private final int mQuality;

    /** Whether the picture is mirrored, as a front lens's preview is. */
    private final boolean mMirrored;

    /** Where the JPEG goes. */
    @NonNull
    private final File mFile;

    /** Copies the JPEG out of the image and closes it. */
    @MainThread
    JpegSaver(@NonNull ImageProxy image, @IntRange(from = 1, to = 100) int quality,
            boolean mirrored, @NonNull File file) {
        try {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            mBytes = new byte[buffer.remaining()];
            buffer.get(mBytes);
            mCrop = new Rect(image.getCropRect());
            mCropped = !mCrop.equals(new Rect(0, 0, image.getWidth(), image.getHeight()));
            mRotation = image.getImageInfo().getRotationDegrees();
        } finally {
            image.close();
        }
        mQuality = quality;
        mMirrored = mirrored;
        mFile = file;
    }

    /** @return true only when the JPEG reached disk, so a failure is never reported as one. */
    @WorkerThread
    @Override
    protected Boolean doInBackground(Void... params) {
        try {
            try (OutputStream output = new FileOutputStream(mFile)) {
                if (mCropped) {
                    writeCropped(output);
                } else {
                    output.write(mBytes);
                }
            }
            Exif exif = new Exif(mFile);
            // Relative to whatever the camera wrote, which is either nothing or this same turn.
            exif.rotate(mRotation - exif.getRotation());
            if (mMirrored) {
                exif.flipHorizontally();
            }
            Location location = getLocation();
            if (location != null) {
                exif.attachLocation(location);
            }
            exif.attachTimestamp();
            exif.save();
            return true;
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Failed to write the JPEG", e);
            if (mFile.exists() && !mFile.delete()) {
                Log.w(TAG, "Failed to clean up a partially written JPEG");
            }
            return false;
        }
    }

    /**
     * Where the picture was taken, or null when that is not known. Asked on the writer's thread,
     * so it may wait briefly for a fix.
     */
    @WorkerThread
    @Nullable
    protected Location getLocation() {
        return null;
    }

    /**
     * Decodes only the cropped region and encodes it again.
     *
     * <p>A JPEG cannot be cropped without decoding it, and decoding the region alone keeps the
     * whole sensor's worth of pixels out of memory.</p>
     */
    @WorkerThread
    private void writeCropped(@NonNull OutputStream output) throws IOException {
        BitmapRegionDecoder decoder = Build.VERSION.SDK_INT >= 31
                ? BitmapRegionDecoder.newInstance(mBytes, 0, mBytes.length)
                : BitmapRegionDecoder.newInstance(mBytes, 0, mBytes.length, false);
        if (decoder == null) {
            throw new IOException("The camera's JPEG does not decode");
        }
        Bitmap bitmap;
        try {
            bitmap = decoder.decodeRegion(mCrop, null);
        } finally {
            decoder.recycle();
        }
        if (bitmap == null) {
            throw new IOException("The camera's JPEG does not decode");
        }
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, mQuality, output)) {
                throw new IOException("Failed to encode the cropped JPEG");
            }
        } finally {
            bitmap.recycle();
        }
    }
}
