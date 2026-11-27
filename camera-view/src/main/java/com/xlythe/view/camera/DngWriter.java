package com.xlythe.view.camera;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Build;
import android.util.Log;
import android.util.Size;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.annotation.WorkerThread;
import androidx.exifinterface.media.ExifInterface;

import static com.xlythe.view.camera.ICameraModule.TAG;

/**
 * Writes a RAW frame and the capture result that describes it as a DNG.
 *
 * <p>Both backends that capture RAW pair the two their own way and hand them here, so the file is
 * written the same way whichever is running. The frame is closed once the write is over, however
 * it went.</p>
 */
@RequiresApi(21)
@RestrictTo(RestrictTo.Scope.LIBRARY)
public class DngWriter extends AsyncTask<Void, Void, Boolean> {
    /** The sensor's colour matrices, black levels and geometry, which DngCreator reads. */
    @NonNull
    private final CameraCharacteristics mCharacteristics;

    /** The result for this frame's exposure, which says how its pixels are to be read. */
    @NonNull
    private final CaptureResult mResult;

    /** The pixels. Closed once the write is over, so its reader can be released. */
    @NonNull
    private final RawCapturePairer.Frame mFrame;

    /** Which way up the picture is, as an Exif orientation; see {@link #exifOrientation}. */
    private final int mOrientation;

    /** Where the DNG goes. */
    @NonNull
    private final File mFile;

    public DngWriter(@NonNull CameraCharacteristics characteristics,
            @NonNull RawCapturePairer.Pairing<? extends CaptureResult> pairing,
            @IntRange(from = 1, to = 8) int orientation, @NonNull File file) {
        mCharacteristics = characteristics;
        mResult = pairing.metadata;
        mFrame = pairing.frame;
        mOrientation = orientation;
        mFile = file;
    }

    /** @return true only when the DNG reached disk, so a failure is never reported as one. */
    @WorkerThread
    @Override
    protected Boolean doInBackground(Void... params) {
        try {
            return write();
        } finally {
            mFrame.close();
        }
    }

    /**
     * Where the picture was taken, or null when that is not known.
     *
     * <p>Asked on the writer's thread, so it returns a fix already in hand rather than waiting for
     * one. A DNG cannot be amended after it is written the way a JPEG's Exif can, so this is the
     * only chance to record it.</p>
     */
    @WorkerThread
    @Nullable
    protected Location getLocation() {
        return null;
    }

    @WorkerThread
    private boolean write() {
        DngCreator creator;
        try {
            creator = new DngCreator(mCharacteristics, mResult);
        } catch (RuntimeException | AssertionError e) {
            Log.e(TAG, "Failed to describe the raw frame", e);
            return false;
        }
        try {
            creator.setOrientation(mOrientation);
            attachLocation(creator);
            try (FileOutputStream output = new FileOutputStream(mFile)) {
                mFrame.writeTo(creator, output);
            }
            return true;
        } catch (IOException | RuntimeException | AssertionError e) {
            // AssertionError as well: DngCreator throws it for a frame that is not the size of the
            // sensor, and an Error left to escape this thread ends the process.
            Log.e(TAG, "Failed to write the DNG", e);
            if (mFile.exists() && !mFile.delete()) {
                Log.w(TAG, "Failed to clean up a partially written DNG");
            }
            return false;
        } finally {
            creator.close();
        }
    }

    /** A location DngCreator will not accept costs the DNG its location, not the picture. */
    @WorkerThread
    private void attachLocation(@NonNull DngCreator creator) {
        Location location = getLocation();
        if (location == null) {
            return;
        }
        try {
            creator.setLocation(location);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Failed to record where the picture was taken", e);
        }
    }

    /**
     * The Exif orientation for a picture taken at this rotation, mirrored or not.
     *
     * <p>The answer {@link Exif#rotate} followed by {@link Exif#flipHorizontally} reaches on a
     * JPEG, so the two files of a RAW_JPEG capture agree about which way is up. DngCreator wants
     * it before it writes, so it is worked out here rather than applied afterwards.</p>
     */
    @IntRange(from = 1, to = 8)
    public static int exifOrientation(int degrees, boolean mirrored) {
        switch (((degrees % 360) + 360) % 360) {
            case 90:
                return mirrored
                        ? ExifInterface.ORIENTATION_TRANSPOSE
                        : ExifInterface.ORIENTATION_ROTATE_90;
            case 180:
                return mirrored
                        ? ExifInterface.ORIENTATION_FLIP_VERTICAL
                        : ExifInterface.ORIENTATION_ROTATE_180;
            case 270:
                return mirrored
                        ? ExifInterface.ORIENTATION_TRANSVERSE
                        : ExifInterface.ORIENTATION_ROTATE_270;
            case 0:
                // Fall-through
            default:
                // An angle that is not a right angle has no orientation, and DngCreator accepts
                // nothing outside the eight, so it reads as unrotated.
                return mirrored
                        ? ExifInterface.ORIENTATION_FLIP_HORIZONTAL
                        : ExifInterface.ORIENTATION_NORMAL;
        }
    }

    /**
     * The RAW_SENSOR size a DNG can be written from, or null if the camera offers none.
     *
     * <p>Not simply the largest. DngCreator only writes a frame the size of the pre-correction
     * active array or of the pixel array, and a camera can list RAW outputs that are neither. The
     * pre-correction array comes first because the DNG's metadata is expressed in it.</p>
     */
    @Nullable
    public static Size sensorSize(@Nullable CameraCharacteristics characteristics) {
        if (characteristics == null) {
            return null;
        }
        StreamConfigurationMap map =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] offered = map == null ? null : map.getOutputSizes(ImageFormat.RAW_SENSOR);
        if (offered == null) {
            return null;
        }
        Rect preCorrection = Build.VERSION.SDK_INT >= 23
                ? characteristics.get(
                        CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
                : null;
        Size pixelArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        if (preCorrection != null) {
            Size wanted = new Size(preCorrection.width(), preCorrection.height());
            for (Size candidate : offered) {
                if (candidate.equals(wanted)) {
                    return candidate;
                }
            }
        }
        for (Size candidate : offered) {
            if (candidate.equals(pixelArray)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Where a RAW_JPEG capture puts its DNG: beside the JPEG, under the same name.
     *
     * <p>{@code IMG_1.jpg} gets {@code IMG_1.dng}. A JPEG that is already called {@code .dng}
     * keeps its whole name with {@code .dng} added, so the two files never collide.</p>
     */
    @NonNull
    public static File dngBeside(@NonNull File jpeg) {
        String name = jpeg.getName();
        int dot = name.lastIndexOf('.');
        String dng = (dot > 0 ? name.substring(0, dot) : name) + ".dng";
        if (dng.equals(name)) {
            dng = name + ".dng";
        }
        return new File(jpeg.getParentFile(), dng);
    }
}
