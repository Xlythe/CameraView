package com.xlythe.view.camera.pano;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Display;
import android.view.Surface;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;

import com.xlythe.view.camera.TransformMath;

/**
 * The guide {@link com.xlythe.view.camera.CameraView} draws over its preview while a panorama or
 * photosphere is swept.
 *
 * <p>Everything that marks a direction is pinned to the world rather than to the screen: it is
 * projected through the same rotation and lens as the preview, so a target stays on the wall it
 * marks as the phone turns, and reaching it is a matter of putting the reticle on it. What is
 * drawn:</p>
 *
 * <ul>
 *   <li>A reticle in the middle, ringed by the progress so far. It fills when it is on the
 *       target, turns orange while the phone turns too fast for a sharp frame, and shows a tick
 *       once everything is covered.</li>
 *   <li>The target to aim at next, and smaller dots on the targets still to come.</li>
 *   <li>Chevrons from the reticle towards the target whenever it is off screen or far from
 *       the middle, which is which way to turn.</li>
 *   <li>For a panorama, the line it is being swept along, to keep the sweep level.</li>
 *   <li>A map of what has been covered along the top: the sphere so far, filling in as frames
 *       are painted, with the view's outline on it.</li>
 * </ul>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public class PanoramaGuideView extends View {
    /** The colour everything is drawn in by default. */
    @ColorInt static final int WHITE = 0xFFFFFFFF;

    /** Progress, the target, and being on it; the focus reticle's locked colour. */
    @ColorInt static final int ACCENT = 0xFFFFD54F;

    /** Turning too fast. */
    @ColorInt static final int WARNING = 0xFFFF8A65;

    /** Drawn under the white strokes so they read over a bright scene as well as a dark one. */
    @ColorInt private static final int SHADOW = 0x59000000;

    /** Behind the map. */
    @ColorInt private static final int MAP_BACKGROUND = 0x73000000;

    /** The reticle's radius. */
    private static final float RETICLE_DP = 30f;

    /** How far outside the reticle the progress ring runs. */
    private static final float RING_GAP_DP = 7f;

    /** The next target's radius. */
    private static final float TARGET_DP = 8f;

    /** The other targets' radius. */
    private static final float DOT_DP = 4f;

    /** How close the map comes to the edges of the view. */
    private static final float MARGIN_DP = 16f;

    /** The widest the photosphere map is drawn. */
    private static final float SPHERE_MAP_DP = 200f;

    /** The widest the panorama map is drawn. */
    private static final float BAND_MAP_DP = 480f;

    /** How far above and below its line the panorama map reaches, in degrees. */
    private static final float BAND_HALF_DEGREES = 40f;

    /** How many chevrons point the way, and how far apart they are. */
    private static final int CHEVRONS = 3;

    /** How far apart the chevrons are. */
    private static final float CHEVRON_SPACING_DP = 13f;

    /** How long the chevrons take to ripple outwards once, and the target to pulse. */
    private static final long CYCLE_MILLIS = 1000;

    /** The panorama line is drawn this far either side of where the camera points. */
    private static final float LINE_REACH_DEGREES = 90f;

    /** The panorama line is a polyline through a point this many degrees apart. */
    private static final float LINE_STEP_DEGREES = 2f;

    /** Where the guide gets what it draws, or null when there is no sweep. */
    @Nullable private GuideState.Source mSource;

    /** A moment to draw at instead of now, for a screenshot; -1 to follow the clock. */
    private long mFrozenTime = -1;

    /** The guide's copy of the sweep's thumbnail. */
    @Nullable private Bitmap mThumbnail;

    /** The version of the sweep's thumbnail last copied. */
    private int mThumbnailVersion = -1;

    // Scratch, so drawing allocates nothing.

    /** For strokes. */
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** For fills. */
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** For the map's picture, scaled smoothly. */
    private final Paint mBitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    /** The device's rotation, as drawn. */
    private final float[] mRotation = new float[9];

    /** A world direction. */
    private final float[] mDirection = new float[3];

    /** A point, in frame then view coordinates. */
    private final float[] mPoint = new float[2];

    /** The previous point along a line. */
    private final float[] mPrevious = new float[2];

    /** The progress ring's bounds, and the view's outline on the map. */
    private final RectF mRect = new RectF();

    /** Where the map is drawn. */
    private final RectF mMap = new RectF();

    /** The rows of the thumbnail the map shows. */
    private final Rect mThumbnailBounds = new Rect();

    /** Lines, chevrons and the tick. */
    private final Path mPath = new Path();

    public PanoramaGuideView(@NonNull Context context) {
        super(context);
        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeCap(Paint.Cap.ROUND);
        mStroke.setStrokeJoin(Paint.Join.ROUND);
        mFill.setStyle(Paint.Style.FILL);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Draws the guide for a sweep, or nothing when given null. */
    @UiThread
    public void setSession(@Nullable PanoramaSession session) {
        setSource(session);
    }

    @UiThread
    void setSource(@Nullable GuideState.Source source) {
        mSource = source;
        mThumbnailVersion = -1;
        invalidate();
    }

    /** Draws as if at this moment, so a screenshot of an animation is the same every time. */
    @VisibleForTesting
    void freezeTime(long uptimeMillis) {
        mFrozenTime = uptimeMillis;
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        GuideState.Source source = mSource;
        if (source == null) {
            return;
        }
        if (mFrozenTime < 0) {
            // The guide follows the sensor, which changes faster than anything invalidates it.
            postInvalidateOnAnimation();
        }
        GuideState state = source.getGuideState();
        if (state == null || state.frameWidth <= 0 || state.focal <= 0f) {
            return;
        }
        long now = mFrozenTime >= 0 ? mFrozenTime : SystemClock.uptimeMillis();
        CameraPose pose = null;
        Matrix frameToView = null;
        if (source.getDisplayRotation(mRotation)) {
            pose = new CameraPose(mRotation, state.sensorRotation, state.frontFacing,
                    state.frameWidth, state.frameHeight, state.focal);
            frameToView = TransformMath.centerCrop(
                    new RectF(0f, 0f, state.frameWidth, state.frameHeight),
                    TransformMath.within360(state.sensorRotation - displayRotation()),
                    state.frontFacing, getWidth(), getHeight());
        }
        // The map first, so a target above the middle of the view is drawn over it rather than
        // hidden under it.
        drawMap(canvas, state, pose);
        boolean sweeping = state.status == GuideState.SWEEPING
                || state.status == GuideState.TOO_FAST;
        boolean aligned = false;
        if (pose != null && sweeping) {
            if (!state.photosphere) {
                drawLine(canvas, pose, frameToView, state.bandPitch);
            }
            aligned = drawTargets(canvas, pose, frameToView, state, now);
        }
        drawReticle(canvas, state, aligned, now);
    }

    /** The panorama's line, pinned to the world, to keep the sweep level. */
    private void drawLine(@NonNull Canvas canvas, @NonNull CameraPose pose,
            @NonNull Matrix frameToView, float pitch) {
        mPath.reset();
        boolean drawing = false;
        float centre = pose.yaw();
        float reach = Math.max(getWidth(), getHeight());
        for (float offset = -LINE_REACH_DEGREES; offset <= LINE_REACH_DEGREES;
                offset += LINE_STEP_DEGREES) {
            boolean visible = toView(pose, frameToView, centre + offset, pitch, mPoint)
                    && Math.abs(mPoint[0] - getWidth() / 2f) < 2 * reach
                    && Math.abs(mPoint[1] - getHeight() / 2f) < 2 * reach;
            if (!visible) {
                drawing = false;
                continue;
            }
            if (drawing) {
                mPath.lineTo(mPoint[0], mPoint[1]);
            } else {
                mPath.moveTo(mPoint[0], mPoint[1]);
                drawing = true;
            }
        }
        strokeWithShadow(canvas, mPath, dp(1.5f), WHITE, 0xB0);
    }

    /**
     * The targets still to cover, the next one standing out, and the way to it.
     *
     * @return whether the reticle is on the next target
     */
    private boolean drawTargets(@NonNull Canvas canvas, @NonNull CameraPose pose,
            @NonNull Matrix frameToView, @NonNull GuideState state, long now) {
        for (int t = 0; t < state.targetYaws.length; t++) {
            if (state.targetsDone[t] || t == state.nextTarget) {
                continue;
            }
            if (toView(pose, frameToView, state.targetYaws[t], state.targetPitches[t], mPoint)
                    && isOnScreen(mPoint, 0f)) {
                mStroke.setStrokeWidth(dp(3f));
                mStroke.setColor(SHADOW);
                canvas.drawCircle(mPoint[0], mPoint[1], dp(DOT_DP), mStroke);
                mStroke.setStrokeWidth(dp(1.5f));
                mStroke.setColor(WHITE);
                mStroke.setAlpha(0xC0);
                canvas.drawCircle(mPoint[0], mPoint[1], dp(DOT_DP), mStroke);
            }
        }
        int next = state.nextTarget;
        if (next < 0) {
            return false;
        }
        SphereMath.direction(state.targetYaws[next], state.targetPitches[next], mDirection);
        boolean aligned = SphereMath.angleBetween(mDirection, pose.forward)
                <= PanoramaSession.ALIGNED_DEGREES;
        float centreX = getWidth() / 2f;
        float centreY = getHeight() / 2f;
        boolean visible = toView(pose, frameToView, state.targetYaws[next],
                state.targetPitches[next], mPoint) && isOnScreen(mPoint, dp(TARGET_DP));
        float phase = (now % CYCLE_MILLIS) / (float) CYCLE_MILLIS;
        if (visible) {
            float pulse = 1f + 0.15f * (float) Math.sin(2 * Math.PI * phase);
            float radius = dp(TARGET_DP) * pulse;
            mFill.setColor(SHADOW);
            canvas.drawCircle(mPoint[0], mPoint[1], radius + dp(2f), mFill);
            mFill.setColor(aligned ? ACCENT : WHITE);
            canvas.drawCircle(mPoint[0], mPoint[1], radius, mFill);
            mStroke.setStrokeWidth(dp(1.5f));
            mStroke.setColor(aligned ? ACCENT : WHITE);
            mStroke.setAlpha(0x90);
            canvas.drawCircle(mPoint[0], mPoint[1], radius * 2f, mStroke);
        }
        float distance = visible
                ? (float) Math.hypot(mPoint[0] - centreX, mPoint[1] - centreY) : Float.MAX_VALUE;
        if (distance > dp(RETICLE_DP) * 2.5f) {
            // Which way the target lies across the picture, even when it is behind the lens.
            mPoint[0] = mDirection[0] * pose.right[0] + mDirection[1] * pose.right[1]
                    + mDirection[2] * pose.right[2];
            mPoint[1] = mDirection[0] * pose.down[0] + mDirection[1] * pose.down[1]
                    + mDirection[2] * pose.down[2];
            frameToView.mapVectors(mPoint);
            float length = (float) Math.hypot(mPoint[0], mPoint[1]);
            float dx = length > 0f ? mPoint[0] / length : 1f;
            float dy = length > 0f ? mPoint[1] / length : 0f;
            drawChevrons(canvas, centreX, centreY, dx, dy, phase);
        }
        return aligned;
    }

    /** Chevrons from the reticle along (dx, dy), brightening outwards in turn. */
    private void drawChevrons(@NonNull Canvas canvas, float centreX, float centreY, float dx,
            float dy, float phase) {
        float start = dp(RETICLE_DP + RING_GAP_DP) + dp(12f);
        float half = dp(6f);
        for (int i = 0; i < CHEVRONS; i++) {
            float along = start + i * dp(CHEVRON_SPACING_DP);
            float x = centreX + dx * along;
            float y = centreY + dy * along;
            mPath.reset();
            mPath.moveTo(x - dx * half - dy * half, y - dy * half + dx * half);
            mPath.lineTo(x, y);
            mPath.lineTo(x - dx * half + dy * half, y - dy * half - dx * half);
            boolean lit = i == (int) (phase * CHEVRONS);
            strokeWithShadow(canvas, mPath, dp(2.5f), WHITE, lit ? 0xFF : 0x90);
        }
    }

    /** The reticle in the middle, with the progress round it. */
    private void drawReticle(@NonNull Canvas canvas, @NonNull GuideState state,
            boolean aligned, long now) {
        float centreX = getWidth() / 2f;
        float centreY = getHeight() / 2f;
        float radius = dp(RETICLE_DP);
        boolean finished = state.status == GuideState.COMPLETE
                || state.status == GuideState.SAVING;
        int colour = state.status == GuideState.TOO_FAST ? WARNING
                : aligned || finished ? ACCENT : WHITE;

        if (aligned || finished) {
            mFill.setColor(ACCENT);
            mFill.setAlpha(finished ? 0x66 : 0x40);
            canvas.drawCircle(centreX, centreY, radius, mFill);
        }
        mStroke.setStrokeWidth(dp(4f));
        mStroke.setColor(SHADOW);
        canvas.drawCircle(centreX, centreY, radius, mStroke);
        mStroke.setStrokeWidth(dp(2f));
        mStroke.setColor(colour);
        canvas.drawCircle(centreX, centreY, radius, mStroke);

        float ring = radius + dp(RING_GAP_DP);
        mRect.set(centreX - ring, centreY - ring, centreX + ring, centreY + ring);
        mStroke.setStrokeWidth(dp(3f));
        mStroke.setColor(WHITE);
        mStroke.setAlpha(0x40);
        canvas.drawCircle(centreX, centreY, ring, mStroke);
        mStroke.setColor(ACCENT);
        if (state.status == GuideState.SAVING) {
            float spin = (now % CYCLE_MILLIS) * 360f / CYCLE_MILLIS;
            canvas.drawArc(mRect, spin - 90f, 90f, false, mStroke);
        } else {
            canvas.drawArc(mRect, -90f, 360f * Math.max(0f, Math.min(1f, state.progress)),
                    false, mStroke);
        }

        if (finished) {
            mPath.reset();
            mPath.moveTo(centreX - radius * 0.4f, centreY + radius * 0.02f);
            mPath.lineTo(centreX - radius * 0.1f, centreY + radius * 0.32f);
            mPath.lineTo(centreX + radius * 0.45f, centreY - radius * 0.3f);
            strokeWithShadow(canvas, mPath, dp(3f), WHITE, 0xFF);
        }
    }

    /** The sphere so far, along the top, with the view marked on it. */
    private void drawMap(@NonNull Canvas canvas, @NonNull GuideState state,
            @Nullable CameraPose pose) {
        float margin = dp(MARGIN_DP);
        float available = getWidth() - 2 * margin;
        if (available <= 0f) {
            return;
        }
        float top;
        float bottom;
        float width;
        if (state.photosphere) {
            width = Math.min(available, dp(SPHERE_MAP_DP));
            top = 90f;
            bottom = -90f;
        } else {
            width = Math.min(available, dp(BAND_MAP_DP));
            top = Math.min(90f, state.bandPitch + BAND_HALF_DEGREES);
            bottom = Math.max(-90f, state.bandPitch - BAND_HALF_DEGREES);
        }
        float height = width * (top - bottom) / 360f;
        float left = (getWidth() - width) / 2f;
        mMap.set(left, margin, left + width, margin + height);
        float corner = dp(6f);

        canvas.save();
        if (state.frontFacing) {
            // The preview of a front lens is mirrored, and so is the picture it saves.
            canvas.scale(-1f, 1f, mMap.centerX(), mMap.centerY());
        }
        mFill.setColor(MAP_BACKGROUND);
        canvas.drawRoundRect(mMap, corner, corner, mFill);
        canvas.save();
        mPath.reset();
        mPath.addRoundRect(mMap, corner, corner, Path.Direction.CW);
        canvas.clipPath(mPath);

        int[] thumbnail = state.thumbnail;
        int thumbnailHeight = state.thumbnailWidth / 2;
        if (thumbnail != null && state.thumbnailWidth > 0) {
            if (mThumbnail == null || mThumbnail.getWidth() != state.thumbnailWidth) {
                mThumbnail = Bitmap.createBitmap(
                        state.thumbnailWidth, thumbnailHeight, Bitmap.Config.ARGB_8888);
                mThumbnailVersion = -1;
            }
            if (mThumbnailVersion != state.thumbnailVersion) {
                mThumbnail.setPixels(thumbnail, 0, state.thumbnailWidth, 0, 0,
                        state.thumbnailWidth, thumbnailHeight);
                mThumbnailVersion = state.thumbnailVersion;
            }
            mThumbnailBounds.set(0, Math.round((90f - top) / 180f * thumbnailHeight),
                    state.thumbnailWidth, Math.round((90f - bottom) / 180f * thumbnailHeight));
            canvas.drawBitmap(mThumbnail, mThumbnailBounds, mMap, mBitmapPaint);
        }

        // The targets still to cover, and the next one.
        for (int t = 0; t < state.targetYaws.length; t++) {
            if (state.targetsDone[t]) {
                continue;
            }
            boolean next = t == state.nextTarget;
            mFill.setColor(next ? ACCENT : WHITE);
            mFill.setAlpha(next ? 0xFF : 0xA0);
            canvas.drawCircle(mapX(state.targetYaws[t], state.startYaw),
                    mapY(state.targetPitches[t], top, bottom), dp(next ? 3f : 1.5f), mFill);
        }

        // Where the camera is pointing, and how much it sees.
        if (pose != null) {
            float pitch = pose.pitch();
            float halfWidth = pose.uprightHorizontalFov() / 360f * mMap.width() / 2f
                    / Math.max(0.25f, (float) Math.cos(Math.toRadians(pitch)));
            float halfHeight = pose.uprightVerticalFov() / (top - bottom) * mMap.height() / 2f;
            float x = mapX(pose.yaw(), state.startYaw);
            float y = mapY(pitch, top, bottom);
            mStroke.setStrokeWidth(dp(1.5f));
            mStroke.setColor(WHITE);
            for (float shift : new float[] {-mMap.width(), 0f, mMap.width()}) {
                mRect.set(x + shift - halfWidth, y - halfHeight, x + shift + halfWidth,
                        y + halfHeight);
                canvas.drawRoundRect(mRect, dp(2f), dp(2f), mStroke);
            }
        }
        canvas.restore();

        mStroke.setStrokeWidth(dp(1f));
        mStroke.setColor(WHITE);
        mStroke.setAlpha(0x40);
        canvas.drawRoundRect(mMap, corner, corner, mStroke);
        canvas.restore();
    }

    private float mapX(float yaw, float startYaw) {
        return mMap.left + (SphereMath.wrap(yaw - startYaw) / 360f + 0.5f) * mMap.width();
    }

    private float mapY(float pitch, float top, float bottom) {
        return mMap.top + (top - pitch) / (top - bottom) * mMap.height();
    }

    /** Where a direction lands on this view, through the lens and the preview's crop. */
    private boolean toView(@NonNull CameraPose pose, @NonNull Matrix frameToView, float yaw,
            float pitch, @NonNull float[] out) {
        SphereMath.direction(yaw, pitch, mDirection);
        if (!pose.project(mDirection, out)) {
            return false;
        }
        frameToView.mapPoints(out);
        return true;
    }

    private boolean isOnScreen(@NonNull float[] point, float inset) {
        return point[0] >= inset && point[0] <= getWidth() - inset
                && point[1] >= inset && point[1] <= getHeight() - inset;
    }

    /** A white stroke over a wider dark one, so it reads against any scene. */
    private void strokeWithShadow(@NonNull Canvas canvas, @NonNull Path path, float width,
            @ColorInt int colour, int alpha) {
        mStroke.setStrokeWidth(width + dp(2f));
        mStroke.setColor(SHADOW);
        canvas.drawPath(path, mStroke);
        mStroke.setStrokeWidth(width);
        mStroke.setColor(colour);
        mStroke.setAlpha(alpha);
        canvas.drawPath(path, mStroke);
    }

    /** How far the display is turned from the device's natural orientation, in degrees. */
    private int displayRotation() {
        Display display = getDisplay();
        if (display == null) {
            return 0;
        }
        switch (display.getRotation()) {
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            default:
                return 0;
        }
    }

    private float dp(float value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
