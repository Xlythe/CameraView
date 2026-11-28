package com.xlythe.view.camera.edit;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A drawing: strokes of ink over an area of a given size, in units of the drawing's own, such as
 * the pixels of the picture it is drawn over. Drawings are immutable.
 *
 * <p>A drawing reads and writes JSON in the format of Muse's ink documents
 * ({@value #MIME_TYPE}, version {@value #FORMAT_VERSION}), so ink moves between apps that use
 * either. A Muse document of several layers reads as one, its visible layers' strokes in order.
 * </p>
 */
public final class Drawing {
    /** The MIME type of a drawing's JSON. */
    public static final String MIME_TYPE = "application/vnd.muse.ink+json";

    /** The version of the format written, and the newest read. */
    public static final int FORMAT_VERSION = 1;

    /** The name of the one layer a drawing is written as. */
    private static final String LAYER_ID = "ink";

    /** The width, in the drawing's units. */
    private final float mWidth;
    /** The height, in the drawing's units. */
    private final float mHeight;
    /** The strokes, in the order they are drawn. */
    @NonNull private final List<Stroke> mStrokes;

    /** An empty drawing of a size, in units of its own, such as a picture's pixels. */
    public Drawing(float width, float height) {
        this(width, height, Collections.<Stroke>emptyList());
    }

    private Drawing(float width, float height, @NonNull List<Stroke> strokes) {
        if (!(width >= 0f) || !(height >= 0f)) {
            throw new IllegalArgumentException("A drawing's size is not negative: " + width
                    + "x" + height);
        }
        mWidth = width;
        mHeight = height;
        mStrokes = strokes;
    }

    /** The width, in the drawing's units. */
    @AnyThread
    public float getWidth() {
        return mWidth;
    }

    /** The height, in the drawing's units. */
    @AnyThread
    public float getHeight() {
        return mHeight;
    }

    /** The strokes, in the order they are drawn, which cannot be changed. */
    @AnyThread
    @NonNull
    public List<Stroke> getStrokes() {
        return mStrokes;
    }

    /** Whether there is no stroke. */
    @AnyThread
    public boolean isEmpty() {
        return mStrokes.isEmpty();
    }

    /** A copy with these strokes in place of this drawing's. */
    @AnyThread
    @NonNull
    public Drawing withStrokes(@NonNull List<Stroke> strokes) {
        return new Drawing(mWidth, mHeight,
                Collections.unmodifiableList(new ArrayList<>(strokes)));
    }

    /** A copy with one more stroke on top. */
    @AnyThread
    @NonNull
    Drawing plus(@NonNull Stroke stroke) {
        List<Stroke> strokes = new ArrayList<>(mStrokes.size() + 1);
        strokes.addAll(mStrokes);
        strokes.add(stroke);
        return new Drawing(mWidth, mHeight, Collections.unmodifiableList(strokes));
    }

    /**
     * Draws the drawing in its own units, so a canvas scaled to fit the drawing's size draws it
     * there. Erasers clear only the drawing's ink, never what the canvas already holds.
     */
    @AnyThread
    public void draw(@NonNull Canvas canvas) {
        InkRenderer.draw(canvas, mStrokes, mWidth > 0 && mHeight > 0
                ? new RectF(0, 0, mWidth, mHeight) : null);
    }

    /**
     * The drawing as a transparent picture of a size, stretched to fill it: such as the size of
     * the picture it was drawn over, to pass to {@link PictureEdits.Builder#setOverlay}.
     */
    @WorkerThread
    @NonNull
    public Bitmap toBitmap(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        if (mWidth > 0 && mHeight > 0) {
            canvas.scale(width / mWidth, height / mHeight);
        }
        draw(canvas);
        return bitmap;
    }

    // ---------- JSON ----------

    /**
     * The drawing as JSON, in the format of Muse's ink documents. Quick enough for a view to
     * keep its state with; a large drawing is better saved off the main thread.
     */
    @AnyThread
    @NonNull
    public String toJson() {
        try {
            JSONObject document = new JSONObject();
            document.put("version", FORMAT_VERSION);
            document.put("width", number(mWidth));
            document.put("height", number(mHeight));
            JSONArray strokes = new JSONArray();
            JSONArray order = new JSONArray();
            for (Stroke stroke : mStrokes) {
                strokes.put(strokeJson(stroke));
                order.put(stroke.getId());
            }
            JSONObject anchor = new JSONObject();
            anchor.put("kind", "fixed");
            anchor.put("pageId", "");
            anchor.put("pageWidth", number(mWidth));
            anchor.put("pageHeight", number(mHeight));
            JSONObject layer = new JSONObject();
            layer.put("id", LAYER_ID);
            layer.put("anchor", anchor);
            layer.put("strokes", strokes);
            layer.put("name", "");
            layer.put("visible", true);
            layer.put("opacity", number(1f));
            document.put("layers", new JSONArray().put(layer));
            document.put("groups", new JSONArray());
            document.put("strokeOrder", order);
            return document.toString();
        } catch (JSONException e) {
            // Every number written is finite, so nothing here can fail.
            throw new IllegalStateException("Unable to write a drawing", e);
        }
    }

    /**
     * Reads a drawing from JSON in the format of Muse's ink documents.
     *
     * @throws JSONException if it is not JSON, is a newer version of the format, or has strokes
     *     whose samples disagree
     */
    @AnyThread
    @NonNull
    public static Drawing fromJson(@NonNull String json) throws JSONException {
        JSONObject document = new JSONObject(json);
        int version = document.optInt("version", FORMAT_VERSION);
        if (version > FORMAT_VERSION) {
            throw new JSONException("Format version " + version + " is newer than "
                    + FORMAT_VERSION);
        }
        float width = (float) document.optDouble("width", 0);
        float height = (float) document.optDouble("height", 0);
        List<Stroke> strokes = new ArrayList<>();
        JSONArray layers = document.optJSONArray("layers");
        for (int i = 0; layers != null && i < layers.length(); i++) {
            JSONObject layer = layers.getJSONObject(i);
            if (!layer.optBoolean("visible", true)) {
                continue;
            }
            float opacity = Brush.clamp((float) layer.optDouble("opacity", 1), 0f, 1f);
            JSONArray layerStrokes = layer.optJSONArray("strokes");
            for (int j = 0; layerStrokes != null && j < layerStrokes.length(); j++) {
                strokes.add(stroke(layerStrokes.getJSONObject(j), opacity));
            }
        }
        return new Drawing(Math.max(0f, finite(width)), Math.max(0f, finite(height)),
                Collections.unmodifiableList(strokes));
    }

    @NonNull
    private static JSONObject strokeJson(@NonNull Stroke stroke) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", stroke.getId());
        json.put("brush", brushJson(stroke.getBrush()));
        json.put("x", array(stroke.xs()));
        json.put("y", array(stroke.ys()));
        json.put("pressure", array(stroke.pressures()));
        json.put("tiltX", array(stroke.tiltXs()));
        json.put("tiltY", array(stroke.tiltYs()));
        JSONArray times = new JSONArray();
        for (int time : stroke.times()) {
            times.put(time);
        }
        json.put("time", times);
        return json;
    }

    @NonNull
    private static JSONObject brushJson(@NonNull Brush brush) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("type", typeName(brush.getType()));
        json.put("width", number(brush.getWidth()));
        json.put("color", brush.getColor());
        json.put("opacity", number(brush.getOpacity()));
        json.put("curveMin", number(brush.getPressureMinScale()));
        json.put("curveMax", number(brush.getPressureMaxScale()));
        json.put("curveExponent", number(brush.getPressureExponent()));
        json.put("tiltUprightScale", number(brush.getTiltUprightScale()));
        json.put("tiltFlatScale", number(brush.getTiltFlatScale()));
        json.put("tiltUprightAlpha", number(brush.getTiltUprightAlpha()));
        json.put("tiltFlatAlpha", number(brush.getTiltFlatAlpha()));
        json.put("tiltFlatDegrees", number(brush.getTiltFlatDegrees()));
        json.put("velocitySlowScale", number(brush.getVelocitySlowScale()));
        json.put("velocityFastScale", number(brush.getVelocityFastScale()));
        json.put("velocityReference", number(brush.getVelocityReference()));
        json.put("velocityExponent", number(brush.getVelocityExponent()));
        json.put("spacing", number(brush.getSpacing()));
        json.put("flow", number(brush.getFlow()));
        json.put("grainKind", grainName(brush.getGrain()));
        json.put("grainDepth", number(brush.getGrainDepth()));
        json.put("grainScale", number(brush.getGrainScale()));
        json.put("grainSeed", brush.getGrainSeed());
        return json;
    }

    /**
     * Reads a stroke. An axis shorter than the stroke, or with nulls, is unknown where it has no
     * value, as Muse writes a device's missing axes.
     */
    @NonNull
    private static Stroke stroke(@NonNull JSONObject json, float layerOpacity)
            throws JSONException {
        JSONArray xs = json.getJSONArray("x");
        JSONArray ys = json.getJSONArray("y");
        int count = xs.length();
        if (count == 0 || ys.length() != count) {
            throw new JSONException("Stroke " + json.optString("id") + " has " + count
                    + " x values and " + ys.length() + " y values");
        }
        float[] x = new float[count];
        float[] y = new float[count];
        for (int i = 0; i < count; i++) {
            x[i] = (float) xs.getDouble(i);
            y[i] = (float) ys.getDouble(i);
        }
        int[] times = new int[count];
        JSONArray time = json.optJSONArray("time");
        for (int i = 0; time != null && i < count && i < time.length(); i++) {
            times[i] = time.optInt(i, 0);
        }
        Brush brush = brush(json.optJSONObject("brush"));
        if (layerOpacity < 1f) {
            brush = brush.withOpacity(brush.getOpacity() * layerOpacity);
        }
        return new Stroke(x, y, axis(json.optJSONArray("pressure"), count),
                axis(json.optJSONArray("tiltX"), count), axis(json.optJSONArray("tiltY"), count),
                times, brush, json.optString("id", ""));
    }

    /**
     * Reads a brush, giving every field missing or out of range the value that means the way
     * brushes behaved before it existed, as Muse does.
     */
    @NonNull
    private static Brush brush(@Nullable JSONObject json) {
        if (json == null) {
            return Brush.PEN;
        }
        float curveMin = Math.max(0f, real(json, "curveMin", 0.25f));
        return new Brush.Builder()
                .setType(typeOf(json.optString("type")))
                .setWidth(positive(real(json, "width", 1f), 1f))
                .setColor(json.optInt("color", 0xFF202020))
                .setOpacity(Brush.clamp(real(json, "opacity", 1f), 0f, 1f))
                .setPressureResponse(curveMin, Math.max(curveMin, real(json, "curveMax", 1f)),
                        positive(real(json, "curveExponent", 1f), 1f))
                .setTiltResponse(Math.max(0.01f, real(json, "tiltUprightScale", 1f)),
                        Math.max(0.01f, real(json, "tiltFlatScale", 1f)),
                        Brush.clamp(real(json, "tiltUprightAlpha", 1f), 0f, 1f),
                        Brush.clamp(real(json, "tiltFlatAlpha", 1f), 0f, 1f),
                        positive(real(json, "tiltFlatDegrees", 60f), 60f))
                .setVelocityResponse(Math.max(0.01f, real(json, "velocitySlowScale", 1f)),
                        Math.max(0.01f, real(json, "velocityFastScale", 1f)),
                        positive(real(json, "velocityReference", 2.5f), 2.5f),
                        positive(real(json, "velocityExponent", 1f), 1f))
                .setSpacing(Math.max(0f, real(json, "spacing", 0f)))
                .setFlow(Brush.clamp(real(json, "flow", 1f), 0f, 1f))
                .setGrain(grainOf(json.optString("grainKind")),
                        Brush.clamp(real(json, "grainDepth", 0f), 0f, 1f),
                        positive(real(json, "grainScale", 3f), 3f),
                        json.optInt("grainSeed", Brush.DEFAULT_SEED))
                .build();
    }

    @NonNull
    private static float[] axis(@Nullable JSONArray values, int count) {
        float[] axis = new float[count];
        for (int i = 0; i < count; i++) {
            double value = values == null || i >= values.length() || values.isNull(i)
                    ? Double.NaN : values.optDouble(i, Double.NaN);
            axis[i] = (float) value;
        }
        return axis;
    }

    /** An axis as JSON, with unknown values as null, since JSON has no NaN. */
    @NonNull
    private static JSONArray array(@NonNull float[] values) {
        JSONArray array = new JSONArray();
        for (float value : values) {
            array.put(Float.isNaN(value) || Float.isInfinite(value) ? JSONObject.NULL
                    : number(value));
        }
        return array;
    }

    /** A float written as the float it is, rather than as the double it widens to. */
    @NonNull
    private static Number number(float value) {
        return Float.valueOf(value);
    }

    private static float real(@NonNull JSONObject json, @NonNull String name, float fallback) {
        double value = json.optDouble(name, fallback);
        return Double.isNaN(value) || Double.isInfinite(value) ? fallback : (float) value;
    }

    private static float positive(float value, float fallback) {
        return value > 0f ? value : fallback;
    }

    private static float finite(float value) {
        return Float.isNaN(value) || Float.isInfinite(value) ? 0f : value;
    }

    @NonNull
    private static String typeName(@Brush.Type int type) {
        switch (type) {
            case Brush.TYPE_MARKER:
                return "MARKER";
            case Brush.TYPE_PENCIL:
                return "PENCIL";
            case Brush.TYPE_ERASER:
                return "ERASER";
            default:
                return "PEN";
        }
    }

    @Brush.Type
    private static int typeOf(@NonNull String name) {
        switch (name) {
            case "MARKER":
                return Brush.TYPE_MARKER;
            case "PENCIL":
                return Brush.TYPE_PENCIL;
            case "ERASER":
                return Brush.TYPE_ERASER;
            default:
                return Brush.TYPE_PEN;
        }
    }

    @NonNull
    private static String grainName(@Brush.Grain int grain) {
        switch (grain) {
            case Brush.GRAIN_SPECKLE:
                return "SPECKLE";
            case Brush.GRAIN_CHALK:
                return "CHALK";
            case Brush.GRAIN_DRY:
                return "DRY";
            default:
                return "NONE";
        }
    }

    @Brush.Grain
    private static int grainOf(@NonNull String name) {
        switch (name) {
            case "SPECKLE":
                return Brush.GRAIN_SPECKLE;
            case "CHALK":
                return Brush.GRAIN_CHALK;
            case "DRY":
                return Brush.GRAIN_DRY;
            default:
                return Brush.GRAIN_NONE;
        }
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof Drawing && mWidth == ((Drawing) other).mWidth
                && mHeight == ((Drawing) other).mHeight
                && mStrokes.equals(((Drawing) other).mStrokes);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * Float.floatToIntBits(mWidth) + Float.floatToIntBits(mHeight))
                + mStrokes.hashCode();
    }

    @NonNull
    @Override
    public String toString() {
        return "Drawing{" + mWidth + "x" + mHeight + ", strokes=" + mStrokes.size() + "}";
    }
}
