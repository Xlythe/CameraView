package com.xlythe.view.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.location.Location;
import android.media.Image;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.annotation.WorkerThread;
import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * A picture drawn through a frame renderer, read back from it and written as a JPEG, on either
 * backend that draws frames.
 *
 * @hide
 */
@RequiresApi(21)
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class DrawnPictures {
    /** Log tag shared with the camera modules. */
    private static final String TAG = ICameraModule.TAG;

    private DrawnPictures() {}

    /** The drawn frame as a bitmap, which is RGBA and so needs no conversion beyond the stride. */
    @NonNull
    public static Bitmap toBitmap(@NonNull Image drawn) {
        Image.Plane plane = drawn.getPlanes()[0];
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        // The reader pads each row out to its stride, so the bitmap is made wide enough to hold
        // the padding and then cropped - reading it as if it were packed shears the picture.
        int padded = rowStride / pixelStride;
        Bitmap full = Bitmap.createBitmap(padded, drawn.getHeight(), Bitmap.Config.ARGB_8888);
        full.copyPixelsFromBuffer(plane.getBuffer());
        if (padded == drawn.getWidth()) {
            return full;
        }
        Bitmap cropped = Bitmap.createBitmap(full, 0, 0, drawn.getWidth(), drawn.getHeight());
        full.recycle();
        return cropped;
    }

    /**
     * Writes a drawn picture, and tags it as a camera frame is tagged, recycling the bitmap.
     *
     * <p>What it is given is already pixels, already the right way up and mirrored as shown,
     * rather than a camera frame. So the orientation it records is normal, where a camera frame's
     * records the turn and the mirror a viewer still has to apply.</p>
     *
     * @param location where the picture was taken, or null to leave it untagged
     * @return true only when the picture reached disk, so a failure is never reported as one
     */
    @WorkerThread
    public static boolean write(@NonNull Context context, @NonNull Bitmap bitmap,
            @NonNull File file, int quality, @NonNull ExifCaptureParameters exifParameters,
            @Nullable Location location) {
        try (FileOutputStream output = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                Log.e(TAG, "Failed to compress a drawn picture");
                return false;
            }
        } catch (IOException | RuntimeException error) {
            Log.e(TAG, "Failed to write a drawn picture", error);
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Failed to clean up a partially written picture");
            }
            return false;
        } finally {
            bitmap.recycle();
        }
        try {
            Exif exif = new Exif(file);
            exif.attachTimestamp();
            exif.attachCaptureDevice(context.getApplicationContext());
            exif.attachThumbnail();
            exifParameters.applyTo(exif);
            exif.setAttribute(ExifInterface.TAG_ORIENTATION,
                    Integer.toString(ExifInterface.ORIENTATION_NORMAL));
            if (location != null) {
                exif.attachLocation(location);
            }
            exif.save();
        } catch (IOException error) {
            Log.e(TAG, "Failed to update EXIF on a drawn picture", error);
        }
        return true;
    }
}
