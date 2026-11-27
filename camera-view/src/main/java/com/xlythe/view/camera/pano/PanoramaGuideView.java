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
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;

import com.xlythe.view.camera.TransformMath;

/**
 * The guide {@link com.xlythe.view.camera.CameraView} draws over its preview while a panorama or
 * photosphere is swept.
 *
 * <p>It has no words in it, so it needs no translating: everything is shape, colour and motion.
 * Everything that marks a direction is pinned to the world rather than to the screen: it is
 * projected through the same rotation and lens as the preview, so a target stays on the wall it
 * marks as the phone turns, and reaching it is a matter of putting the reticle on it.</p>
 *
 * <ul>
 *   <li>A reticle in the middle, ringed by the progress so far. Until the first frame is taken
 *       its ring spins, which is the cue to hold still; it shows a tick once everything is
 *       covered.</li>
 *   <li>For a panorama, a strip along the top showing the panorama so far, with a box for what
 *       the camera sees now that moves along it as the phone turns, the strip sliding on when the
 *       box nears its end. A line pinned to the horizon being swept, and chevrons on it from the
 *       reticle pointing the way to turn, both ways until the sweep has a direction. The line and
 *       chevrons turn orange and pulse when the phone tips off the line or turns too fast for a
 *       sharp frame, and the chevrons beckon harder when the phone stops short.</li>
 *   <li>For a photosphere, dots fixed in the world on everything still to take, the next one
 *       larger and pulsing, with chevrons towards it. Holding the reticle on it fills the
 *       reticle, and when it is full the frame is taken with a flash and the next dot lights.
 *       A small map of the sphere along the top shows what is taken and the dots left.</li>
 * </ul>
 */
@RequiresApi(19)
@RestrictTo(RestrictTo.Scope.LIBRARY)
public class PanoramaGuideView extends View {
    /** The colour everything is drawn in by default. */
    @ColorInt static final int WHITE = 0xFFFFFFFF;

    /** Progress, the target, and being on it; the focus reticle's locked colour. */
    @ColorInt static final int ACCENT = 0xFFFFD54F;

    /** Turning too fast, or off the line. */
    @ColorInt static final int WARNING = 0xFFFF8A65;

    /** Off the line by more than this, in degrees, a panorama's line and chevrons turn orange. */
    static final float LEVEL_TOLERANCE_DEGREES = 4f;

    /** Slower than this, in degrees a second, a panorama under way has stopped short. */
    static final float STALLED_DEGREES_PER_SECOND = 2f;

    /** How far round a panorama's strip shows at once, in degrees. */
    static final float STRIP_SPAN_DEGREES = 180f;

    /** How far above and below its line a panorama's strip shows, in degrees. */
    static final float STRIP_HALF_HEIGHT_DEGREES = 18f;

    /** How long the flash of a photosphere frame being taken lasts. */
    private static final long FLASH_MILLIS = 450;

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

    /**
     * Where the middle of a panorama's strip is, in degrees from where the sweep started, or NaN
     * before it is first drawn. It moves only when the view's box nears an end of the strip.
     */
    private float mStripCentre = Float.NaN;

    /** When the newest photosphere frame was taken, for its flash; -1 for none yet. */
    private long mFlashStart = -1;

    /** The thumbnail version the flash was started for. */
    private int mFlashVersion = -1;

    // Scratch, so drawing allocates nothing.

    /** For strokes. */
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** For fills. */
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** For the map's picture, scaled smoothly. */
    private final Paint mBitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    /** The device's rotation, as drawn. */
    private final float[] mRotation = new float[9];

    /** Where the camera looks as drawn, set again for every frame drawn. */
    private final CameraPose mPose = new CameraPose(new float[9], 0, false, 1, 1, 1f);

    /** How the frames map onto this view, or null until worked out. */
    @Nullable private Matrix mFrameToView;

    /** What {@link #mFrameToView} was worked out for, so it is redone only when that changes. */
    private final int[] mFrameToViewKey = new int[6];

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
        mStripCentre = Float.NaN;
        mFlashStart = -1;
        mFlashVersion = -1;
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
            pose = mPose;
            pose.set(mRotation, state.sensorRotation, state.frontFacing,
                    state.frameWidth, state.frameHeight, state.focal);
            frameToView = frameToView(state);
        }
        boolean sweeping = state.status == GuideState.SWEEPING
                || state.status == GuideState.TOO_FAST;
        // The map first, so a target above the middle of the view is drawn over it rather than
        // hidden under it.
        if (state.photosphere) {
            drawSphereMap(canvas, state, pose);
        } else {
            drawStrip(canvas, state, pose, now);
        }
        boolean aligned = false;
        if (pose != null && sweeping) {
            if (state.photosphere) {
                aligned = drawTargets(canvas, pose, frameToView, state, now);
            } else {
                drawLevel(canvas, pose, frameToView, state, now);
            }
        }
        drawReticle(canvas, state, aligned, now);
        if (state.photosphere) {
            drawFlash(canvas, state, now);
        }
    }

    /**
     * How the frames map onto this view, which is how the preview maps them: turned upright for
     * the display, mirrored for a front lens, and scaled to cover. Worked out again only when
     * one of those changes.
     */
    @NonNull
    private Matrix frameToView(@NonNull GuideState state) {
        int rotation = TransformMath.within360(state.sensorRotation - displayRotation());
        int[] key = mFrameToViewKey;
        // Not short-circuited, so every entry of the key is brought up to date.
        boolean changed = mFrameToView == null
                | update(key, 0, state.frameWidth) | update(key, 1, state.frameHeight)
                | update(key, 2, rotation) | update(key, 3, state.frontFacing ? 1 : 0)
                | update(key, 4, getWidth()) | update(key, 5, getHeight());
        if (changed) {
            mFrameToView = TransformMath.centerCrop(
                    new RectF(0f, 0f, state.frameWidth, state.frameHeight), rotation,
                    state.frontFacing, getWidth(), getHeight());
        }
        return mFrameToView;
    }

    /** Sets one entry of a key, saying whether that changed it. */
    private static boolean update(@NonNull int[] key, int index, int value) {
        boolean changed = key[index] != value;
        key[index] = value;
        return changed;
    }

    /**
     * A panorama's line, pinned to the horizon being swept, with chevrons on it from the reticle
     * the way to turn. Both orange and pulsing when the phone tips off the line or turns too fast;
     * the chevrons beckon harder when it has stopped short.
     */
    private void drawLevel(@NonNull Canvas canvas, @NonNull CameraPose pose,
            @NonNull Matrix frameToView, @NonNull GuideState state, long now) {
        float phase = (now % CYCLE_MILLIS) / (float) CYCLE_MILLIS;
        float pulse = 0.5f + 0.5f * (float) Math.sin(2 * Math.PI * phase);
        boolean offLevel =
                Math.abs(pose.pitch() - state.bandPitch) > LEVEL_TOLERANCE_DEGREES;
        boolean tooFast = state.status == GuideState.TOO_FAST;
        int lineColour = offLevel ? WARNING : WHITE;
        int lineAlpha = offLevel ? (int) (0x90 + 0x6F * pulse) : 0xB0;

        mPath.reset();
        boolean drawing = false;
        float centre = pose.yaw();
        float reach = Math.max(getWidth(), getHeight());
        for (float offset = -LINE_REACH_DEGREES; offset <= LINE_REACH_DEGREES;
                offset += LINE_STEP_DEGREES) {
            boolean visible = toView(pose, frameToView, centre + offset, state.bandPitch, mPoint)
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
        strokeWithShadow(canvas, mPath, dp(offLevel ? 2.5f : 1.5f), lineColour, lineAlpha);

        // Which way along the line to turn: towards the side still to sweep, or both ways until
        // the sweep has gone one way.
        float leading = state.sweptRight + state.sweptLeft;
        boolean right = leading > 0f;
        boolean both = Math.abs(leading) < 4f;
        boolean stalled = !both && state.turnSpeed < STALLED_DEGREES_PER_SECOND;
        int colour = tooFast || offLevel ? WARNING : stalled ? ACCENT : WHITE;
        float centreX = getWidth() / 2f;
        float centreY = getHeight() / 2f;
        for (int side = -1; side <= 1; side += 2) {
            if (!both && (side > 0) != right) {
                continue;
            }
            // The line's direction on screen, from the reticle towards that side.
            if (!toView(pose, frameToView, centre + side * 20f, state.bandPitch, mPoint)
                    || !toView(pose, frameToView, centre + side * 19f, state.bandPitch,
                    mPrevious)) {
                continue;
            }
            float dx = mPoint[0] - mPrevious[0];
            float dy = mPoint[1] - mPrevious[1];
            float length = (float) Math.hypot(dx, dy);
            if (length <= 0f) {
                continue;
            }
            drawChevrons(canvas, centreX, centreY, dx / length, dy / length, phase, colour,
                    stalled || tooFast ? 1.35f : 1f);
        }
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
            drawChevrons(canvas, centreX, centreY, dx, dy, phase, WHITE, 1f);
        }
        return aligned;
    }

    /** Chevrons from the reticle along (dx, dy), brightening outwards in turn. */
    private void drawChevrons(@NonNull Canvas canvas, float centreX, float centreY, float dx,
            float dy, float phase, @ColorInt int colour, float scale) {
        float start = dp(RETICLE_DP + RING_GAP_DP) + dp(12f);
        float half = dp(6f) * scale;
        for (int i = 0; i < CHEVRONS; i++) {
            float along = start + i * dp(CHEVRON_SPACING_DP) * scale;
            float x = centreX + dx * along;
            float y = centreY + dy * along;
            mPath.reset();
            mPath.moveTo(x - dx * half - dy * half, y - dy * half + dx * half);
            mPath.lineTo(x, y);
            mPath.lineTo(x - dx * half + dy * half, y - dy * half - dx * half);
            boolean lit = i == (int) (phase * CHEVRONS);
            strokeWithShadow(canvas, mPath, dp(2.5f) * scale, colour, lit ? 0xFF : 0x90);
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

        if (finished) {
            mFill.setColor(ACCENT);
            mFill.setAlpha(0x66);
            canvas.drawCircle(centreX, centreY, radius, mFill);
        } else if (aligned) {
            // Held on a photosphere's target: the reticle fills as the hold goes on.
            mFill.setColor(ACCENT);
            mFill.setAlpha(0x40);
            canvas.drawCircle(centreX, centreY, radius, mFill);
            mFill.setAlpha(0x80);
            mRect.set(centreX - radius, centreY - radius, centreX + radius, centreY + radius);
            canvas.drawArc(mRect, -90f, 360f * state.holdProgress, true, mFill);
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
        if (state.status == GuideState.SAVING || state.status == GuideState.SETTLING) {
            // Spinning: the picture is being written, or nothing can be taken yet and the phone
            // should be held still.
            float spin = (now % CYCLE_MILLIS) * 360f / CYCLE_MILLIS;
            if (state.status == GuideState.SETTLING) {
                mStroke.setColor(WHITE);
            }
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

    /** A ring flying out of the reticle and fading as a photosphere's frame is taken. */
    private void drawFlash(@NonNull Canvas canvas, @NonNull GuideState state, long now) {
        if (state.thumbnail == null) {
            return;
        }
        if (mFlashVersion != state.thumbnailVersion) {
            // A new frame was taken; the first one is the sweep's start rather than a capture
            // anyone needs told about, but it is flashed too, as the cue that holding works.
            mFlashVersion = state.thumbnailVersion;
            mFlashStart = now;
        }
        if (mFlashStart < 0 || now - mFlashStart > FLASH_MILLIS) {
            return;
        }
        float t = (now - mFlashStart) / (float) FLASH_MILLIS;
        float radius = dp(RETICLE_DP) * (1f + 1.5f * t);
        mStroke.setStrokeWidth(dp(4f) * (1f - t) + dp(1f));
        mStroke.setColor(WHITE);
        mStroke.setAlpha((int) (0xFF * (1f - t)));
        canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, radius, mStroke);
    }

    /**
     * A panorama's strip: the panorama so far, close up, with a box for what the camera sees
     * now. The box moves along the strip as the phone turns, and the strip slides on under it
     * when it nears an end.
     */
    private void drawStrip(@NonNull Canvas canvas, @NonNull GuideState state,
            @Nullable CameraPose pose, long now) {
        float margin = dp(MARGIN_DP);
        float width = Math.min(getWidth() - 2 * margin, dp(BAND_MAP_DP));
        if (width <= 0f) {
            return;
        }
        float span = STRIP_SPAN_DEGREES;
        float top = state.bandPitch + STRIP_HALF_HEIGHT_DEGREES;
        float bottom = state.bandPitch - STRIP_HALF_HEIGHT_DEGREES;
        float height = width * (top - bottom) / span;
        float left = (getWidth() - width) / 2f;
        mMap.set(left, margin, left + width, margin + height);
        float corner = dp(6f);

        // Where the camera points, from where the sweep started, and where the strip is centred.
        float looking = pose == null ? 0f : SphereMath.wrap(pose.yaw() - state.startYaw);
        if (Float.isNaN(mStripCentre)) {
            mStripCentre = looking;
        }
        float fromCentre = SphereMath.wrap(looking - mStripCentre);
        float slack = span / 4f;
        if (fromCentre > slack) {
            mStripCentre = SphereMath.wrap(looking - slack);
        } else if (fromCentre < -slack) {
            mStripCentre = SphereMath.wrap(looking + slack);
        }

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
            copyThumbnail(state, thumbnail, thumbnailHeight);
            // The thumbnail's middle column is the start; the strip's middle is its centre. Drawn
            // up to three times, a turn apart, so a strip across the thumbnail's edge is whole.
            float pixelsPerDegree = state.thumbnailWidth / 360f;
            int rowTop = Math.round((90f - top) / 180f * thumbnailHeight);
            int rowBottom = Math.round((90f - bottom) / 180f * thumbnailHeight);
            float stripLeft = mStripCentre - span / 2f;
            for (int turn = -1; turn <= 1; turn++) {
                float from = stripLeft + turn * 360f;
                int columnLeft = Math.round((from + 180f) * pixelsPerDegree);
                int columnRight = columnLeft + Math.round(span * pixelsPerDegree);
                int clippedLeft = Math.max(0, columnLeft);
                int clippedRight = Math.min(state.thumbnailWidth, columnRight);
                if (clippedLeft >= clippedRight) {
                    continue;
                }
                mThumbnailBounds.set(clippedLeft, rowTop, clippedRight, rowBottom);
                float x0 = mMap.left + (clippedLeft - columnLeft) / (float) (columnRight
                        - columnLeft) * mMap.width();
                float x1 = mMap.left + (clippedRight - columnLeft) / (float) (columnRight
                        - columnLeft) * mMap.width();
                mRect.set(x0, mMap.top, x1, mMap.bottom);
                canvas.drawBitmap(mThumbnail, mThumbnailBounds, mRect, mBitmapPaint);
            }
        }

        // What the camera sees now, orange when it is turning too fast to take.
        if (pose != null && state.status != GuideState.SAVING
                && state.status != GuideState.COMPLETE) {
            float halfWidth = pose.uprightHorizontalFov() / span * mMap.width() / 2f;
            // Never taller than the strip, which shows less than a frame does, so it stays a box.
            float halfHeight = Math.min(pose.uprightVerticalFov() / (top - bottom) * mMap.height(),
                    mMap.height() - dp(4f)) / 2f;
            float x = mMap.centerX() + SphereMath.wrap(looking - mStripCentre) / span
                    * mMap.width();
            float y = Math.max(mMap.top + halfHeight + dp(2f), Math.min(mMap.bottom - halfHeight
                    - dp(2f), mMap.top + (top - pose.pitch()) / (top - bottom) * mMap.height()));
            boolean warn = state.status == GuideState.TOO_FAST;
            mStroke.setStrokeWidth(dp(warn ? 2.5f : 1.5f));
            mStroke.setColor(warn ? WARNING : WHITE);
            mRect.set(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight);
            canvas.drawRoundRect(mRect, dp(2f), dp(2f), mStroke);
        }
        canvas.restore();

        boolean finished = state.status == GuideState.COMPLETE
                || state.status == GuideState.SAVING;
        mStroke.setStrokeWidth(dp(finished ? 2f : 1f));
        mStroke.setColor(finished ? ACCENT : WHITE);
        mStroke.setAlpha(finished ? 0xFF : 0x40);
        canvas.drawRoundRect(mMap, corner, corner, mStroke);
        canvas.restore();
    }

    /** Copies the sweep's thumbnail into the guide's bitmap when it has changed. */
    private void copyThumbnail(@NonNull GuideState state, @NonNull int[] thumbnail,
            int thumbnailHeight) {
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
    }

    /** A photosphere's map: the sphere so far, along the top, with the view marked on it. */
    private void drawSphereMap(@NonNull Canvas canvas, @NonNull GuideState state,
            @Nullable CameraPose pose) {
        float margin = dp(MARGIN_DP);
        float available = getWidth() - 2 * margin;
        if (available <= 0f) {
            return;
        }
        float width = Math.min(available, dp(SPHERE_MAP_DP));
        float top = 90f;
        float bottom = -90f;
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
            copyThumbnail(state, thumbnail, thumbnailHeight);
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
