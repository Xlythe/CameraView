package com.xlythe.view.camera.edit;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.MotionPredictor;
import android.view.View;

import androidx.annotation.FloatRange;
import androidx.annotation.IntDef;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;

import org.json.JSONException;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A surface to draw on with a pen, a finger or a mouse, over anything or over nothing.
 *
 * <p>Ink is kept as strokes in a {@link Drawing} of a size the app chooses, such as the pixels
 * of the picture beneath, drawn to fit the view. A brush's width is how wide it draws on screen,
 * in density-independent pixels; each stroke keeps it in the drawing's units, so it stays the
 * same part of the picture at any size.</p>
 *
 * <h2>What it takes care of</h2>
 * <ul>
 *   <li><b>Pens:</b> pressure, tilt, every sample the digitiser reports, and the eraser end,
 *   which erases. A barrel button can erase too; see {@link #setStylusButtonAction}.</li>
 *   <li><b>Palms:</b> a palm the system cancels, a pen landing while a finger draws, and a second
 *   finger all take the stroke back, leaving nothing to undo.</li>
 *   <li><b>Latency:</b> pen events are delivered as they arrive rather than once a frame, and
 *   from Android 14 the stroke is drawn ahead to where the pen is predicted to be, then
 *   corrected as the real samples come.</li>
 *   <li><b>Steadiness:</b> tremor is smoothed without the line trailing the pen; see
 *   {@link #setStabilization}.</li>
 *   <li><b>Undo:</b> every stroke is one step, and so is an erase however long it is.</li>
 * </ul>
 *
 * <p>A pen hovering over the surface shows how wide the brush will draw, as a ring tinted
 * with the theme's {@code colorControlNormal}.</p>
 */
public class DrawingView extends View {
    /** The tag this logs under. */
    private static final String TAG = "DrawingView";

    /** What a stroke does. */
    @IntDef({TOOL_DRAW, TOOL_ERASE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Tool {}

    /** Strokes draw with the brush. The default. */
    public static final int TOOL_DRAW = 0;
    /** Strokes erase, as {@link #setEraseMode} says. */
    public static final int TOOL_ERASE = 1;

    /** How an eraser takes ink away. */
    @IntDef({ERASE_TOUCHED, ERASE_WHOLE_STROKE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface EraseMode {}

    /** Only the ink under the eraser goes. The default. */
    public static final int ERASE_TOUCHED = 0;
    /** Every stroke the eraser touches goes, however long. */
    public static final int ERASE_WHOLE_STROKE = 1;

    /** What a pen's barrel button does while held. */
    @IntDef({BUTTON_NONE, BUTTON_ERASE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ButtonAction {}

    /** Nothing. */
    public static final int BUTTON_NONE = 0;
    /** Erases, as the eraser end does. The default. */
    public static final int BUTTON_ERASE = 1;

    /** How wide the eraser is on screen, in density-independent pixels. */
    private static final float ERASER_WIDTH_DP = 24f;

    /** How far ahead of the last real sample the stroke is predicted, in nanoseconds. */
    private static final long PREDICTION_NANOS = 16_000_000L;

    /** The most a drawing's JSON may be to be kept across a configuration change. */
    private static final int MAX_SAVED_JSON = 256 * 1024;

    /** The stabilization a pen starts with. */
    private static final float DEFAULT_STABILIZATION = 0.35f;

    /** How much removed tremor is given back as pressure. */
    private static final float EXPRESSION = 0.5f;

    /** Turns events into strokes. */
    private final StylusInput mInput = new StylusInput(new InputListener());
    /** Undo and redo, over whole drawings. */
    private final History<Drawing> mHistory =
            new History<>(new Drawing(0, 0), History.DEFAULT_CAPACITY);
    /** The brush strokes draw with. */
    @NonNull private Brush mBrush = Brush.PEN;
    /** What a stroke does. */
    @Tool private int mTool = TOOL_DRAW;
    /** How the eraser works. */
    @EraseMode private int mEraseMode = ERASE_TOUCHED;
    /** What a barrel button does. */
    @ButtonAction private int mButtonAction = BUTTON_ERASE;
    /** Whether fingers draw. */
    private boolean mFingerDrawing = true;
    /** How much tremor is smoothed, 0 to 1. */
    private float mStabilization = DEFAULT_STABILIZATION;
    /** Told when the drawing changes. */
    @Nullable private OnDrawingChangedListener mListener;
    /** Told when what the pen can report changes, for a brush panel. */
    @Nullable private Runnable mCapabilitiesListener;
    /** Told when the brush is changed by anyone, for a brush panel. */
    @Nullable private Runnable mBrushListener;

    /** Maps the drawing's units to the view's pixels. */
    private final Matrix mDrawingToView = new Matrix();
    /** Maps the view's pixels to the drawing's units. */
    private final Matrix mViewToDrawing = new Matrix();
    /** The committed ink, drawn at the view's size, or null until there is a size. */
    @Nullable private Bitmap mInk;
    /** Whether the committed ink must be drawn again. */
    private boolean mInkStale = true;

    /** The stroke being drawn, in the drawing's units, or null. */
    @Nullable private Stroke.Builder mWet;
    /** Smooths the stroke being drawn. */
    @Nullable private MotionFilter mFilter;
    /** The predicted samples after the stroke being drawn, in the drawing's units. */
    private final List<float[]> mPredicted = new ArrayList<>();
    /** The stroke being drawn, whole, as last built for drawing. */
    @Nullable private Stroke mWetStroke;
    /** Whether the stroke being drawn erases whole strokes rather than ink. */
    private boolean mErasingWhole;
    /** The predictor, from Android 14, or null. */
    @Nullable private Predictor mPredictor;

    /** Where a hovering pen is, in the view's pixels, or NaN. */
    private float mHoverX = Float.NaN;
    private float mHoverY = Float.NaN;
    /** Draws the hover ring. */
    private final Paint mHoverPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Draws ink. */
    private final Paint mInkPaint = new Paint();
    /** Draws the committed ink's bitmap. */
    private final Paint mBitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    public DrawingView(@NonNull Context context) {
        this(context, null);
    }

    public DrawingView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mHoverPaint.setStyle(Paint.Style.STROKE);
        mHoverPaint.setStrokeWidth(dp(1.5f));
        mHoverPaint.setColor(Build.VERSION.SDK_INT >= 21 ? themeColor(context,
                android.R.attr.colorControlNormal, Color.GRAY) : Color.GRAY);
        setSaveEnabled(true);
    }

    // ---------- The drawing ----------

    /**
     * Shows a drawing to carry on with, such as one read back with {@link Drawing#fromJson},
     * with nothing to undo. Its size is the area drawn to fit the view; a drawing of no size
     * takes the view's own pixels as its units.
     */
    @MainThread
    public void setDrawing(@NonNull Drawing drawing) {
        cancelStroke();
        mHistory.reset(drawing);
        layoutDrawing();
        inkChanged();
    }

    /** The drawing as it stands, which an app can save with {@link Drawing#toJson}. */
    @MainThread
    @NonNull
    public Drawing getDrawing() {
        return mHistory.current();
    }

    /**
     * Starts an empty drawing of a size, in units of the app's own, such as the pixels of the
     * picture shown beneath, with nothing to undo.
     */
    @MainThread
    public void setDrawingSize(float width, float height) {
        setDrawing(new Drawing(width, height));
    }

    /** Takes away every stroke, as one step that can be undone. */
    @MainThread
    public void clear() {
        cancelStroke();
        Drawing drawing = mHistory.current();
        if (!drawing.isEmpty()) {
            commit(drawing.withStrokes(new ArrayList<Stroke>()));
        }
    }

    /** Takes back the last stroke or erase. */
    @MainThread
    public void undo() {
        cancelStroke();
        if (mHistory.canUndo()) {
            mHistory.undo();
            changed();
        }
    }

    /** Puts back the last stroke or erase taken back. */
    @MainThread
    public void redo() {
        cancelStroke();
        if (mHistory.canRedo()) {
            mHistory.redo();
            changed();
        }
    }

    /** The steps, for an {@link EditHistory} to keep in order with other views' steps. */
    @MainThread
    @NonNull
    History<Drawing> history() {
        return mHistory;
    }

    @MainThread
    public boolean canUndo() {
        return mHistory.canUndo();
    }

    @MainThread
    public boolean canRedo() {
        return mHistory.canRedo();
    }

    /** Hears every change to the drawing: a stroke, an erase, an undo or redo, a clear. */
    @MainThread
    public void setOnDrawingChangedListener(@Nullable OnDrawingChangedListener listener) {
        mListener = listener;
    }

    // ---------- How it draws ----------

    /**
     * The brush strokes draw with. Its width is how wide it draws on screen, in
     * density-independent pixels.
     */
    @MainThread
    public void setBrush(@NonNull Brush brush) {
        if (brush.equals(mBrush)) {
            return;
        }
        mBrush = brush;
        if (mBrushListener != null) {
            mBrushListener.run();
        }
        invalidate();
    }

    @MainThread
    @NonNull
    public Brush getBrush() {
        return mBrush;
    }

    /** What a stroke does: {@link #TOOL_DRAW} by default. A pen's eraser end always erases. */
    @MainThread
    public void setTool(@Tool int tool) {
        mTool = tool;
    }

    @MainThread
    @Tool
    public int getTool() {
        return mTool;
    }

    /** How the eraser takes ink away: {@link #ERASE_TOUCHED} by default. */
    @MainThread
    public void setEraseMode(@EraseMode int mode) {
        mEraseMode = mode;
    }

    @MainThread
    @EraseMode
    public int getEraseMode() {
        return mEraseMode;
    }

    /** What a pen's barrel button does while held: {@link #BUTTON_ERASE} by default. */
    @MainThread
    public void setStylusButtonAction(@ButtonAction int action) {
        mButtonAction = action;
    }

    @MainThread
    @ButtonAction
    public int getStylusButtonAction() {
        return mButtonAction;
    }

    /**
     * Whether fingers draw, as they do by default. When they do not, a pen or mouse draws and
     * touches from fingers pass through to the views beneath.
     */
    @MainThread
    public void setFingerDrawingEnabled(boolean enabled) {
        mFingerDrawing = enabled;
    }

    @MainThread
    public boolean isFingerDrawingEnabled() {
        return mFingerDrawing;
    }

    /**
     * How much hand tremor is smoothed, from 0, none, to 1, the most, which steadies a shaking
     * hand at the cost of the line trailing a slow one. What is smoothed away is given back as
     * pressure, where the pen has it, so the line keeps its life. 0.35 by default.
     */
    @MainThread
    public void setStabilization(@FloatRange(from = 0, to = 1) float stabilization) {
        mStabilization = Brush.clamp(stabilization, 0f, 1f);
    }

    @MainThread
    public float getStabilization() {
        return mStabilization;
    }

    // ---------- What the brush panel reads ----------

    /** What the pen has shown it reports, for a brush panel to show its controls by. */
    @NonNull
    StylusInput stylus() {
        return mInput;
    }

    void setOnCapabilitiesChangedListener(@Nullable Runnable listener) {
        mCapabilitiesListener = listener;
    }

    void setOnBrushChangedListener(@Nullable Runnable listener) {
        mBrushListener = listener;
    }

    // ---------- Layout and drawing ----------

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (mInk != null) {
            mInk.recycle();
            mInk = null;
        }
        if (width > 0 && height > 0) {
            mInk = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }
        layoutDrawing();
        inkChanged();
    }

    /** Fits the drawing in the view, centred, keeping its proportions. */
    private void layoutDrawing() {
        Drawing drawing = mHistory.current();
        mDrawingToView.reset();
        float width = getWidth();
        float height = getHeight();
        if (drawing.getWidth() > 0 && drawing.getHeight() > 0 && width > 0 && height > 0) {
            float scale = Math.min(width / drawing.getWidth(), height / drawing.getHeight());
            mDrawingToView.setScale(scale, scale);
            mDrawingToView.postTranslate((width - drawing.getWidth() * scale) / 2f,
                    (height - drawing.getHeight() * scale) / 2f);
        }
        mDrawingToView.invert(mViewToDrawing);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        Bitmap ink = mInk;
        if (ink != null && mInkStale) {
            ink.eraseColor(Color.TRANSPARENT);
            Canvas inkCanvas = new Canvas(ink);
            inkCanvas.concat(mDrawingToView);
            mHistory.current().draw(inkCanvas);
            mInkStale = false;
        }
        Stroke wet = mWetStroke;
        if (ink != null) {
            if (wet != null && wet.getBrush().isErasing()) {
                // The eraser clears only ink: the committed ink and the wet eraser share a layer.
                int saved = InkRenderer.saveLayer(canvas, null, null);
                canvas.drawBitmap(ink, 0, 0, mBitmapPaint);
                canvas.concat(mDrawingToView);
                InkRenderer.drawStroke(canvas, wet, mInkPaint);
                canvas.restoreToCount(saved);
                wet = null;
            } else {
                canvas.drawBitmap(ink, 0, 0, mBitmapPaint);
            }
        }
        if (wet != null) {
            int saved = canvas.save();
            canvas.concat(mDrawingToView);
            InkRenderer.drawStroke(canvas, wet, mInkPaint);
            canvas.restoreToCount(saved);
        }
        if (!Float.isNaN(mHoverX) && mWet == null) {
            float radius = Math.max(dp(2), widthOnScreen(currentBrush(false, false)) / 2f);
            canvas.drawCircle(mHoverX, mHoverY, radius, mHoverPaint);
        }
    }

    /** How wide a brush draws on screen, in the view's pixels. */
    private float widthOnScreen(@NonNull Brush brush) {
        return dp(brush.isErasing() ? ERASER_WIDTH_DP : brush.getWidth());
    }

    // ---------- Input ----------

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && Build.VERSION.SDK_INT >= 21
                && StylusInput.toolOf(event, 0) != StylusInput.TOOL_FINGER) {
            // Every sample as it arrives, rather than one batch a frame.
            requestUnbufferedDispatch(event);
        }
        boolean handled = mInput.onTouchEvent(event, mFingerDrawing);
        if (handled && mWet != null && Build.VERSION.SDK_INT >= 34) {
            predict(event);
        }
        return handled;
    }

    @Override
    public boolean onHoverEvent(@NonNull MotionEvent event) {
        return mInput.onHoverEvent(event) || super.onHoverEvent(event);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (Build.VERSION.SDK_INT >= 34) {
            mPredictor = new Predictor(getContext());
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelStroke();
        mPredictor = null;
        super.onDetachedFromWindow();
    }

    /** Draws the stroke ahead to where the pen is predicted to be, from Android 14. */
    @RequiresApi(34)
    private void predict(@NonNull MotionEvent event) {
        Predictor predictor = mPredictor;
        if (predictor == null) {
            return;
        }
        boolean available = predictor.isAvailable(event);
        mInput.reportPrediction(available);
        if (!available) {
            return;
        }
        mPredicted.clear();
        MotionEvent predicted = predictor.predict(event);
        if (predicted != null) {
            float[] point = new float[2];
            for (int h = 0; h < predicted.getHistorySize(); h++) {
                point[0] = predicted.getHistoricalX(0, h);
                point[1] = predicted.getHistoricalY(0, h);
                addPredicted(point, predicted.getHistoricalPressure(0, h));
            }
            point[0] = predicted.getX(0);
            point[1] = predicted.getY(0);
            addPredicted(point, predicted.getPressure(0));
            predicted.recycle();
        }
        rebuildWet();
    }

    private void addPredicted(@NonNull float[] point, float pressure) {
        mViewToDrawing.mapPoints(point);
        mPredicted.add(new float[] {point[0], point[1], pressure});
    }

    /** The brush a stroke draws with, given what the pen is doing. */
    @NonNull
    private Brush currentBrush(boolean eraserEnd, boolean button) {
        boolean erasing = mTool == TOOL_ERASE || eraserEnd
                || (button && mButtonAction == BUTTON_ERASE);
        return erasing ? Brush.ERASER : mBrush;
    }

    /** The brush in the drawing's units, so it draws as wide on screen as it says. */
    @NonNull
    private Brush inDrawingUnits(@NonNull Brush brush) {
        float scale = mDrawingToView.mapRadius(1f);
        float width = widthOnScreen(brush) / (scale > 0 ? scale : 1f);
        return brush.withWidth(Math.max(0.01f, width));
    }

    /** Hears the input. */
    private final class InputListener implements StylusInput.Listener {
        @Override
        public void onStrokeStart(@NonNull StylusInput.Sample sample) {
            boolean eraserEnd = sample.tool == StylusInput.TOOL_ERASER;
            boolean button = sample.primaryButton || sample.secondaryButton;
            Brush brush = inDrawingUnits(currentBrush(eraserEnd, button));
            mErasingWhole = brush.isErasing() && mEraseMode == ERASE_WHOLE_STROKE;
            mWet = new Stroke.Builder(brush, UUID.randomUUID().toString());
            mFilter = new MotionFilter(sample.tool == StylusInput.TOOL_FINGER
                    ? Math.min(mStabilization, DEFAULT_STABILIZATION) : mStabilization,
                    EXPRESSION);
            mHoverX = Float.NaN;
            mPredicted.clear();
            add(sample);
            if (mErasingWhole) {
                eraseWhole(true);
            }
        }

        @Override
        public void onStrokeSample(@NonNull StylusInput.Sample sample) {
            if (mWet == null) {
                return;
            }
            mPredicted.clear();
            add(sample);
            if (mErasingWhole) {
                eraseWhole(false);
            }
        }

        @Override
        public void onStrokeEnd(@NonNull StylusInput.Sample sample) {
            if (mWet == null) {
                return;
            }
            mPredicted.clear();
            add(sample);
            if (mErasingWhole) {
                eraseWhole(false);
            }
            Stroke.Builder wet = mWet;
            finishWet();
            if (mErasingWhole) {
                // Committed as it went, one step for the whole drag.
                changed();
                return;
            }
            commit(mHistory.current().plus(wet.build()));
        }

        @Override
        public void onStrokeCancel(int reason) {
            if (mErasingWhole && mWet != null && mHistory.canUndo()) {
                // The erase so far was a palm's: take it back with nothing to redo.
                mHistory.rollback();
                inkChanged();
            }
            finishWet();
            invalidate();
        }

        @Override
        public void onHover(@Nullable StylusInput.Sample sample) {
            mHoverX = sample == null ? Float.NaN : sample.x;
            mHoverY = sample == null ? Float.NaN : sample.y;
            invalidate();
        }

        @Override
        public void onCapabilitiesChanged() {
            if (mCapabilitiesListener != null) {
                mCapabilitiesListener.run();
            }
        }

        /** Adds a sample, smoothed, in the drawing's units. */
        private void add(@NonNull StylusInput.Sample sample) {
            Stroke.Builder wet = mWet;
            MotionFilter filter = mFilter;
            if (wet == null || filter == null) {
                return;
            }
            float[] point = {sample.x, sample.y};
            mViewToDrawing.mapPoints(point);
            if (mStabilization == 0f) {
                // No smoothing at all: the samples as the pen gave them.
                wet.add(point[0], point[1], sample.pressure, sample.tiltX, sample.tiltY,
                        sample.time);
                rebuildWet();
                return;
            }
            filter.filter(point[0], point[1], sample.pressure, sample.time);
            wet.add(filter.x(), filter.y(), filter.pressure(), sample.tiltX, sample.tiltY,
                    sample.time);
            rebuildWet();
        }
    }

    /** Builds the stroke being drawn, with its predicted tail, for drawing. */
    private void rebuildWet() {
        Stroke.Builder wet = mWet;
        if (wet == null || mErasingWhole) {
            mWetStroke = null;
            invalidate();
            return;
        }
        Stroke stroke = wet.build();
        if (!mPredicted.isEmpty()) {
            // A forked filter smooths the prediction as the real samples will be smoothed,
            // without moving the real filter's clock.
            MotionFilter fork = mFilter == null ? null : mFilter.fork();
            Stroke.Builder ahead = new Stroke.Builder(stroke.getBrush(), stroke.getId());
            for (int i = 0; i < stroke.size(); i++) {
                ahead.add(stroke.getX(i), stroke.getY(i), stroke.getPressure(i),
                        stroke.getTiltX(i), stroke.getTiltY(i), stroke.getTimeOffsetMillis(i));
            }
            long time = stroke.getTimeOffsetMillis(stroke.size() - 1);
            for (float[] predicted : mPredicted) {
                time += 4;
                float x = predicted[0];
                float y = predicted[1];
                float pressure = Float.isNaN(stroke.getPressure(stroke.size() - 1)) ? Float.NaN
                        : mInput.scaledPressure(predicted[2]);
                if (fork != null) {
                    fork.filter(x, y, pressure, time);
                    x = fork.x();
                    y = fork.y();
                }
                ahead.add(x, y, pressure, Float.NaN, Float.NaN, time);
            }
            stroke = ahead.build();
        }
        mWetStroke = stroke;
        invalidate();
    }

    /** Takes away every stroke the eraser has touched so far, as one step for the drag. */
    private void eraseWhole(boolean first) {
        Stroke.Builder wet = mWet;
        if (wet == null) {
            return;
        }
        Stroke eraser = wet.build();
        Drawing drawing = mHistory.current();
        List<Stroke> kept = new ArrayList<>(drawing.getStrokes().size());
        for (Stroke stroke : drawing.getStrokes()) {
            if (!Eraser.touches(eraser, stroke)) {
                kept.add(stroke);
            }
        }
        if (kept.size() == drawing.getStrokes().size()) {
            if (first) {
                // Commit an unchanged step now, so the drag amends one step from here on.
                mHistory.commit(drawing.withStrokes(kept));
            }
            return;
        }
        if (first) {
            mHistory.commit(drawing.withStrokes(kept));
        } else {
            mHistory.amend(drawing.withStrokes(kept));
        }
        inkChanged();
    }

    private void finishWet() {
        mWet = null;
        mWetStroke = null;
        mFilter = null;
        mPredicted.clear();
    }

    /** Takes back the stroke being drawn, if there is one. */
    private void cancelStroke() {
        mInput.cancel(StylusInput.CANCEL_SYSTEM);
    }

    private void commit(@NonNull Drawing drawing) {
        mHistory.commit(drawing);
        changed();
    }

    private void changed() {
        inkChanged();
        if (mListener != null) {
            mListener.onDrawingChanged(mHistory.current());
        }
    }

    private void inkChanged() {
        mInkStale = true;
        invalidate();
    }

    // ---------- State ----------

    @Nullable
    @Override
    protected Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();
        String json = mHistory.current().toJson();
        if (json.length() > MAX_SAVED_JSON) {
            Log.w(TAG, "The drawing is too large to keep across a configuration change; "
                    + "save it with Drawing.toJson()");
            return superState;
        }
        return new DrawingState(superState, json);
    }

    @Override
    protected void onRestoreInstanceState(@Nullable Parcelable state) {
        if (!(state instanceof DrawingState)) {
            super.onRestoreInstanceState(state);
            return;
        }
        DrawingState saved = (DrawingState) state;
        super.onRestoreInstanceState(saved.getSuperState());
        try {
            setDrawing(Drawing.fromJson(saved.mJson));
        } catch (JSONException e) {
            Log.w(TAG, "Unable to restore the drawing", e);
        }
    }

    /** A drawing kept across a configuration change, as JSON. */
    static final class DrawingState extends BaseSavedState {
        /** The drawing. */
        @NonNull final String mJson;

        DrawingState(@Nullable Parcelable superState, @NonNull String json) {
            super(superState);
            mJson = json;
        }

        DrawingState(@NonNull Parcel source) {
            super(source);
            String json = source.readString();
            mJson = json == null ? "{}" : json;
        }

        @Override
        public void writeToParcel(@NonNull Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeString(mJson);
        }

        public static final Creator<DrawingState> CREATOR = new Creator<DrawingState>() {
            @Override
            public DrawingState createFromParcel(Parcel source) {
                return new DrawingState(source);
            }

            @Override
            public DrawingState[] newArray(int size) {
                return new DrawingState[size];
            }
        };
    }

    /**
     * The platform's motion predictor, kept in a class of its own so nothing older than
     * Android 14 loads it.
     */
    @RequiresApi(34)
    private static final class Predictor {
        /** The platform's predictor. */
        private final MotionPredictor mPredictor;

        Predictor(@NonNull Context context) {
            mPredictor = new MotionPredictor(context);
        }

        boolean isAvailable(@NonNull MotionEvent event) {
            return mPredictor.isPredictionAvailable(event.getDeviceId(), event.getSource());
        }

        @Nullable
        MotionEvent predict(@NonNull MotionEvent event) {
            mPredictor.record(event);
            return mPredictor.predict(event.getEventTimeNanos() + PREDICTION_NANOS);
        }
    }

    // ---------- Helpers ----------

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    /** A colour from the theme, or a fallback where the theme does not say. */
    static int themeColor(@NonNull Context context, int attribute, int fallback) {
        TypedArray array = context.obtainStyledAttributes(new int[] {attribute});
        try {
            return array.getColor(0, fallback);
        } finally {
            array.recycle();
        }
    }

    /** Hears every change to a drawing. */
    public interface OnDrawingChangedListener {
        /** Called on the main thread with the drawing as it now stands. */
        void onDrawingChanged(@NonNull Drawing drawing);
    }
}
