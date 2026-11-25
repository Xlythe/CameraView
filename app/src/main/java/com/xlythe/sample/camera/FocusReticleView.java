package com.xlythe.sample.camera;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The focus reticle a camera app draws, as an overlay over a
 * {@link com.xlythe.view.camera.CameraView}.
 *
 * <p>CameraView draws nothing over its own preview, because an app with a camera UI of its own
 * wants its own affordance. This is what one looks like: it is driven by
 * {@link com.xlythe.view.camera.CameraView.FocusListener}, so it appears when the camera took the
 * request and confirms only what the camera said it managed - a reticle that locks on its own is
 * telling the user something nobody checked.</p>
 *
 * <p>Drawn rather than animated with views so that the reticle can sit anywhere in the preview
 * without a layout pass per touch.</p>
 */
public class FocusReticleView extends View {
    /** How long the reticle takes to converge on the point, in milliseconds. */
    private static final long CONVERGE_MILLIS = 160;

    /** How long a settled reticle stays up before it leaves. */
    private static final long HOLD_MILLIS = 450;

    private static final long FADE_MILLIS = 220;

    /** How much larger the reticle starts than it ends: the whole of the converging movement. */
    private static final float ENTRY_SCALE = 1.35f;

    private static final int STROKE_DP = 2;

    /** Kept well inside the reticle's own corners, so the ticks read as marks rather than sides. */
    private static final float TICK_FRACTION = 0.14f;

    private static final float CORNER_FRACTION = 0.18f;

    /** Bounds on the reticle, so a camera's metering area does not decide how big it looks. */
    private static final int MIN_SIZE_DP = 64;

    private static final int MAX_SIZE_DP = 112;

    private static final int COLOR_FOCUSING = 0xFFFFFFFF;

    private static final int COLOR_LOCKED = 0xFFFFD54F;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF mReticle = new RectF();

    private float mCenterX;
    private float mCenterY;
    private float mSize;

    /** 0 while converging, 1 once settled on the point. */
    private float mConvergence;

    private float mAlpha;

    private int mColor = COLOR_FOCUSING;

    @Nullable
    private ValueAnimator mAnimator;

    public FocusReticleView(Context context) {
        this(context, null);
    }

    public FocusReticleView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FocusReticleView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(dp(STROKE_DP));
        mPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    /**
     * Shows the reticle converging on an area the camera has started focusing on.
     *
     * @param area where the camera is focusing, in this view's coordinates
     */
    public void showAt(@NonNull RectF area) {
        mCenterX = area.centerX();
        mCenterY = area.centerY();
        mSize = Math.max(dp(MIN_SIZE_DP),
                Math.min(Math.min(area.width(), area.height()), dp(MAX_SIZE_DP)));
        mColor = COLOR_FOCUSING;
        // Restarted from the beginning rather than continued: a new request is aimed somewhere
        // else, so the movement has to happen again at the new point.
        animate(converging(), CONVERGE_MILLIS, 0, null);
    }

    /**
     * Takes the reticle away, confirming the point first if the camera actually locked onto it.
     *
     * @param locked what the camera reported, not what the touch hoped for
     */
    public void settle(boolean locked) {
        if (mAlpha <= 0f && mConvergence <= 0f) {
            // Nothing is on screen - a focus that finished before this overlay heard it started.
            return;
        }
        mColor = locked ? COLOR_LOCKED : COLOR_FOCUSING;
        // Snapped to its resting size, because a camera can answer in less time than the entry
        // takes and a reticle left frozen part of the way in reads as something that stalled.
        mConvergence = 1f;
        mAlpha = 1f;
        // A lock is held for a moment before it goes, so the confirmation is readable; a focus
        // that did not lock simply leaves, confirming nothing.
        animate(fadingOut(), FADE_MILLIS, locked ? HOLD_MILLIS : 0, () -> mConvergence = 0f);
    }

    /** Animates the converging movement, which brings the reticle in as it shrinks. */
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

        // The reticle enters larger than it ends and closes on the point, which is the whole of
        // the feedback: a square that merely appeared would read as a label rather than an act.
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
