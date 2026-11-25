package com.xlythe.view.camera.pano;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import static com.xlythe.view.camera.ICameraModule.TAG;

/**
 * Turns a camera frame into something that can be drawn.
 *
 * <p>Camera frames arrive as three planes of YUV, which is what every camera produces without
 * conversion and what no drawing API accepts. Anything that wants to compose frames - stitching
 * them onto a sphere, here - has to get them into pixels first, and the conversion is the part
 * that is easy to get subtly wrong: the planes are not packed.</p>
 *
 * <h2>Why the strides matter</h2>
 *
 * <p>Each plane has a row stride that is usually wider than the image, because the camera aligns
 * its rows, and the chroma planes have a pixel stride that is usually two, because U and V are
 * interleaved in one buffer rather than stored separately. Reading them as if they were packed
 * gives a picture that is sheared a little further over on every row, with colour that drifts -
 * which looks like a bad camera rather than like a bug.</p>
 *
 * <p>The route out is through JPEG, by way of {@link YuvImage}. It costs a compress and a
 * decode per frame and a little quality, and it is the only conversion in the framework that
 * works on every version this library supports; frames being blended into a panorama are the
 * case where that trade is clearly worth it.</p>
 */
public final class YuvFrame {
    /**
     * How hard to compress on the way through JPEG.
     *
     * <p>High, because this is not the file anyone keeps - it is an intermediate on the way to
     * a canvas that will itself be compressed. The artefacts that matter are the ones that
     * survive being blended, and above about ninety there are none.</p>
     */
    private static final int INTERMEDIATE_QUALITY = 92;

    private YuvFrame() {}

    /**
     * One frame as a bitmap, upright, or null if it could not be converted.
     *
     * @param image a {@link ImageFormat#YUV_420_888} frame, still open
     * @param rotationDegrees the clockwise rotation that makes it upright, as
     *     {@code FrameStream.Frame.getRotationDegrees} reports it
     * @param mirrored whether to flip it, for a frame from a user-facing lens
     */
    @Nullable
    public static Bitmap toBitmap(@NonNull Image image, int rotationDegrees, boolean mirrored) {
        if (image.getFormat() != ImageFormat.YUV_420_888) {
            Log.w(TAG, "Only YUV_420_888 frames can be converted, not " + image.getFormat());
            return null;
        }
        try {
            byte[] nv21 = toNv21(image);
            YuvImage yuv = new YuvImage(
                    nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            if (!yuv.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()),
                    INTERMEDIATE_QUALITY, jpeg)) {
                return null;
            }
            byte[] bytes = jpeg.toByteArray();
            Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (decoded == null || (rotationDegrees == 0 && !mirrored)) {
                return decoded;
            }
            return orient(decoded, rotationDegrees, mirrored);
        } catch (RuntimeException | OutOfMemoryError error) {
            // A frame is not worth a crash: the next one usually converts, and a sweep that
            // dropped one has a gap rather than no panorama.
            Log.w(TAG, "Unable to convert a camera frame", error);
            return null;
        }
    }

    /**
     * The frame as NV21: all the luma, then V and U interleaved.
     *
     * <p>NV21 rather than the planar form because that is the one {@link YuvImage} takes. The
     * order is the part worth saying out loud - V comes before U, which is the opposite of what
     * the name YUV suggests and the usual way to get a picture with the colours swapped.</p>
     */
    @VisibleForTesting
    @NonNull
    static byte[] toNv21(@NonNull Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] nv21 = new byte[width * height * 3 / 2];
        Image.Plane[] planes = image.getPlanes();

        copyPlane(planes[0], width, height, nv21, 0, 1);

        // Chroma is half the size in each direction, and interleaved into the back half of the
        // buffer. Written V first.
        int chromaWidth = width / 2;
        int chromaHeight = height / 2;
        int chromaStart = width * height;
        copyPlane(planes[2], chromaWidth, chromaHeight, nv21, chromaStart, 2);
        copyPlane(planes[1], chromaWidth, chromaHeight, nv21, chromaStart + 1, 2);
        return nv21;
    }

    /**
     * Copies one plane out, honouring both strides.
     *
     * <p>A row at a time, and a whole row at a time where the plane is packed - which is the
     * common case for luma and is worth the branch, because the alternative is a per-pixel loop
     * over a couple of million pixels per frame.</p>
     *
     * @param destinationStride how far apart the written samples are, 1 for luma and 2 for the
     *     interleaved chroma
     */
    private static void copyPlane(@NonNull Image.Plane plane, int width, int height,
            @NonNull byte[] destination, int destinationOffset, int destinationStride) {
        ByteBuffer source = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        byte[] row = new byte[rowStride];
        int written = destinationOffset;
        for (int y = 0; y < height; y++) {
            int rowStart = y * rowStride;
            if (rowStart >= source.limit()) {
                break;
            }
            int available = Math.min(rowStride, source.limit() - rowStart);
            source.position(rowStart);
            source.get(row, 0, available);
            if (pixelStride == 1 && destinationStride == 1) {
                System.arraycopy(row, 0, destination, written, Math.min(width, available));
                written += width;
                continue;
            }
            int offset = written;
            for (int x = 0; x < width; x++) {
                int at = x * pixelStride;
                if (at >= available || offset >= destination.length) {
                    break;
                }
                destination[offset] = row[at];
                offset += destinationStride;
            }
            written += width * destinationStride;
        }
    }

    /**
     * The same bitmap turned upright, and mirrored if it came from a front lens.
     *
     * <p>The mirror is applied before the rotation, because it is a property of the lens and the
     * rotation is a property of how the phone is held. Doing them the other way round mirrors
     * the wrong axis for every orientation but one.</p>
     */
    @Nullable
    private static Bitmap orient(@NonNull Bitmap source, int rotationDegrees, boolean mirrored) {
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        if (mirrored) {
            matrix.postScale(-1f, 1f, source.getWidth() / 2f, source.getHeight() / 2f);
        }
        matrix.postRotate(rotationDegrees);
        try {
            Bitmap oriented = Bitmap.createBitmap(
                    source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
            if (oriented != source) {
                source.recycle();
            }
            return oriented;
        } catch (RuntimeException | OutOfMemoryError error) {
            Log.w(TAG, "Unable to turn a camera frame upright", error);
            return source;
        }
    }
}
