package com.xlythe.view.camera.edit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.ColorInt;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A plain, text-free brush picker to put beside a {@link DrawingView}: a row of brushes, each
 * shown as a stroke it draws, a row of colours, a size you set by dragging along a stroke that
 * thickens, and an opacity you set by dragging along a bar that fades. A control for pressure
 * appears once the pen in use is found to report it.
 *
 * <p>It is drawn in the theme's colours: {@code colorControlNormal} for outlines and the size
 * stroke, and {@code colorControlActivated} for what is chosen. Any row can be hidden, and the
 * whole panel replaced by the app's own, which sets the brush on the {@link DrawingView}
 * itself.</p>
 */
public class BrushPanelView extends LinearLayout {
    /** The colours offered unless the app gives its own. */
    private static final int[] DEFAULT_COLORS = {0xFF202020, 0xFFFFFFFF, 0xFFE53935, 0xFFFB8C00,
            0xFFFDD835, 0xFF43A047, 0xFF1E88E5, 0xFF8E24AA};

    /** The narrowest and widest brush the size control sets, in density-independent pixels. */
    @VisibleForTesting
    static final float MIN_WIDTH_DP = 1f;
    @VisibleForTesting
    static final float MAX_WIDTH_DP = 48f;

    /** The faintest opacity the opacity control sets. */
    @VisibleForTesting
    static final float MIN_OPACITY = 0.05f;

    /** How tall each row is, in density-independent pixels. */
    private static final float ROW_DP = 44f;

    /** The brushes offered. */
    private final List<Brush> mBrushes = new ArrayList<>(Arrays.asList(Brush.PEN,
            Brush.INKING_PEN, Brush.MARKER, Brush.PENCIL, Brush.CHARCOAL, Brush.ERASER));
    /** The colours offered. */
    @NonNull private int[] mColors = DEFAULT_COLORS.clone();
    /** Which offered brush is chosen. */
    private int mBrushIndex;
    /** The brush as chosen, with its colour, width and opacity. */
    @NonNull private Brush mBrush = Brush.PEN;
    /** Whether pressure changes the width; off makes the chosen brush ignore it. */
    private boolean mPressureOn = true;
    /** The surface the brush is for, or null. */
    @Nullable private DrawingView mDrawingView;
    /** Told when the brush changes. */
    @Nullable private OnBrushChangedListener mListener;

    /** The rows. */
    private final BrushRow mBrushRow;
    private final SwatchRow mSwatchRow;
    private final SizeSlider mSizeSlider;
    private final OpacitySlider mOpacitySlider;
    private final PressureToggle mPressureToggle;

    /** Theme colours. */
    @ColorInt private final int mNormal;
    @ColorInt private final int mActivated;

    public BrushPanelView(@NonNull Context context) {
        this(context, null);
    }

    public BrushPanelView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        mNormal = Build.VERSION.SDK_INT >= 21 ? DrawingView.themeColor(context,
                android.R.attr.colorControlNormal, Color.GRAY) : Color.GRAY;
        mActivated = Build.VERSION.SDK_INT >= 21 ? DrawingView.themeColor(context,
                android.R.attr.colorControlActivated, 0xFF1E88E5) : 0xFF1E88E5;
        int row = Math.round(dp(ROW_DP));
        mBrushRow = new BrushRow(context);
        mSwatchRow = new SwatchRow(context);
        mSizeSlider = new SizeSlider(context);
        mOpacitySlider = new OpacitySlider(context);
        mPressureToggle = new PressureToggle(context);
        LinearLayout sliders = new LinearLayout(context);
        sliders.setOrientation(HORIZONTAL);
        sliders.addView(mSizeSlider, new LayoutParams(0, row, 1));
        sliders.addView(mPressureToggle, new LayoutParams(row, row));
        addView(mBrushRow, new LayoutParams(LayoutParams.MATCH_PARENT, row));
        addView(mSwatchRow, new LayoutParams(LayoutParams.MATCH_PARENT, row));
        addView(sliders, new LayoutParams(LayoutParams.MATCH_PARENT, row));
        addView(mOpacitySlider, new LayoutParams(LayoutParams.MATCH_PARENT, row));
        mPressureToggle.setVisibility(GONE);
        mBrush = applyColor(mBrushes.get(0), mColors[0]);
    }

    // ---------- What it offers ----------

    /**
     * Sets the surface the chosen brush is for. The panel sets the surface's brush as it
     * changes, follows a brush the app sets on the surface, and shows the pressure control once
     * the surface's pen is found to report pressure.
     */
    @MainThread
    public void setDrawingView(@Nullable DrawingView view) {
        if (mDrawingView != null) {
            mDrawingView.setOnBrushChangedListener(null);
            mDrawingView.setOnCapabilitiesChangedListener(null);
        }
        mDrawingView = view;
        if (view != null) {
            view.setOnBrushChangedListener(() -> showBrush(view.getBrush()));
            view.setOnCapabilitiesChangedListener(this::updateCapabilities);
            view.setBrush(mBrush);
            updateCapabilities();
        }
    }

    /** The brushes offered, in order. The first is chosen. */
    @MainThread
    public void setBrushes(@NonNull List<Brush> brushes) {
        if (brushes.isEmpty()) {
            throw new IllegalArgumentException("Offer at least one brush");
        }
        mBrushes.clear();
        mBrushes.addAll(brushes);
        mBrushIndex = 0;
        choose(applyColor(mBrushes.get(0), mBrush.getColor()));
        mBrushRow.invalidate();
    }

    /** The brushes offered, which cannot be changed. */
    @MainThread
    @NonNull
    public List<Brush> getBrushes() {
        return Collections.unmodifiableList(new ArrayList<>(mBrushes));
    }

    /** The colours offered, as ARGB. */
    @MainThread
    public void setColors(@NonNull @ColorInt int[] colors) {
        if (colors.length == 0) {
            throw new IllegalArgumentException("Offer at least one colour");
        }
        mColors = colors.clone();
        mSwatchRow.invalidate();
    }

    @MainThread
    @NonNull
    @ColorInt
    public int[] getColors() {
        return mColors.clone();
    }

    /** Chooses a brush, as if picked: with its own colour, width and opacity. */
    @MainThread
    public void setBrush(@NonNull Brush brush) {
        choose(brush);
    }

    /** The brush as chosen. */
    @MainThread
    @NonNull
    public Brush getBrush() {
        return mBrush;
    }

    /** Hears the brush change. */
    @MainThread
    public void setOnBrushChangedListener(@Nullable OnBrushChangedListener listener) {
        mListener = listener;
    }

    /** Whether the row of brushes is shown, as it is by default. */
    @MainThread
    public void setBrushControlShown(boolean shown) {
        mBrushRow.setVisibility(shown ? VISIBLE : GONE);
    }

    /** Whether the row of colours is shown, as it is by default. */
    @MainThread
    public void setColorControlShown(boolean shown) {
        mSwatchRow.setVisibility(shown ? VISIBLE : GONE);
    }

    /** Whether the size control is shown, as it is by default. */
    @MainThread
    public void setSizeControlShown(boolean shown) {
        ((View) mSizeSlider.getParent()).setVisibility(shown ? VISIBLE : GONE);
    }

    /** Whether the opacity control is shown, as it is by default. */
    @MainThread
    public void setOpacityControlShown(boolean shown) {
        mOpacitySlider.setVisibility(shown ? VISIBLE : GONE);
    }

    // ---------- Choosing ----------

    /** Makes a brush the chosen one and tells everyone. */
    private void choose(@NonNull Brush brush) {
        showBrush(brush);
        if (mDrawingView != null) {
            mDrawingView.setBrush(mBrush);
        }
        if (mListener != null) {
            mListener.onBrushChanged(mBrush);
        }
    }

    /** Shows a brush as chosen, without telling anyone. */
    private void showBrush(@NonNull Brush brush) {
        mBrush = brush;
        int index = -1;
        for (int i = 0; i < mBrushes.size(); i++) {
            if (mBrushes.get(i).getType() == brush.getType()
                    && mBrushes.get(i).getGrain() == brush.getGrain()
                    && mBrushes.get(i).getSpacing() == brush.getSpacing()) {
                index = i;
                break;
            }
        }
        if (index >= 0) {
            mBrushIndex = index;
        }
        mBrushRow.invalidate();
        mSwatchRow.invalidate();
        mSizeSlider.invalidate();
        mOpacitySlider.invalidate();
        mPressureToggle.invalidate();
    }

    /** A brush in a colour, keeping its own alpha unless the colour is opaque. */
    @NonNull
    private static Brush applyColor(@NonNull Brush brush, @ColorInt int color) {
        if (brush.isErasing()) {
            return brush;
        }
        return brush.withColor((color & 0x00FFFFFF) | (brush.getColor() & 0xFF000000));
    }

    private void updateCapabilities() {
        DrawingView view = mDrawingView;
        boolean pressure = view != null && view.stylus().pressure() == StylusInput.PRESENT;
        mPressureToggle.setVisibility(pressure ? VISIBLE : GONE);
    }

    /** A width in density-independent pixels, from 0 to 1 along the size control. */
    @VisibleForTesting
    static float widthAt(float fraction) {
        // Even steps of ratio, so a fine brush is as easy to set as a broad one.
        return (float) (MIN_WIDTH_DP * Math.pow(MAX_WIDTH_DP / MIN_WIDTH_DP,
                Brush.clamp(fraction, 0f, 1f)));
    }

    /** Where a width falls along the size control, from 0 to 1. */
    @VisibleForTesting
    static float fractionOf(float width) {
        return Brush.clamp((float) (Math.log(Math.max(width, MIN_WIDTH_DP) / MIN_WIDTH_DP)
                / Math.log(MAX_WIDTH_DP / MIN_WIDTH_DP)), 0f, 1f);
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    // ---------- The rows ----------

    /** The brushes, each shown as an S-shaped stroke it draws, pressing harder in the middle. */
    private final class BrushRow extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mInk = new Paint();

        BrushRow(@NonNull Context context) {
            super(context);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            int count = mBrushes.size();
            float cell = getWidth() / (float) count;
            for (int i = 0; i < count; i++) {
                RectF bounds = new RectF(i * cell, 0, (i + 1) * cell, getHeight());
                bounds.inset(dp(4), dp(4));
                if (i == mBrushIndex) {
                    mPaint.setStyle(Paint.Style.FILL);
                    mPaint.setColor((mActivated & 0x00FFFFFF) | 0x40000000);
                    canvas.drawRoundRect(bounds, dp(6), dp(6), mPaint);
                }
                Brush brush = i == mBrushIndex ? mBrush : applyColor(mBrushes.get(i),
                        mBrush.getColor());
                drawSample(canvas, bounds, brush);
            }
        }

        /** Draws a brush's sample stroke inside a cell, with the real renderer. */
        private void drawSample(@NonNull Canvas canvas, @NonNull RectF cell,
                @NonNull Brush brush) {
            RectF area = new RectF(cell);
            area.inset(cell.width() * 0.15f, cell.height() * 0.2f);
            float width = Math.min(area.height() * 0.4f, dp(Math.max(1.5f, brush.getWidth()
                    * 0.6f)));
            Brush shown = brush.withWidth(Math.max(0.5f, width));
            int saved = InkRenderer.saveLayer(canvas, cell, null);
            if (shown.isErasing()) {
                // An eraser is shown as the path it clears through a tint.
                mPaint.setStyle(Paint.Style.FILL);
                mPaint.setColor((mNormal & 0x00FFFFFF) | 0x60000000);
                canvas.drawRoundRect(area, dp(3), dp(3), mPaint);
            }
            InkRenderer.drawStroke(canvas, sampleStroke(area, shown), mInk);
            canvas.restoreToCount(saved);
        }

        @Override
        public boolean onTouchEvent(@NonNull MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                int index = (int) (event.getX() / (getWidth() / (float) mBrushes.size()));
                if (index >= 0 && index < mBrushes.size()) {
                    mBrushIndex = index;
                    choose(applyColor(mBrushes.get(index), mBrush.getColor()));
                }
            }
            return true;
        }
    }

    /** An S-shaped stroke across an area, pressing hardest in its middle. */
    @NonNull
    static Stroke sampleStroke(@NonNull RectF area, @NonNull Brush brush) {
        Stroke.Builder builder = new Stroke.Builder(brush, "sample");
        int samples = 24;
        for (int i = 0; i < samples; i++) {
            float t = i / (float) (samples - 1);
            float x = area.left + area.width() * t;
            float y = area.centerY() - (float) Math.sin(t * Math.PI * 2) * area.height() * 0.3f;
            float pressure = (float) Math.sin(t * Math.PI);
            builder.add(x, y, pressure, Float.NaN, Float.NaN, i * 8L);
        }
        return builder.build();
    }

    /** The colours, as circles, the chosen one ringed. */
    private final class SwatchRow extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        SwatchRow(@NonNull Context context) {
            super(context);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            float cell = getWidth() / (float) mColors.length;
            float radius = Math.min(cell, getHeight()) * 0.32f;
            int chosen = mBrush.getColor() & 0x00FFFFFF;
            for (int i = 0; i < mColors.length; i++) {
                float x = cell * (i + 0.5f);
                float y = getHeight() / 2f;
                mPaint.setStyle(Paint.Style.FILL);
                mPaint.setColor(mColors[i] | 0xFF000000);
                canvas.drawCircle(x, y, radius, mPaint);
                mPaint.setStyle(Paint.Style.STROKE);
                boolean selected = (mColors[i] & 0x00FFFFFF) == chosen && !mBrush.isErasing();
                mPaint.setStrokeWidth(dp(selected ? 3 : 1));
                mPaint.setColor(selected ? mActivated : mNormal);
                canvas.drawCircle(x, y, radius + dp(selected ? 3 : 0.5f), mPaint);
            }
        }

        @Override
        public boolean onTouchEvent(@NonNull MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                int index = (int) (event.getX() / (getWidth() / (float) mColors.length));
                if (index >= 0 && index < mColors.length) {
                    Brush brush = mBrush.isErasing() ? applyColor(firstDrawing(), mColors[index])
                            : applyColor(mBrush, mColors[index]);
                    choose(brush);
                }
            }
            return true;
        }

        /** The first brush that draws, for a colour picked while erasing. */
        @NonNull
        private Brush firstDrawing() {
            for (Brush brush : mBrushes) {
                if (!brush.isErasing()) {
                    return brush;
                }
            }
            return Brush.PEN;
        }
    }

    /** A stroke that thickens from left to right; dragging along it sets the width. */
    private final class SizeSlider extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mWedge = new Path();

        SizeSlider(@NonNull Context context) {
            super(context);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            float left = dp(12);
            float right = getWidth() - dp(12);
            float middle = getHeight() / 2f;
            float thickest = Math.min(getHeight() * 0.35f, dp(14));
            mWedge.reset();
            mWedge.moveTo(left, middle);
            mWedge.lineTo(right, middle - thickest);
            mWedge.lineTo(right, middle + thickest);
            mWedge.close();
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(mNormal);
            canvas.drawPath(mWedge, mPaint);
            float x = left + (right - left) * fractionOf(mBrush.getWidth());
            mPaint.setColor(mBrush.isErasing() ? mNormal : mBrush.getColor() | 0xFF000000);
            canvas.drawCircle(x, middle, Math.max(dp(4), dp(mBrush.getWidth()) / 2f), mPaint);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(dp(2));
            mPaint.setColor(mActivated);
            canvas.drawCircle(x, middle, Math.max(dp(4), dp(mBrush.getWidth()) / 2f) + dp(2),
                    mPaint);
        }

        @Override
        public boolean onTouchEvent(@NonNull MotionEvent event) {
            float left = dp(12);
            float right = getWidth() - dp(12);
            float fraction = (event.getX() - left) / Math.max(1f, right - left);
            getParent().requestDisallowInterceptTouchEvent(true);
            choose(mBrush.withWidth(widthAt(fraction)));
            return true;
        }
    }

    /** A bar that fades from clear to the colour; dragging along it sets the opacity. */
    private final class OpacitySlider extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        OpacitySlider(@NonNull Context context) {
            super(context);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            float left = dp(12);
            float right = getWidth() - dp(12);
            float top = getHeight() * 0.35f;
            float bottom = getHeight() * 0.65f;
            int color = mBrush.isErasing() ? mNormal : mBrush.getColor() | 0xFF000000;
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setShader(new LinearGradient(left, 0, right, 0, color & 0x00FFFFFF, color,
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(new RectF(left, top, right, bottom), dp(4), dp(4), mPaint);
            mPaint.setShader(null);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(dp(1));
            mPaint.setColor(mNormal);
            canvas.drawRoundRect(new RectF(left, top, right, bottom), dp(4), dp(4), mPaint);
            float x = left + (right - left) * Brush.clamp((mBrush.getOpacity() - MIN_OPACITY)
                    / (1f - MIN_OPACITY), 0f, 1f);
            mPaint.setStrokeWidth(dp(3));
            mPaint.setColor(mActivated);
            canvas.drawLine(x, top - dp(4), x, bottom + dp(4), mPaint);
        }

        @Override
        public boolean onTouchEvent(@NonNull MotionEvent event) {
            if (mBrush.isErasing()) {
                return true;
            }
            float left = dp(12);
            float right = getWidth() - dp(12);
            float fraction = Brush.clamp((event.getX() - left) / Math.max(1f, right - left),
                    0f, 1f);
            getParent().requestDisallowInterceptTouchEvent(true);
            choose(mBrush.withOpacity(MIN_OPACITY + (1f - MIN_OPACITY) * fraction));
            return true;
        }
    }

    /** Whether pressure changes the width: a tapered stroke when on, an even one when off. */
    private final class PressureToggle extends View {
        private final Paint mInk = new Paint();
        /** The pressure response the brush had before it was turned off. */
        private float[] mSaved;

        PressureToggle(@NonNull Context context) {
            super(context);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            RectF area = new RectF(0, 0, getWidth(), getHeight());
            area.inset(getWidth() * 0.2f, getHeight() * 0.3f);
            Brush shown = new Brush.Builder()
                    .setColor(mPressureOn ? mActivated : mNormal)
                    .setWidth(dp(5))
                    .setPressureResponse(mPressureOn ? 0.1f : 1f, 1f, 1f)
                    .build();
            InkRenderer.drawStroke(canvas, sampleStroke(area, shown), mInk);
        }

        @Override
        public boolean onTouchEvent(@NonNull MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                mPressureOn = !mPressureOn;
                if (mPressureOn) {
                    float[] saved = mSaved == null ? new float[] {0.25f, 1f, 1f} : mSaved;
                    choose(new Brush.Builder(mBrush)
                            .setPressureResponse(saved[0], saved[1], saved[2]).build());
                } else {
                    mSaved = new float[] {mBrush.getPressureMinScale(),
                            mBrush.getPressureMaxScale(), mBrush.getPressureExponent()};
                    float max = mBrush.getPressureMaxScale();
                    choose(new Brush.Builder(mBrush).setPressureResponse(max, max, 1f).build());
                }
                invalidate();
            }
            return true;
        }
    }

    /** Hears the brush change. */
    public interface OnBrushChangedListener {
        /** Called on the main thread with the brush as now chosen. */
        void onBrushChanged(@NonNull Brush brush);
    }
}
