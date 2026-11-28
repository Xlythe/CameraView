package com.xlythe.view.camera.edit;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.os.Build;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * Draws strokes onto a {@link Canvas}, the same way for the screen and for a saved picture, so
 * what is drawn is what is saved.
 *
 * <p>A stroke whose width follows pressure is a chain of round-capped segments, each as wide as
 * its own sample makes it, as Muse draws them. A translucent stroke is drawn opaque into a layer
 * of its own and laid down at its opacity, so where its segments overlap at a joint the ink does
 * not darken. A stamped brush lays dabs a fraction of its width apart, each bitten into by its
 * grain, building up to the brush's opacity and no further.</p>
 *
 * <p>Strokes are drawn into a layer of their own, so an eraser clears ink and never what the ink
 * is drawn over.</p>
 */
final class InkRenderer {
    /** The gap between dabs, as a fraction of the width, for a textured brush with no spacing. */
    private static final float DEFAULT_DAB_SPACING = 0.25f;

    /** The smallest gap between dabs, in the drawing's units, however thin the brush. */
    private static final float MIN_DAB_STEP = 0.5f;

    /** The least a dab's texture may cover for the dab to be drawn at all. */
    private static final float MIN_COVERAGE = 0.02f;

    private InkRenderer() {}

    /** Draws strokes in order, inside a layer so erasers clear only ink. */
    @AnyThread
    static void draw(@NonNull Canvas canvas, @NonNull List<Stroke> strokes,
            @Nullable RectF bounds) {
        if (strokes.isEmpty()) {
            return;
        }
        int saved = saveLayer(canvas, bounds, null);
        Paint paint = new Paint();
        for (Stroke stroke : strokes) {
            drawStroke(canvas, stroke, paint);
        }
        canvas.restoreToCount(saved);
    }

    /**
     * Draws one stroke straight onto a canvas, which must already be a layer if the stroke is
     * an eraser.
     */
    @AnyThread
    static void drawStroke(@NonNull Canvas canvas, @NonNull Stroke stroke, @NonNull Paint paint) {
        Brush brush = stroke.getBrush();
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        if (brush.isErasing()) {
            paint.setColor(Color.BLACK);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            drawSegments(canvas, stroke, paint, false);
            return;
        }
        float alpha = Color.alpha(brush.getColor()) / 255f * brush.getOpacity();
        int opaque = brush.getColor() | 0xFF000000;
        boolean stamped = brush.isStamped();
        // A brush that pales as it leans varies its alpha along the stroke, so it too is laid
        // in a layer of its own, its segments replacing rather than covering one another.
        boolean fades = brush.getTiltUprightAlpha() != brush.getTiltFlatAlpha();
        boolean layered = alpha < 1f || stamped || fades;
        int saved = layered ? saveLayerAlpha(canvas, stroke.inkedBounds(),
                Math.round(alpha * 255)) : -1;
        paint.setColor(opaque);
        if (layered && !stamped) {
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
        }
        if (stamped) {
            drawStamped(canvas, stroke, paint);
        } else {
            drawSegments(canvas, stroke, paint, true);
        }
        if (saved >= 0) {
            canvas.restoreToCount(saved);
        }
    }

    /** Draws a stroke as round-capped segments, each as wide as its sample makes it. */
    private static void drawSegments(@NonNull Canvas canvas, @NonNull Stroke stroke,
            @NonNull Paint paint, boolean tiltFades) {
        Brush brush = stroke.getBrush();
        int color = paint.getColor();
        if (stroke.size() == 1) {
            paint.setStyle(Paint.Style.FILL);
            float lean = brush.leanOf(stroke.getTiltX(0), stroke.getTiltY(0));
            canvas.drawCircle(stroke.getX(0), stroke.getY(0),
                    brush.widthFor(stroke.getPressure(0), lean, Float.NaN) / 2f, paint);
            return;
        }
        paint.setStyle(Paint.Style.STROKE);
        float restingAlpha = brush.alphaFor(Float.NaN);
        for (int i = 1; i < stroke.size(); i++) {
            float lean = brush.leanOf(stroke.getTiltX(i), stroke.getTiltY(i));
            paint.setStrokeWidth(brush.widthFor(stroke.getPressure(i), lean, stroke.speedAt(i)));
            if (tiltFades && !Float.isNaN(lean) && restingAlpha > 0f) {
                // A leaned pencil goes pale, relative to the stroke's own opacity.
                float fade = Brush.clamp(brush.alphaFor(lean) / restingAlpha, 0f, 1f);
                paint.setColor(withAlpha(color, Math.round(Color.alpha(color) * fade)));
            }
            canvas.drawLine(stroke.getX(i - 1), stroke.getY(i - 1), stroke.getX(i),
                    stroke.getY(i), paint);
        }
        paint.setColor(color);
    }

    /**
     * Lays a stroke down as dabs, each as wide as its sample makes it and as dense as the brush's
     * flow, its lean and its grain say.
     */
    private static void drawStamped(@NonNull Canvas canvas, @NonNull Stroke stroke,
            @NonNull Paint paint) {
        Brush brush = stroke.getBrush();
        int color = paint.getColor();
        paint.setStyle(Paint.Style.FILL);
        float spacing = brush.getSpacing() > 0f ? brush.getSpacing() : DEFAULT_DAB_SPACING;
        if (stroke.size() == 1) {
            float width = brush.widthFor(stroke.getPressure(0),
                    brush.leanOf(stroke.getTiltX(0), stroke.getTiltY(0)), Float.NaN);
            dab(canvas, paint, color, brush, stroke.getX(0), stroke.getY(0), width,
                    brush.getFlow(), 0f);
            return;
        }
        float total = Math.max(1e-3f, stroke.length());
        float walked = 0f;
        float carried = 0f;
        float resting = brush.alphaFor(Float.NaN);
        for (int i = 1; i < stroke.size(); i++) {
            float x0 = stroke.getX(i - 1);
            float y0 = stroke.getY(i - 1);
            float dx = stroke.getX(i) - x0;
            float dy = stroke.getY(i) - y0;
            float segment = (float) Math.sqrt(dx * dx + dy * dy);
            if (segment <= 0f) {
                continue;
            }
            float lean = brush.leanOf(stroke.getTiltX(i), stroke.getTiltY(i));
            float width = brush.widthFor(stroke.getPressure(i), lean, stroke.speedAt(i));
            float step = Math.max(MIN_DAB_STEP, spacing * width);
            float density = brush.getFlow()
                    * (resting > 0f ? Brush.clamp(brush.alphaFor(lean) / resting, 0f, 1f) : 1f);
            float at = carried;
            while (at <= segment) {
                float fraction = at / segment;
                dab(canvas, paint, color, brush, x0 + dx * fraction, y0 + dy * fraction, width,
                        density, (walked + at) / total);
                at += step;
            }
            carried = at - segment;
            walked += segment;
        }
        paint.setColor(color);
    }

    private static void dab(@NonNull Canvas canvas, @NonNull Paint paint, int color,
            @NonNull Brush brush, float x, float y, float width, float density, float along) {
        float coverage = brush.coverageAt(x, y, along);
        if (coverage <= MIN_COVERAGE) {
            return;
        }
        paint.setColor(withAlpha(color, Math.round(255 * Brush.clamp(density * coverage, 0f, 1f))));
        canvas.drawCircle(x, y, width / 2f, paint);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    /** Starts an offscreen layer, on every release this library supports. */
    @SuppressWarnings("deprecation")
    static int saveLayer(@NonNull Canvas canvas, @Nullable RectF bounds, @Nullable Paint paint) {
        if (Build.VERSION.SDK_INT >= 21) {
            return canvas.saveLayer(bounds, paint);
        }
        return canvas.saveLayer(bounds, paint, Canvas.ALL_SAVE_FLAG);
    }

    /** Starts an offscreen layer laid down at an alpha, on every release this library supports. */
    @SuppressWarnings("deprecation")
    static int saveLayerAlpha(@NonNull Canvas canvas, @Nullable RectF bounds, int alpha) {
        if (Build.VERSION.SDK_INT >= 21) {
            return canvas.saveLayerAlpha(bounds, alpha);
        }
        return canvas.saveLayerAlpha(bounds, alpha, Canvas.ALL_SAVE_FLAG);
    }
}
