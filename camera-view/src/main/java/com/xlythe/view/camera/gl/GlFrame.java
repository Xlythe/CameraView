package com.xlythe.view.camera.gl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * One camera frame, handed to {@link FrameRenderer#onDrawFrame}.
 *
 * <p>Valid only during that call: the same instance describes the next frame, and the matrix is
 * the library's own array. Read them there, and neither keep nor write to them.</p>
 */
public final class GlFrame {
    // One instance describes every frame for every output, so these change between calls.

    /** Draws the camera frame for {@link #drawCameraFrame}. */
    private final OesTextureProgram mProgram;

    /** The external texture the camera wrote this frame into. */
    private int mTextureName;

    /** Turns the frame's texture coordinates into the ones to sample. */
    private float[] mTextureMatrix = GlMath.identity();

    /** The width of the picture being drawn, the right way up. */
    private int mWidth;

    /** The height of the picture being drawn, the right way up. */
    private int mHeight;

    /** When the camera captured the frame, in monotonic nanoseconds. */
    private long mTimestampNanos;

    /** Where in the output the camera frame goes, as x, y, width and height. */
    private int[] mViewport = {0, 0, 0, 0};

    /**
     * What the camera's SurfaceTexture reported for this frame, which is what says how
     * {@link #mTextureMatrix} turns and crops the buffer the camera wrote.
     */
    private float[] mSurfaceTextureMatrix = GlMath.identity();

    /** The camera's optics and position for this frame, or null where nothing is known. */
    @Nullable private FrameGeometry mGeometry;

    GlFrame(@NonNull OesTextureProgram program) {
        mProgram = program;
    }

    void set(int textureName, @NonNull float[] textureMatrix,
            @NonNull float[] surfaceTextureMatrix, @Nullable FrameGeometry geometry, int width,
            int height, long timestampNanos, @NonNull int[] viewport) {
        mTextureName = textureName;
        mTextureMatrix = textureMatrix;
        mSurfaceTextureMatrix = surfaceTextureMatrix;
        mGeometry = geometry;
        mWidth = width;
        mHeight = height;
        mTimestampNanos = timestampNanos;
        mViewport = viewport;
    }

    /**
     * The {@code GL_TEXTURE_EXTERNAL_OES} texture the camera wrote this frame into. Bind it to that
     * target, not to {@code GL_TEXTURE_2D}, and sample it with {@code samplerExternalOES}.
     */
    public int getTextureName() {
        return mTextureName;
    }

    /**
     * The 4x4 column-major matrix that turns this frame's texture coordinates into the ones to
     * sample, which is where the rotation, the mirror and the crop are.
     *
     * <p>Apply it to a {@code vec4} coordinate in a vertex shader and use the {@code xy} of the
     * result, as {@link #drawCameraFrame} does. Ignoring it draws the raw buffer, which is
     * sideways on most devices and backwards on a front camera.</p>
     */
    @NonNull
    public float[] getTextureMatrix() {
        return mTextureMatrix;
    }

    /** The width in pixels of the picture being drawn, the right way up as it is shown. */
    public int getWidth() {
        return mWidth;
    }

    /** The height in pixels of the picture being drawn, the right way up as it is shown. */
    public int getHeight() {
        return mHeight;
    }

    /**
     * When the camera captured this frame, in monotonic nanoseconds on the clock of
     * {@link android.graphics.SurfaceTexture#getTimestamp}, for lining it up with other sensor
     * readings.
     */
    public long getTimestampNanos() {
        return mTimestampNanos;
    }

    /**
     * Draws the camera frame the right way up, filling the output. An overlay calls this first and
     * then draws on top. It leaves no program or texture bound.
     */
    public void drawCameraFrame() {
        mProgram.draw(mTextureName, mTextureMatrix, mViewport);
    }

    /**
     * The projection that puts a point in the camera's space where the camera saw it in this
     * picture, so that geometry drawn with it lines up with the frame from
     * {@link #drawCameraFrame()}.
     *
     * <p>Built from the lens's own calibration where the camera publishes one, and from ARCore's
     * while it is tracking, rather than from a nominal angle of view: a lens whose optical centre
     * is a few pixels off the middle of the sensor puts a marker a few pixels off where it
     * belongs. It also carries this output's crop and turn, so the same point lands correctly in
     * the preview, the recording and the photograph.</p>
     *
     * <p>The camera's space is held the way the camera wrote its frames - x towards the right of
     * its buffer, y towards the top, looking down -z, in metres while AR tracks - and does not
     * turn with the screen. Combine it with {@link #getViewMatrix} and a model matrix as
     * {@code projection * view * model}. It maps onto the viewport {@link #drawCameraFrame()}
     * leaves set, which overhangs the output when the frame is cropped to fill it. A mirrored
     * picture, as a front camera's preview is, reverses the winding of what is drawn.</p>
     *
     * @param matrix receives the 4x4 column-major matrix
     * @param near the distance to the near clipping plane, greater than zero
     * @param far the distance to the far clipping plane, greater than {@code near}
     * @return whether the camera's optics are known. False leaves the matrix untouched.
     */
    public boolean getProjectionMatrix(@NonNull float[] matrix, float near, float far) {
        checkMatrix(matrix);
        if (!(near > 0f) || !(far > near)) {
            throw new IllegalArgumentException(
                    "The clipping planes must satisfy 0 < near < far, not " + near + ", " + far);
        }
        FrameGeometry geometry = mGeometry;
        if (geometry == null || !geometry.hasIntrinsics()) {
            return false;
        }
        float[] projection;
        try {
            projection = GlMath.projection(geometry.getIntrinsics(),
                    GlMath.outputToBuffer(mTextureMatrix, mSurfaceTextureMatrix), near, far);
        } catch (IllegalArgumentException error) {
            // A matrix that flattens the frame says nothing about where a point lands on it.
            return false;
        }
        System.arraycopy(projection, 0, matrix, 0, GlMath.MATRIX_SIZE);
        return true;
    }

    /**
     * The transform into the camera's space that {@link #getProjectionMatrix} projects from.
     *
     * <p>While AR is tracking it is from the world ARCore tracks, in metres, with y pointing up
     * against gravity, so a point that stays put in the room is drawn where it stays. Otherwise
     * it is from the device itself, in the coordinate system the device's sensors report in - x
     * across the screen in its natural orientation, y up it and z out of it - which is what the
     * rotation from {@link android.hardware.SensorManager#getRotationMatrix} is relative to, so a
     * direction in the world can be pointed at with no tracking at all. Either way it includes
     * where the lens sits on the device and which way it faces, from the camera's own
     * calibration where it publishes one.</p>
     *
     * @param matrix receives the 4x4 column-major matrix
     * @return whether the camera's position is known. False leaves the matrix untouched.
     */
    public boolean getViewMatrix(@NonNull float[] matrix) {
        checkMatrix(matrix);
        FrameGeometry geometry = mGeometry;
        if (geometry == null || !geometry.hasViewMatrix()) {
            return false;
        }
        System.arraycopy(geometry.getViewMatrix(), 0, matrix, 0, GlMath.MATRIX_SIZE);
        return true;
    }

    /**
     * A {@code GL_TEXTURE_2D} holding how far away the scene is across this frame, or 0 when
     * nothing is measuring it. Depth is measured only while AR is tracking, on a device ARCore
     * can measure depth on.
     *
     * <p>This is what lets a real object hide something drawn behind it: compare how far away a
     * fragment is with how far away the scene is at the same place, and draw it only where it is
     * nearer. Sample it at the same coordinates as the camera frame - {@link #getTextureMatrix()}
     * applied to this picture's texture coordinates - since the depth is measured across the
     * camera's own image. Where it reads 0 the distance is unknown, not touching the lens.</p>
     *
     * <p>Each texel is a distance in millimetres along the camera's view direction - the
     * {@code -z} of {@link #getViewMatrix}'s space, not the length of the ray - split over two
     * bytes, low then high, in the luminance and alpha channels:
     * {@code float millimetres = dot(texture2D(depth, uv).ra, vec2(255.0, 65280.0));}. It is
     * smoothed and can lag the camera frame slightly, so an edge is soft rather than exact.</p>
     */
    public int getDepthTextureName() {
        FrameGeometry geometry = mGeometry;
        return geometry == null ? 0 : geometry.getDepthTextureName();
    }

    private static void checkMatrix(@NonNull float[] matrix) {
        if (matrix.length < GlMath.MATRIX_SIZE) {
            throw new IllegalArgumentException(
                    "A matrix is " + GlMath.MATRIX_SIZE + " floats, not " + matrix.length);
        }
    }
}
