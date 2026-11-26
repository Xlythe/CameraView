package com.xlythe.sample.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.PointF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xlythe.view.camera.WorldOverlay;

/**
 * Draws a pillar of light standing at a point in the room, cut off by what stands in front of it.
 *
 * <p>In the sample rather than in the library, because what a marker looks like is the decision
 * of the app. CameraView says where the camera is and how far away the room is; everything here
 * is paint.</p>
 *
 * <p>A pillar rather than a dot because a dot is only findable if you are already looking at it.
 * Something tall is visible over furniture and from across a room, which is the whole use for
 * marking a place in the world - and it means the interesting half of the drawing is the
 * occlusion: the base disappearing behind a sofa while the top does not is what makes it look
 * like it is in the room rather than painted on the glass.</p>
 */
public class PillarOfLightView extends View {
    /** How tall the pillar stands, in metres. Head height, so it clears most furniture. */
    private static final float HEIGHT_METRES = 2f;

    /** How wide it is drawn, in pixels. Not scaled by distance: it is a marker, not an object. */
    private static final float WIDTH_PIXELS = 36f;

    /**
     * How many points up the pillar are tested against the room.
     *
     * <p>Enough that the edge of a piece of furniture cuts the pillar at about the right place,
     * and few enough that the arithmetic is nothing next to a frame. At two metres this is a
     * sample every eight centimetres.</p>
     */
    private static final int SAMPLES = 24;

    /**
     * How much nearer the room has to be before it counts as being in the way, in metres.
     *
     * <p>A depth reading is noisy and is taken at a slightly different moment from the pose, so
     * comparing them exactly makes the pillar flicker along every edge it passes. Four
     * centimetres of slack costs nothing visually and settles it.</p>
     */
    private static final float TOLERANCE_METRES = 0.04f;

    /** How near the lowest visible sample has to be to the ground to count as the base. */
    private static final float BASE_SLACK_PIXELS = 2f;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Where the pillar stands, in the coordinates of the session, or null for nowhere yet. */
    @Nullable
    private float[] mPosition;

    /** The pose of the latest frame, copied because the frame does not outlive its callback. */
    private final float[] mViewMatrix = new float[16];
    private final float[] mViewProjectionMatrix = new float[16];

    /** How far away the room is, for the latest frame, or null when it did not say. */
    @Nullable
    private WorldOverlay.SceneDepth mDepth;

    /** The stretch of the pillar that is not hidden, worked out on the main thread. */
    @Nullable
    private WorldOverlay.VisibleRun mVisible;

    private boolean mHasPose;

    public PillarOfLightView(Context context) {
        this(context, null);
    }

    public PillarOfLightView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mPaint.setStyle(Paint.Style.FILL);
        mGlowPaint.setStyle(Paint.Style.FILL);
        setWillNotDraw(false);
    }

    /** Stands the pillar at a point in the world, or takes it away with null. */
    public void setPosition(@Nullable float[] position) {
        mPosition = position == null ? null : position.clone();
        invalidate();
    }

    /** Whether there is a pillar to draw at all. */
    public boolean hasPosition() {
        return mPosition != null;
    }

    /**
     * Takes the pose and the depth of one frame.
     *
     * <p>Called on the render thread, so nothing here draws and nothing reads a View: the
     * numbers are copied and the work of deciding what is visible is left to {@link #onDraw},
     * which runs where it is allowed to. The depth is the exception - it is a view onto a
     * buffer that is handed back when the callback returns, so what is visible has to be
     * decided here after all.</p>
     */
    public void onPose(@NonNull float[] viewMatrix, @NonNull float[] viewProjectionMatrix,
            @Nullable WorldOverlay.SceneDepth depth) {
        float[] position = mPosition;
        System.arraycopy(viewMatrix, 0, mViewMatrix, 0, mViewMatrix.length);
        System.arraycopy(viewProjectionMatrix, 0, mViewProjectionMatrix, 0,
                mViewProjectionMatrix.length);
        mHasPose = true;
        mDepth = depth;
        if (position == null || getWidth() <= 0 || getHeight() <= 0) {
            mVisible = null;
        } else {
            // While the depth buffer is still valid, which is only inside this call.
            mVisible = WorldOverlay.visiblePillar(mViewMatrix, mViewProjectionMatrix,
                    position[0], position[1], position[2], HEIGHT_METRES,
                    getWidth(), getHeight(), SAMPLES, TOLERANCE_METRES,
                    depth == null ? (x, y) -> 0f : depth);
        }
        postInvalidateOnAnimation();
    }

    /** Stops drawing, because a pose that is no longer tracked is not a pose. */
    public void onTrackingLost() {
        mHasPose = false;
        mVisible = null;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        WorldOverlay.VisibleRun visible = mVisible;
        if (!mHasPose || visible == null) {
            return;
        }
        PointF from = visible.getFrom();
        PointF to = visible.getTo();

        // Brightest at the base and fading upwards, which is what makes it read as light coming
        // out of the floor rather than as a rectangle standing on it.
        mPaint.setShader(new LinearGradient(from.x, from.y, to.x, to.y,
                new int[] {0xFFFFFFFF, 0xCC7FD4FF, 0x1A7FD4FF},
                new float[] {0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(from.x - WIDTH_PIXELS / 2f, Math.min(from.y, to.y),
                from.x + WIDTH_PIXELS / 2f, Math.max(from.y, to.y), mPaint);

        // A pool of light where it meets the floor, so the base has somewhere to stand. Only
        // when the base itself is what is visible: the run starts at the lowest point that is
        // not hidden, which for a pillar standing behind a sofa is partway up it, and lighting
        // the floor there would put the pool on the sofa.
        if (isTheBaseVisible(from)) {
            mGlowPaint.setShader(new RadialGradient(from.x, from.y, WIDTH_PIXELS * 2f,
                    0x667FD4FF, Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawCircle(from.x, from.y, WIDTH_PIXELS * 2f, mGlowPaint);
        }
    }

    /**
     * Whether the lowest visible point of the pillar is its actual base.
     *
     * <p>Compared in the view rather than in metres because that is where the answer is used,
     * and a few pixels of slack covers the pillar being sampled at intervals: the lowest sample
     * that is not hidden sits just above the ground even when nothing is in the way.</p>
     */
    private boolean isTheBaseVisible(@NonNull PointF lowestVisible) {
        float[] position = mPosition;
        if (position == null) {
            return false;
        }
        PointF base = new PointF();
        PointF top = new PointF();
        if (!WorldOverlay.project(mViewProjectionMatrix, position[0], position[1], position[2],
                getWidth(), getHeight(), base)
                || !WorldOverlay.project(mViewProjectionMatrix,
                        position[0], position[1] + HEIGHT_METRES, position[2],
                        getWidth(), getHeight(), top)) {
            return false;
        }
        // One sample of the pillar, in pixels, plus a little for the pillar seen end-on.
        float slack = Math.abs(base.y - top.y) / SAMPLES + BASE_SLACK_PIXELS;
        return Math.abs(base.y - lowestVisible.y) <= slack;
    }
}
