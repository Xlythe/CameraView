package com.xlythe.view.camera.pano;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import static com.xlythe.view.camera.ICameraModule.TAG;

/**
 * The sphere being painted, one frame at a time.
 *
 * <p>An equirectangular bitmap that frames are warped onto as they arrive. Warped rather than
 * placed, because the mapping from a flat frame to a sphere is not something a rectangle can
 * express: the top of a frame covers more longitude than its middle, and more so the further
 * from the horizon it is pointed. A frame drawn as a rectangle is right in the centre and wrong
 * at the corners, which on a finished sweep is a row of wedges that do not meet.</p>
 *
 * <p>So each frame is drawn as a mesh. The frame is cut into a grid, every vertex of the grid is
 * projected onto the canvas through {@link Equirectangular}, and the frame is stretched over the
 * result - which is what {@link Canvas#drawBitmapMesh} is for. Inside each little quad the
 * mapping is treated as flat, which it nearly is, and the grid is fine enough that the error is
 * under a pixel.</p>
 *
 * <h2>The seam</h2>
 *
 * <p>A frame pointed at the back of the sphere straddles both edges of the canvas, because the
 * canvas is a cylinder that has been cut open. Drawing it once puts half of it off the edge and
 * loses it, so it is drawn again a canvas-width to each side; the copies land outside and are
 * clipped, and the half that belongs at the far edge arrives there.</p>
 *
 * <p>Not thread safe, and not meant to be: it holds a bitmap the size of the sphere and is
 * written from whichever thread the frames arrive on.</p>
 */
public final class PanoramaCanvas {
    /**
     * How many quads across and down each frame is cut into.
     *
     * <p>Eight is where the error from treating each quad as flat falls under a pixel for an
     * ordinary phone lens on a canvas a few thousand pixels wide, and it is 81 projections per
     * frame, which is nothing. Fewer shows as faint creases along the quad edges.</p>
     */
    @VisibleForTesting
    static final int MESH_QUADS = 8;

    private final Bitmap mCanvas;
    private final Canvas mPainter;
    private final Paint mPaint;
    private final float[] mVertices = new float[(MESH_QUADS + 1) * (MESH_QUADS + 1) * 2];
    private final PointF mProjected = new PointF();

    /** What is being asked for, which decides the crop and the metadata at the end. */
    private final PanoramaCoverage mCoverage;

    private int mFramesDrawn;

    /**
     * @param canvasWidth how wide the full sphere is, in pixels. The height follows from it
     * @param coverage what the sweep is asking for, so the result can be cropped to it
     */
    public PanoramaCanvas(int canvasWidth, @NonNull PanoramaCoverage coverage) {
        int height = Equirectangular.canvasHeightFor(canvasWidth);
        mCanvas = Bitmap.createBitmap(canvasWidth, height, Bitmap.Config.ARGB_8888);
        mPainter = new Canvas(mCanvas);
        mCoverage = coverage;
        mPaint = new Paint();
        // Filtered, because every frame is being stretched by a different amount and the
        // nearest-neighbour alternative shows the grid.
        mPaint.setFilterBitmap(true);
        mPaint.setAntiAlias(true);
    }

    /** How wide the sphere is, in pixels. */
    public int getWidth() {
        return mCanvas.getWidth();
    }

    /** How tall it is, which is always half the width. */
    public int getHeight() {
        return mCanvas.getHeight();
    }

    /** How many frames have been painted on, for a caller that wants to know it did something. */
    public int getFramesDrawn() {
        return mFramesDrawn;
    }

    /**
     * Paints one frame onto the sphere.
     *
     * <p>The frame is expected upright - the rotation already applied - because the rotation
     * matrix says where the camera was pointed and not which way up its sensor is.</p>
     *
     * @param frame the frame, upright
     * @param rotationMatrix where the camera was pointed, 16 floats column-major
     * @param horizontalFieldOfViewDegrees the lens angle of view across the upright frame
     * @param verticalFieldOfViewDegrees its angle of view down the upright frame
     * @return whether anything was painted
     */
    public boolean draw(@NonNull Bitmap frame, @NonNull float[] rotationMatrix,
            float horizontalFieldOfViewDegrees, float verticalFieldOfViewDegrees) {
        if (!buildMesh(rotationMatrix,
                horizontalFieldOfViewDegrees, verticalFieldOfViewDegrees)) {
            return false;
        }
        try {
            drawMesh(frame, 0f);
            // The copies that carry the far side of a frame across the cut in the cylinder. Both
            // directions, because a frame can straddle either edge, and both are no-ops when it
            // straddles neither: everything they draw lands outside the canvas and is clipped.
            if (liesOffTheCanvas()) {
                drawMesh(frame, -getWidth());
                drawMesh(frame, getWidth());
            }
            mFramesDrawn++;
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to paint a frame onto the panorama", error);
            return false;
        }
    }

    /**
     * Where each vertex of the frame's grid lands on the canvas.
     *
     * <p>Measured from the middle of the frame outwards, so the grid stays in one piece. Every
     * vertex is within half a turn of the frame's centre, and expressing them all that way
     * leaves a frame pointed at the cut in the cylinder as a coherent shape lying off the edge
     * of the canvas - which {@link #draw} then shifts into place. Wrapping each vertex onto the
     * canvas as it is computed instead puts neighbouring vertices at opposite ends of the
     * picture, and the quad between them is stretched across everything.</p>
     *
     * @return whether the whole grid could be placed, which it cannot without a usable angle of
     *     view - and half a grid is worse than none, since the frame would be drawn folded
     */
    private boolean buildMesh(@NonNull float[] rotationMatrix,
            float horizontalFieldOfViewDegrees, float verticalFieldOfViewDegrees) {
        float[] direction = new float[2];
        if (!Equirectangular.directionFor(0.5f, 0.5f, rotationMatrix,
                horizontalFieldOfViewDegrees, verticalFieldOfViewDegrees, direction)) {
            return false;
        }
        float centreYaw = direction[0];
        int at = 0;
        for (int row = 0; row <= MESH_QUADS; row++) {
            float v = (float) row / MESH_QUADS;
            for (int column = 0; column <= MESH_QUADS; column++) {
                float u = (float) column / MESH_QUADS;
                if (!Equirectangular.directionFor(u, v, rotationMatrix,
                        horizontalFieldOfViewDegrees, verticalFieldOfViewDegrees, direction)) {
                    return false;
                }
                Equirectangular.projectUnwrapped(
                        Equirectangular.unwrapYawNear(centreYaw, direction[0]), direction[1],
                        getWidth(), getHeight(), mProjected);
                mVertices[at++] = mProjected.x;
                mVertices[at++] = mProjected.y;
            }
        }
        return true;
    }

    /**
     * Whether any of the mesh lies off the canvas, and so needs a copy bringing it round.
     *
     * <p>True for a frame at the cut in the cylinder, which is the case the copies exist for,
     * and also for one pointed just past either edge. Both want the same treatment, so the test
     * is simply whether anything fell outside rather than anything about where the camera was
     * pointed.</p>
     */
    private boolean liesOffTheCanvas() {
        for (int at = 0; at < mVertices.length; at += 2) {
            if (mVertices[at] < 0f || mVertices[at] >= getWidth()) {
                return true;
            }
        }
        return false;
    }

    private void drawMesh(@NonNull Bitmap frame, float offsetX) {
        if (offsetX == 0f) {
            mPainter.drawBitmapMesh(frame, MESH_QUADS, MESH_QUADS, mVertices, 0, null, 0, mPaint);
            return;
        }
        mPainter.save();
        mPainter.translate(offsetX, 0f);
        mPainter.drawBitmapMesh(frame, MESH_QUADS, MESH_QUADS, mVertices, 0, null, 0, mPaint);
        mPainter.restore();
    }

    /**
     * Writes the sphere out as a panorama, cropped to what was actually asked for.
     *
     * <p>Cropped because a sweep along the horizon is a band out of a sphere, and saving the
     * whole canvas would be a picture that is mostly empty. The crop is recorded in the
     * metadata, so a viewer knows the band is a band rather than stretching it over everything.
     * </p>
     *
     * @param file where to write the JPEG
     * @param quality 0 to 100
     * @param headingDegrees the compass heading the sweep started at, or NaN if unknown
     * @return whether a file was written
     */
    public boolean save(@NonNull File file, int quality, float headingDegrees) {
        Rect crop = cropToCoverage();
        if (crop.isEmpty()) {
            Log.w(TAG, "There is nothing in this panorama to save");
            return false;
        }
        Bitmap cropped = null;
        try {
            cropped = crop.width() == getWidth() && crop.height() == getHeight()
                    ? mCanvas
                    : Bitmap.createBitmap(mCanvas, crop.left, crop.top, crop.width(),
                            crop.height());
            try (OutputStream out = new FileOutputStream(file)) {
                if (!cropped.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                    Log.w(TAG, "Unable to compress the panorama");
                    return false;
                }
            }
        } catch (IOException | RuntimeException | OutOfMemoryError error) {
            Log.w(TAG, "Unable to write the panorama to " + file, error);
            return false;
        } finally {
            if (cropped != null && cropped != mCanvas) {
                cropped.recycle();
            }
        }
        // The pixels alone are a picture with an odd shape; this is what makes them a panorama.
        return new GPanoMetadata(crop.width(), crop.height(), getWidth(), getHeight(),
                crop.left, crop.top, headingDegrees).writeTo(file);
    }

    /**
     * The part of the canvas the sweep was asked to cover.
     *
     * <p>From what was asked for rather than from what was painted, so that a sweep which fell
     * a few degrees short still produces a picture of the shape the caller wanted, with the gap
     * left black - which is honest, and which the metadata explains. Measuring the painted
     * pixels instead would make the output size depend on how steadily somebody turned.</p>
     */
    @VisibleForTesting
    @NonNull
    Rect cropToCoverage() {
        PointF topLeft = new PointF();
        PointF bottomRight = new PointF();
        float startYaw = mCoverage.getStartYaw();
        float centreYaw = Float.isNaN(startYaw) ? 0f : startYaw;
        float half = mCoverage.getYawSpan() / 2;

        Equirectangular.project(centreYaw - half, mCoverage.getMaxPitch(),
                getWidth(), getHeight(), topLeft);
        Equirectangular.project(centreYaw + half, mCoverage.getMinPitch(),
                getWidth(), getHeight(), bottomRight);

        int top = Math.max(0, Math.round(topLeft.y));
        int bottom = Math.min(getHeight(), Math.round(bottomRight.y));
        if (mCoverage.getYawSpan() >= Equirectangular.FULL_TURN_DEGREES) {
            // A full turn is the whole width, and asking the projection for it would wrap the
            // two edges onto the same column and give a crop one pixel wide.
            return new Rect(0, top, getWidth(), bottom);
        }
        int left = Math.max(0, Math.round(topLeft.x));
        int right = Math.min(getWidth(), Math.round(bottomRight.x));
        if (right <= left) {
            // The span crosses the cut in the cylinder, so it is not one rectangle. Saving the
            // full width keeps every pixel, and the metadata still says where the sweep sits.
            return new Rect(0, top, getWidth(), bottom);
        }
        return new Rect(left, top, right, bottom);
    }

    /** The sphere as it stands, for a caller that wants to show the sweep as it happens. */
    @NonNull
    public Bitmap getBitmap() {
        return mCanvas;
    }

    /** Gives the bitmap back. Nothing may be drawn or saved afterwards. */
    public void recycle() {
        mCanvas.recycle();
    }
}
