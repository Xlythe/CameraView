package com.xlythe.view.camera.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.Arrays;
import java.util.Collections;

/** Drawings, written and read in Muse's ink format, and drawn. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 37)
public class DrawingTest {
    /** A stroke with pressure that a pen had, tilt it did not, and real timing. */
    static Stroke stroke(Brush brush, String id, float... xy) {
        Stroke.Builder builder = new Stroke.Builder(brush, id);
        for (int i = 0; i < xy.length; i += 2) {
            builder.add(xy[i], xy[i + 1], i == 0 ? 0.25f : 0.75f, Float.NaN, Float.NaN, i * 4L);
        }
        return builder.build();
    }

    @Test
    public void aDrawingRoundTripsThroughJsonWithEveryAxis() throws JSONException {
        Brush pencil = Brush.PENCIL.withColor(0xFF112233);
        Stroke.Builder tilted = new Stroke.Builder(Brush.CHARCOAL, "tilted");
        tilted.add(1, 2, 0.5f, 30f, -12.5f, 1000);
        tilted.add(3, 4, 0.6f, 31f, -12f, 1008);
        Drawing drawing = new Drawing(640, 480).withStrokes(Arrays.asList(
                stroke(pencil, "a", 10, 10, 20, 30, 40, 35), tilted.build()));

        Drawing read = Drawing.fromJson(drawing.toJson());

        assertEquals(640f, read.getWidth(), 0f);
        assertEquals(480f, read.getHeight(), 0f);
        assertEquals(2, read.getStrokes().size());
        Stroke a = read.getStrokes().get(0);
        assertEquals("a", a.getId());
        assertEquals(pencil, a.getBrush());
        assertEquals(3, a.size());
        assertEquals(20f, a.getX(1), 0f);
        assertEquals(30f, a.getY(1), 0f);
        assertEquals(0.75f, a.getPressure(1), 0f);
        assertTrue("an axis the pen did not have stays unknown", Float.isNaN(a.getTiltX(0)));
        assertEquals(16, a.getTimeOffsetMillis(2));
        Stroke b = read.getStrokes().get(1);
        assertEquals(Brush.CHARCOAL, b.getBrush());
        assertEquals(-12.5f, b.getTiltY(0), 0f);
        assertEquals(8, b.getTimeOffsetMillis(1));
    }

    @Test
    public void unknownAxesAreWrittenAsNullAsMuseWritesThem() throws JSONException {
        Drawing drawing = new Drawing(10, 10).withStrokes(Collections.singletonList(
                stroke(Brush.PEN, "s", 1, 1, 2, 2)));
        JSONObject json = new JSONObject(drawing.toJson());
        assertEquals(1, json.getInt("version"));
        JSONObject layer = json.getJSONArray("layers").getJSONObject(0);
        assertEquals("fixed", layer.getJSONObject("anchor").getString("kind"));
        JSONObject stroke = layer.getJSONArray("strokes").getJSONObject(0);
        assertTrue(stroke.getJSONArray("tiltX").isNull(0));
        assertEquals(0.25, stroke.getJSONArray("pressure").getDouble(0), 0.0);
        JSONObject brush = stroke.getJSONObject("brush");
        assertEquals("PEN", brush.getString("type"));
        assertEquals(0.25, brush.getDouble("curveMin"), 1e-6);
        assertEquals("NONE", brush.getString("grainKind"));
        assertEquals("s", json.getJSONArray("strokeOrder").getString(0));
    }

    @Test
    public void aMuseDocumentOfLayersReadsAsOneDrawing() throws JSONException {
        // As Muse writes one: two layers, one hidden, with fields this does not use.
        String muse = "{\"version\":1,\"width\":800.0,\"height\":600.0,\"layers\":["
                + "{\"id\":\"l1\",\"anchor\":{\"kind\":\"fixed\",\"pageId\":\"p\",\"pageWidth\":800.0,"
                + "\"pageHeight\":600.0},\"strokes\":[{\"id\":\"s1\",\"brush\":{\"type\":\"MARKER\","
                + "\"width\":18.0,\"color\":-8062,\"opacity\":0.4,\"curveMin\":1.0,\"curveMax\":1.0},"
                + "\"x\":[1.0,2.0],\"y\":[3.0,4.0],\"pressure\":[null,null],\"time\":[0,8]}],"
                + "\"name\":\"Ink\",\"visible\":true,\"opacity\":0.5,\"author\":\"ana\","
                + "\"blend\":\"MULTIPLY\",\"locked\":true},"
                + "{\"id\":\"l2\",\"anchor\":{\"kind\":\"fixed\"},\"visible\":false,\"strokes\":["
                + "{\"id\":\"hidden\",\"brush\":{\"type\":\"PEN\",\"width\":3.0,\"color\":-14671840},"
                + "\"x\":[5.0],\"y\":[6.0]}]}],\"groups\":[],\"strokeOrder\":[\"s1\",\"hidden\"],"
                + "\"futureField\":{\"a\":1}}";
        Drawing drawing = Drawing.fromJson(muse);
        assertEquals(1, drawing.getStrokes().size());
        Stroke stroke = drawing.getStrokes().get(0);
        assertEquals(Brush.TYPE_MARKER, stroke.getBrush().getType());
        assertEquals(0xFFFFE082, stroke.getBrush().getColor());
        // The layer's opacity carries into the brush's.
        assertEquals(0.2f, stroke.getBrush().getOpacity(), 1e-6f);
        assertTrue(Float.isNaN(stroke.getPressure(0)));
        assertTrue("an axis written short is unknown", Float.isNaN(stroke.getTiltX(1)));
    }

    @Test
    public void aBadOrNewerDocumentFailsToRead() {
        assertThrows(JSONException.class, () -> Drawing.fromJson("not json"));
        assertThrows(JSONException.class, () -> Drawing.fromJson("{\"version\":2}"));
        assertThrows(JSONException.class, () -> Drawing.fromJson("{\"version\":1,\"layers\":[{"
                + "\"strokes\":[{\"id\":\"s\",\"x\":[1,2],\"y\":[1]}]}]}"));
        assertThrows(JSONException.class, () -> Drawing.fromJson("{\"version\":1,\"layers\":[{"
                + "\"strokes\":[{\"id\":\"s\",\"x\":[],\"y\":[]}]}]}"));
    }

    @Test
    public void aBrushOutOfRangeReadsAsTheWayBrushesWereBefore() throws JSONException {
        String json = "{\"version\":1,\"layers\":[{\"strokes\":[{\"id\":\"s\",\"x\":[1],"
                + "\"y\":[1],\"brush\":{\"type\":\"SPRAY\",\"width\":-3,\"opacity\":7,"
                + "\"curveMin\":-1,\"curveExponent\":0,\"tiltFlatDegrees\":0,"
                + "\"grainKind\":\"CHALK\",\"grainScale\":0}}]}]}";
        Brush brush = Drawing.fromJson(json).getStrokes().get(0).getBrush();
        assertEquals(Brush.TYPE_PEN, brush.getType());
        assertEquals(1f, brush.getWidth(), 0f);
        assertEquals(1f, brush.getOpacity(), 0f);
        assertEquals(0f, brush.getPressureMinScale(), 0f);
        assertEquals(1f, brush.getPressureExponent(), 0f);
        assertEquals(60f, brush.getTiltFlatDegrees(), 0f);
        assertEquals(Brush.GRAIN_CHALK, brush.getGrain());
        assertEquals(3f, brush.getGrainScale(), 0f);
    }

    @Test
    public void aDrawingIsImmutableAndComparesByWhatItHolds() {
        Drawing empty = new Drawing(10, 20);
        assertTrue(empty.isEmpty());
        Stroke stroke = stroke(Brush.PEN, "s", 1, 1);
        Drawing drawn = empty.plus(stroke);
        assertTrue(empty.isEmpty());
        assertEquals(1, drawn.getStrokes().size());
        assertThrows(UnsupportedOperationException.class,
                () -> drawn.getStrokes().add(stroke));
        assertEquals(drawn, empty.withStrokes(Collections.singletonList(stroke)));
        assertEquals(drawn.hashCode(),
                empty.withStrokes(Collections.singletonList(stroke)).hashCode());
        assertNotEquals(drawn, empty);
        assertThrows(IllegalArgumentException.class, () -> new Drawing(-1, 1));
    }

    @Test
    public void aDrawingIsDrawnStretchedOverAPicture() {
        // A red line across the top of a 100x50 drawing, then an eraser through its middle.
        Brush red = Brush.PEN.withColor(Color.RED).withWidth(10);
        Drawing drawing = new Drawing(100, 50).withStrokes(Arrays.asList(
                stroke(red, "line", 0, 5, 100, 5),
                stroke(Brush.ERASER.withWidth(10), "erase", 50, 0, 50, 10)));
        Bitmap bitmap = drawing.toBitmap(200, 100);
        assertEquals(200, bitmap.getWidth());
        int left = bitmap.getPixel(20, 10);
        assertEquals(Color.RED, left | 0xFF000000);
        assertEquals(255, Color.alpha(left));
        assertEquals("the eraser cleared the ink", 0, Color.alpha(bitmap.getPixel(100, 10)));
        assertEquals("nothing below the line", 0, Color.alpha(bitmap.getPixel(20, 80)));
    }

    @Test
    public void jsonArraysWriteFloatsAsFloats() throws JSONException {
        Drawing drawing = new Drawing(1, 1).withStrokes(Collections.singletonList(
                stroke(Brush.MARKER, "m", 0.1f, 0.2f)));
        JSONArray xs = new JSONObject(drawing.toJson()).getJSONArray("layers").getJSONObject(0)
                .getJSONArray("strokes").getJSONObject(0).getJSONArray("x");
        assertEquals("0.1", xs.get(0).toString());
    }
}
