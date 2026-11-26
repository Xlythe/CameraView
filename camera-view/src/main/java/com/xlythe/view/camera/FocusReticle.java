package com.xlythe.view.camera;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;

/**
 * The reticle {@link CameraView} draws where a focus request lands.
 *
 * <p>Driven by what the camera reports, not by the touch: it appears when the camera takes a
 * request and turns to the locked colour only if the camera says it converged.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public class FocusReticle extends View {
    /** How long the reticle takes to close on the point, in milliseconds. */
    private static final long CONVERGE_MILLIS = 160;

    /** How long a locked reticle stays up, so the confirmation is readable. */
    private static final long HOLD_MILLIS = 450;

    /** How long the reticle takes to fade out once settled. */
    private static final long FADE_MILLIS = 220;

    /** How much larger the reticle starts than it ends. */
    private static final float ENTRY_SCALE = 1.35f;

    private static final int STROKE_DP = 2;

    /** The length of the ticks at each side, as a fraction of the reticle. */
    private static final float TICK_FRACTION = 0.14f;

    private static final float CORNER_FRACTION = 0.18f;

    /** Bounds on the reticle, so a camera's metering area does not decide how big it looks. */
    private static final int MIN_SIZE_DP = 64;

    private static final int MAX_SIZE_DP = 112;

    /** White, while the camera is still working on the request. */
    static final int DEFAULT_FOCUSING_COLOR = 0xFFFFFFFF;

    /** Amber, once the camera reports it converged. */
    static final int DEFAULT_LOCKED_COLOR = 0xFFFFD54F;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Reused for every frame, so drawing allocates nothing. */
    private final RectF mReticle = new RectF();

    @ColorInt private int mFocusingColor = DEFAULT_FOCUSING_COLOR;

    @ColorInt private int mLockedColor = DEFAULT_LOCKED_COLOR;

    /** The colour being drawn now: one of the two above. */
    @ColorInt private int mColor = DEFAULT_FOCUSING_COLOR;

    private float mCenterX;

    private float mCenterY;

    /** The reticle's side at rest, in pixels, already clamped to the bounds above. */
    private float mSize;

    /** 0 while closing on the point, 1 once settled on it. */
    private float mConvergence;

    private float mAlpha;

    /** The running animation, cancelled when a new one starts. */
    @Nullable private ValueAnimator mAnimator;

    public FocusReticle(@NonNull Context context) {
        super(context);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(dp(STROKE_DP));
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        // Drawn over the preview, and never in the way of a tap meant for it.
        setClickable(false);
        setFocusable(false);
        setWillNotDraw(false);
    }

    /** The colours for a request in progress and for one the camera locked onto. */
    public void setColors(@ColorInt int focusing, @ColorInt int locked) {
        mFocusingColor = focusing;
        mLockedColor = locked;
        invalidate();
    }

    /**
     * Shows the reticle closing on an area the camera has started focusing on.
     *
     * @param area where the camera is focusing, in this view's coordinates
     */
    @UiThread
    public void showAt(@NonNull RectF area) {
        mCenterX = area.centerX();
        mCenterY = area.centerY();
        mSize = Math.max(dp(MIN_SIZE_DP),
                Math.min(Math.min(area.width(), area.height()), dp(MAX_SIZE_DP)));
        mColor = mFocusingColor;
        // Restarted rather than continued: a new request is aimed somewhere else.
        animate(converging(), CONVERGE_MILLIS, 0, null);
    }

    /**
     * Takes the reticle away, confirming the point first if the camera locked onto it.
     *
     * @param locked what the camera reported, not what the touch hoped for
     */
    @UiThread
    public void settle(boolean locked) {
        if (mAlpha <= 0f && mConvergence <= 0f) {
            return;
        }
        mColor = locked ? mLockedColor : mFocusingColor;
        // Snapped to rest, because a camera can answer before the entry finishes.
        mConvergence = 1f;
        mAlpha = 1f;
        animate(fadingOut(), FADE_MILLIS, locked ? HOLD_MILLIS : 0, () -> mConvergence = 0f);
    }

    /**
     * Puts the reticle in a state without animating to it, for a screenshot test.
     *
     * @param convergence 0 for the start of the entry, 1 for at rest
     */
    @VisibleForTesting
    void showStatic(@NonNull RectF area, boolean locked, float convergence) {
        mCenterX = area.centerX();
        mCenterY = area.centerY();
        mSize = Math.max(dp(MIN_SIZE_DP),
                Math.min(Math.min(area.width(), area.height()), dp(MAX_SIZE_DP)));
        mColor = locked ? mLockedColor : mFocusingColor;
        mConvergence = convergence;
        mAlpha = 1f;
        invalidate();
    }

    private ValueAnimator converging() {
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.addUpdateListener(value -> {
            mConvergence = (float) value.getAnimatedValue();
            mAlpha = mConvergence;
            invalidate();
        });
        return animator;
    }

    private ValueAnimator fadingOut() {
        ValueAnimator animator = ValueAnimator.ofFloat(mAlpha, 0f);
        animator.addUpdateListener(value -> {
            mAlpha = (float) value.getAnimatedValue();
            invalidate();
        });
        return animator;
    }

    private void animate(@NonNull ValueAnimator animator, long duration, long delay,
            @Nullable Runnable onEnd) {
        if (mAnimator != null) {
            mAnimator.cancel();
        }
        animator.setDuration(duration);
        animator.setStartDelay(delay);
        if (onEnd != null) {
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator ignored) {
                    onEnd.run();
                    invalidate();
                }
            });
        }
        mAnimator = animator;
        animator.start();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (mAlpha <= 0f || mSize <= 0f) {
            return;
        }

        // Enters larger than it ends and closes on the point; a square that merely appeared
        // would read as a label rather than an act.
        float scale = ENTRY_SCALE + (1f - ENTRY_SCALE) * mConvergence;
        float half = mSize * scale / 2f;
        mReticle.set(mCenterX - half, mCenterY - half, mCenterX + half, mCenterY + half);

        mPaint.setColor(mColor);
        mPaint.setAlpha((int) (255 * Math.min(1f, Math.max(0f, mAlpha))));
        float corner = mSize * CORNER_FRACTION;
        canvas.drawRoundRect(mReticle, corner, corner, mPaint);

        float tick = mSize * TICK_FRACTION;
        canvas.drawLine(mReticle.left, mCenterY, mReticle.left + tick, mCenterY, mPaint);
        canvas.drawLine(mReticle.right - tick, mCenterY, mReticle.right, mCenterY, mPaint);
        canvas.drawLine(mCenterX, mReticle.top, mCenterX, mReticle.top + tick, mPaint);
        canvas.drawLine(mCenterX, mReticle.bottom - tick, mCenterX, mReticle.bottom, mPaint);
    }

    private float dp(float value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
