package com.xlythe.view.camera.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Gainmap;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.MediaTags;
import com.xlythe.view.camera.edit.PictureEditor;
import com.xlythe.view.camera.edit.PictureEdits;
import com.xlythe.view.camera.gl.ColorMatrix;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The picture editor on a device, with the device's own decoders and encoders: a camera picture
 * edited end to end, every format the device writes, an Ultra HDR picture's gain map, a DNG and a
 * motion photo.
 *
 * <p>Every source is lopsided, its quarters red, green, blue and yellow reading across from the
 * top left, so a turn or a mirror in the wrong place fails. What each test makes is also written
 * as a PNG under the test's external files, in editor-proof, for a person to look at.</p>
 */
@RunWith(AndroidJUnit4.class)
public final class PictureEditorDeviceTest {
    private static final String TAG = "PictureEditorDevice";

    /** Long enough for a camera to open, or an encoder to finish, on a slow emulator. */
    private static final long TIMEOUT_SECONDS = 60;

    /** The Exif tags a camera writes that the edit must carry. */
    private static final String[] CAMERA_TAGS = {"Make", "Model", "DateTimeOriginal"};

    @Rule
    public final GrantPermissionRule permissions =
            GrantPermissionRule.grant(Manifest.permission.CAMERA);

    @Rule
    public final TestRule cameraHal = DeviceState.skipWhenTheEmulatorCameraHalStalls();

    /** Where what each test makes is written for a person to look at. */
    private static File sProof;

    @BeforeClass
    public static void makeTheProofFolder() {
        Context context = ApplicationProvider.getApplicationContext();
        File root = context.getExternalFilesDir(null);
        sProof = new File(root == null ? context.getFilesDir() : root, "editor-proof");
        assertTrue(sProof.isDirectory() || sProof.mkdirs());
    }

    /** A picture from the camera, turned, cropped, filtered and marked, keeps its metadata. */
    @Test
    public void aCameraPictureIsEditedEndToEnd() throws Exception {
        DeviceState.assumeATestableCamera();
        File picture = temporaryFile(".jpg");
        takePicture(picture);
        MediaTags original = MediaTags.open(picture);
        Bitmap seenBefore = seen(picture);
        Log.i(TAG, "Camera picture " + seenBefore.getWidth() + "x" + seenBefore.getHeight()
                + " " + original);

        Bitmap mark = Bitmap.createBitmap(seenBefore.getWidth() / 4,
                seenBefore.getHeight() / 4, Bitmap.Config.ARGB_8888);
        // A magenta block over the top left eighth of the picture as seen.
        new Canvas(mark).drawRect(0, 0, mark.getWidth() / 2f, mark.getHeight() / 2f,
                paint(Color.MAGENTA));
        File edited = temporaryFile(".jpg");
        edit(picture, edited, new PictureEdits.Builder()
                .setRotationDegrees(90)
                .setCrop(new RectF(0f, 0f, 1f, 0.5f))
                .setColorMatrix(ColorMatrix.grayscale())
                .setOverlay(mark)
                .build(), PictureEditor.FORMAT_SAME);

        MediaTags tags = MediaTags.open(edited);
        assertEquals(0, tags.getRotation());
        for (String tag : CAMERA_TAGS) {
            assertEquals(tag, original.getExifTag(exifNumber(tag)), tags.getExifTag(
                    exifNumber(tag)));
        }
        assertEquals(original.getMake(), tags.getMake());
        assertEquals(original.getCaptureTime(), tags.getCaptureTime());
        Bitmap after = seen(edited);
        // Turned clockwise, the picture is as tall as it was wide; the crop keeps its top half.
        assertEquals(seenBefore.getHeight(), after.getWidth(), 1);
        assertEquals(seenBefore.getWidth() / 2, after.getHeight(), 1);
        // The mark was at the top left; turned clockwise, it is at the top right.
        assertColor(Color.MAGENTA, after.getPixel(after.getWidth() - 4, 4));
        int grey = after.getPixel(after.getWidth() / 4, after.getHeight() / 2);
        assertEquals(Color.red(grey), Color.blue(grey), 6);
        proof("camera-before", seenBefore);
        proof("camera-after", after);
    }

    /** Every format the device writes holds the picture the right way round, at its size. */
    @Test
    public void everyFormatTheDeviceWritesHoldsThePicture() throws Exception {
        File source = jpeg(quarters(1200, 700));
        MediaTags located = MediaTags.open(source);
        located.setMake("Xlythe");
        located.setModel("Editor test");
        located.save();
        int[] formats = {PictureEditor.FORMAT_JPEG, PictureEditor.FORMAT_PNG,
                PictureEditor.FORMAT_WEBP, PictureEditor.FORMAT_HEIF, PictureEditor.FORMAT_AVIF};
        StringBuilder written = new StringBuilder();
        for (int format : formats) {
            boolean supported = PictureEditor.isOutputFormatSupported(format);
            Log.i(TAG, "Format " + format + " supported: " + supported + " on API "
                    + Build.VERSION.SDK_INT);
            if (!supported) {
                continue;
            }
            for (int[] size : new int[][] {{1200, 700}, {301, 201}}) {
                File input = size[0] == 1200 ? source : jpeg(quarters(size[0], size[1]));
                File output = temporaryFile(".out");
                long started = System.currentTimeMillis();
                edit(input, output, new PictureEdits.Builder()
                        .setFlippedHorizontally(true)
                        .setColorMatrix(ColorMatrix.saturation(1.01f))
                        .build(), format);
                long took = System.currentTimeMillis() - started;
                MediaTags tags = MediaTags.open(output);
                assertEquals("format " + format, format, tags.getFormat());
                Bitmap seen = seen(output);
                assertEquals("width in format " + format, size[0], seen.getWidth());
                assertEquals("height in format " + format, size[1], seen.getHeight());
                // Mirrored: green, red, yellow, blue.
                assertQuarters(seen, Color.GREEN, Color.RED, Color.YELLOW, Color.BLUE);
                if (input == source) {
                    assertEquals("format " + format, "Xlythe", tags.getMake());
                    assertEquals("format " + format, "Editor test", tags.getModel());
                    proof("format-" + format, seen);
                }
                written.append(format).append(' ').append(size[0]).append('x').append(size[1])
                        .append(' ').append(output.length()).append(" bytes ").append(took)
                        .append(" ms; ");
            }
        }
        Log.i(TAG, "Written: " + written);
    }

    /** An Ultra HDR picture's gain map turns with it, and is neutral beneath what covers it. */
    @Test
    public void anUltraHdrPictureKeepsAnEditedGainMap() throws Exception {
        assumeTrue("gain maps are Android 14's", Build.VERSION.SDK_INT >= 34);
        File source = ultraHdr(800, 400);
        MediaTags before = MediaTags.open(source);
        assertEquals(MediaTags.GAIN_MAP_ULTRA_HDR, before.getGainMap());
        proofGainmap("hdr-before", BitmapFactory.decodeFile(source.getPath()));

        // A mark over the right half of the picture, which turns to its bottom half.
        Bitmap mark = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888);
        new Canvas(mark).drawRect(100, 0, 200, 100, paint(Color.MAGENTA));
        File edited = temporaryFile(".jpg");
        edit(source, edited, new PictureEdits.Builder()
                .setRotationDegrees(90)
                .setOverlay(mark)
                .build(), PictureEditor.FORMAT_SAME);

        assertEquals(MediaTags.GAIN_MAP_ULTRA_HDR, MediaTags.open(edited).getGainMap());
        Bitmap decoded = BitmapFactory.decodeFile(edited.getPath());
        assertEquals(400, decoded.getWidth());
        assertEquals(800, decoded.getHeight());
        assertTrue("the gain map came through", decoded.hasGainmap());
        Gainmap gainmap = decoded.getGainmap();
        Bitmap gains = gainmap.getGainmapContents();
        float sx = gains.getWidth() / 400f;
        float sy = gains.getHeight() / 800f;
        // The source's gain map lit its left half, which turned clockwise is the top half.
        int top = gain(gains, (int) (200 * sx), (int) (200 * sy));
        int bottom = gain(gains, (int) (200 * sx), (int) (600 * sy));
        Log.i(TAG, "Gain at the top " + top + ", at the bottom (beneath the mark) " + bottom);
        assertTrue("the top is lit: " + top, top > 200);
        assertTrue("beneath the mark is neutral: " + bottom, bottom < 20);
        assertEquals(4f, gainmap.getRatioMax()[0], 0.05f);
        proofGainmap("hdr-after", decoded);
    }

    /** A DNG becomes a JPEG, upright, with the camera's metadata. */
    @Test
    public void aDngBecomesAPictureAnyAppCanOpen() throws Exception {
        assumeTrue("DNG decodes from Android 7.0", Build.VERSION.SDK_INT >= 24);
        File dng = asset("media/skia_sample_1mp_rotated.dng", ".dng");
        MediaTags raw = MediaTags.open(dng);
        File jpeg = temporaryFile(".jpg");
        edit(dng, jpeg, new PictureEdits.Builder().build(), PictureEditor.FORMAT_SAME);

        MediaTags tags = MediaTags.open(jpeg);
        assertEquals(MediaTags.FORMAT_JPEG, tags.getFormat());
        assertEquals(0, tags.getRotation());
        assertEquals(raw.getMake(), tags.getMake());
        assertEquals(raw.getModel(), tags.getModel());
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(dng.getPath(), bounds);
        Bitmap seen = seen(jpeg);
        boolean turned = raw.getRotation() % 180 != 0;
        Log.i(TAG, "DNG " + bounds.outWidth + "x" + bounds.outHeight + " turned "
                + raw.getRotation() + " became " + seen.getWidth() + "x" + seen.getHeight());
        assertEquals(turned ? bounds.outHeight : bounds.outWidth, seen.getWidth());
        assertEquals(turned ? bounds.outWidth : bounds.outHeight, seen.getHeight());
        proof("dng", seen);
    }

    /** A motion photo's clip turns with it, and goes cleanly when the picture is cropped. */
    @Test
    public void aMotionPhotoKeepsOrDropsItsClip() throws Exception {
        byte[] clip = readAsset("media/chromium_bear_rotate_90.mp4");
        File clipFile = temporaryFile(".mp4");
        write(clipFile, clip);
        int clipRotation = MediaTags.open(clipFile).getRotation();
        File source = motionPhoto(clip);
        assertNotNull(MediaTags.open(source).getMotionPhoto());

        File turned = temporaryFile(".jpg");
        edit(source, turned, new PictureEdits.Builder().setRotationDegrees(90).build(),
                PictureEditor.FORMAT_SAME);
        MediaTags.MotionPhoto motion = MediaTags.open(turned).getMotionPhoto();
        assertNotNull("the clip was kept", motion);
        File kept = temporaryFile(".mp4");
        write(kept, Arrays.copyOfRange(read(turned), (int) motion.getVideoOffset(),
                (int) (motion.getVideoOffset() + motion.getVideoLength())));
        assertEquals((clipRotation + 90) % 360, MediaTags.open(kept).getRotation());

        File cropped = temporaryFile(".jpg");
        edit(source, cropped, new PictureEdits.Builder()
                .setCrop(new RectF(0, 0, 0.5f, 0.5f)).build(), PictureEditor.FORMAT_SAME);
        MediaTags croppedTags = MediaTags.open(cropped);
        assertNull(croppedTags.getMotionPhoto());
        assertNull(croppedTags.getXmp("http://ns.google.com/photos/1.0/camera/", "MotionPhoto"));
        assertTrue("nothing of the clip is left", cropped.length() < source.length() - clip.length
                + 1024);
    }

    // ---------- Helpers ----------

    /** Edits a picture, waiting for the editor's report on the main thread. */
    private static void edit(File source, File output, PictureEdits edits, int format)
            throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<File> result = new AtomicReference<>();
        new Handler(Looper.getMainLooper()).post(() -> {
            PictureEditor editor = new PictureEditor(ApplicationProvider.getApplicationContext(),
                    Uri.fromFile(source), output, edits);
            editor.setOutputFormat(format);
            editor.setOnPictureEditedListener(new PictureEditor.OnPictureEditedListener() {
                @Override
                public void onPictureEdited(@NonNull File file) {
                    result.set(file);
                    done.countDown();
                }

                @Override
                public void onFailure() {
                    done.countDown();
                }
            });
            editor.start();
        });
        assertTrue("the editor never reported", done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertNotNull("the edit failed; see the PictureEditor log", result.get());
    }

    private void takePicture(File file) throws Exception {
        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            CountDownLatch opened = new CountDownLatch(1);
            CountDownLatch captured = new CountDownLatch(1);
            AtomicReference<File> saved = new AtomicReference<>();
            AtomicReference<CameraView> held = new AtomicReference<>();
            scenario.onActivity(activity -> {
                CameraView camera = activity.getCameraView();
                held.set(camera);
                camera.setOnCameraStateChangedListener(
                        new CameraView.OnCameraStateChangedListener() {
                            @Override public void onCameraOpened() {
                                opened.countDown();
                            }

                            @Override public void onCameraClosed() {}
                        });
                if (camera.isOpen()) {
                    opened.countDown();
                } else {
                    camera.open();
                }
            });
            assertTrue("the camera never opened " + DeviceState.whyTheCameraMightNotOpen(),
                    opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            CameraView camera = held.get();
            DeviceState.turnTheFlashOff(camera);
            try {
                scenario.onActivity(activity -> {
                    camera.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
                        @Override public void onImageConfirmation() {}

                        @Override public void onImageCaptured(File picture) {
                            saved.set(picture);
                            captured.countDown();
                        }

                        @Override public void onFailure() {
                            captured.countDown();
                        }
                    });
                    camera.takePicture(file);
                });
                assertTrue("the capture never reported",
                        captured.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                assertNotNull("the capture failed", saved.get());
            } finally {
                scenario.onActivity(activity -> activity.getCameraView().close());
            }
        }
    }

    /** Red, green, blue and yellow quarters, reading across from the top left. */
    private static Bitmap quarters(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawRect(0, 0, width / 2f, height / 2f, paint(Color.RED));
        canvas.drawRect(width / 2f, 0, width, height / 2f, paint(Color.GREEN));
        canvas.drawRect(0, height / 2f, width / 2f, height, paint(Color.BLUE));
        canvas.drawRect(width / 2f, height / 2f, width, height, paint(Color.YELLOW));
        return bitmap;
    }

    /** An Ultra HDR JPEG of quarters whose gain map lights only its left half, four times over. */
    @RequiresApi(34)
    private static File ultraHdr(int width, int height) throws IOException {
        Bitmap base = quarters(width, height);
        Bitmap contents = Bitmap.createBitmap(width / 2, height / 2, Bitmap.Config.ALPHA_8);
        Canvas canvas = new Canvas(contents);
        Paint lit = new Paint();
        lit.setColor(Color.argb(255, 0, 0, 0));
        canvas.drawRect(0, 0, width / 4f, height / 2f, lit);
        Gainmap gainmap = new Gainmap(contents);
        gainmap.setRatioMin(1f, 1f, 1f);
        gainmap.setRatioMax(4f, 4f, 4f);
        gainmap.setGamma(1f, 1f, 1f);
        gainmap.setEpsilonSdr(0f, 0f, 0f);
        gainmap.setEpsilonHdr(0f, 0f, 0f);
        gainmap.setMinDisplayRatioForHdrTransition(1f);
        gainmap.setDisplayRatioForFullHdr(4f);
        base.setGainmap(gainmap);
        return jpeg(base);
    }

    private static int gain(Bitmap gains, int x, int y) {
        int pixel = gains.getPixel(x, y);
        return gains.getConfig() == Bitmap.Config.ALPHA_8 ? Color.alpha(pixel) : Color.red(pixel);
    }

    private static File motionPhoto(byte[] clip) throws IOException {
        File still = jpeg(quarters(640, 360));
        String xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf="
                + "\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"\""
                + " xmlns:Camera=\"http://ns.google.com/photos/1.0/camera/\""
                + " xmlns:Container=\"http://ns.google.com/photos/1.0/container/\""
                + " xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\""
                + " Camera:MotionPhoto=\"1\" Camera:MotionPhotoVersion=\"1\""
                + " Camera:MotionPhotoPresentationTimestampUs=\"0\"><Container:Directory><rdf:Seq>"
                + "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"image/jpeg\""
                + " Item:Semantic=\"Primary\" Item:Length=\"0\" Item:Padding=\"0\"/></rdf:li>"
                + "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"video/mp4\""
                + " Item:Semantic=\"MotionPhoto\" Item:Length=\"" + clip.length + "\"/></rdf:li>"
                + "</rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>";
        byte[] jpeg = read(still);
        byte[] identifier = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.US_ASCII);
        byte[] packet = xmp.getBytes(StandardCharsets.UTF_8);
        int length = 2 + identifier.length + packet.length;
        byte[] segment = new byte[2 + length];
        segment[0] = (byte) 0xFF;
        segment[1] = (byte) 0xE1;
        segment[2] = (byte) (length >> 8);
        segment[3] = (byte) length;
        System.arraycopy(identifier, 0, segment, 4, identifier.length);
        System.arraycopy(packet, 0, segment, 4 + identifier.length, packet.length);
        byte[] whole = new byte[jpeg.length + segment.length + clip.length];
        System.arraycopy(jpeg, 0, whole, 0, 2);
        System.arraycopy(segment, 0, whole, 2, segment.length);
        System.arraycopy(jpeg, 2, whole, 2 + segment.length, jpeg.length - 2);
        System.arraycopy(clip, 0, whole, jpeg.length + segment.length, clip.length);
        File file = temporaryFile(".jpg");
        write(file, whole);
        return file;
    }

    private static int exifNumber(String tag) {
        switch (tag) {
            case "Make":
                return 0x010F;
            case "Model":
                return 0x0110;
            default:
                return 0x9003;
        }
    }

    private static File jpeg(Bitmap bitmap) throws IOException {
        File file = temporaryFile(".jpg");
        try (OutputStream out = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out));
        }
        return file;
    }

    private static Paint paint(int color) {
        Paint paint = new Paint();
        paint.setColor(color);
        return paint;
    }

    /** The picture as a viewer shows it: decoded, then turned as its metadata says. */
    private static Bitmap seen(File file) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap stored = BitmapFactory.decodeFile(file.getPath(), options);
        assertNotNull("decodes " + file, stored);
        MediaTags tags = MediaTags.open(file);
        Matrix matrix = new Matrix();
        if (tags.isMirrored()) {
            matrix.postScale(-1, 1);
            matrix.postTranslate(stored.getWidth(), 0);
        }
        int rotation = tags.getRotation();
        matrix.postRotate(rotation);
        boolean turned = rotation % 180 != 0;
        int width = turned ? stored.getHeight() : stored.getWidth();
        int height = turned ? stored.getWidth() : stored.getHeight();
        RectF bounds = new RectF(0, 0, stored.getWidth(), stored.getHeight());
        matrix.mapRect(bounds);
        matrix.postTranslate(-bounds.left, -bounds.top);
        Bitmap seen = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        new Canvas(seen).drawBitmap(stored, matrix, null);
        return seen;
    }

    private static void assertQuarters(Bitmap bitmap, int topLeft, int topRight, int bottomLeft,
            int bottomRight) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        assertColor(topLeft, bitmap.getPixel(w / 4, h / 4));
        assertColor(topRight, bitmap.getPixel(w * 3 / 4, h / 4));
        assertColor(bottomLeft, bitmap.getPixel(w / 4, h * 3 / 4));
        assertColor(bottomRight, bitmap.getPixel(w * 3 / 4, h * 3 / 4));
    }

    private static void assertColor(int expected, int actual) {
        String message = String.format("expected #%08X, was #%08X", expected, actual);
        assertEquals(message, Color.red(expected), Color.red(actual), 40);
        assertEquals(message, Color.green(expected), Color.green(actual), 40);
        assertEquals(message, Color.blue(expected), Color.blue(actual), 40);
    }

    /** Writes a picture for a person to look at. */
    private static void proof(String name, Bitmap bitmap) throws IOException {
        try (OutputStream out = new FileOutputStream(new File(sProof, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    /** Writes a picture beside its gain map, brightest where the gain is greatest. */
    @RequiresApi(34)
    private static void proofGainmap(String name, Bitmap picture) throws IOException {
        proof(name, picture);
        if (!picture.hasGainmap()) {
            return;
        }
        Bitmap gains = picture.getGainmap().getGainmapContents();
        Bitmap shown = Bitmap.createBitmap(gains.getWidth(), gains.getHeight(),
                Bitmap.Config.ARGB_8888);
        for (int y = 0; y < gains.getHeight(); y++) {
            for (int x = 0; x < gains.getWidth(); x++) {
                int gain = gain(gains, x, y);
                shown.setPixel(x, y, Color.rgb(gain, gain, gain));
            }
        }
        proof(name + "-gainmap", shown);
    }

    @Nullable
    private static File asset(String path, String extension) throws IOException {
        File file = temporaryFile(extension);
        write(file, readAsset(path));
        return file;
    }

    private static byte[] readAsset(String path) throws IOException {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        try (InputStream input = context.getAssets().open(path)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                bytes.write(buffer, 0, count);
            }
            return bytes.toByteArray();
        }
    }

    private static byte[] read(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int at = 0;
            while (at < bytes.length) {
                int count = input.read(bytes, at, bytes.length - at);
                if (count < 0) {
                    break;
                }
                at += count;
            }
            return bytes;
        }
    }

    private static void write(File file, byte[] bytes) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
    }

    private static File temporaryFile(String extension) throws IOException {
        File file = File.createTempFile("editor", extension,
                ApplicationProvider.getApplicationContext().getCacheDir());
        file.deleteOnExit();
        return file;
    }
}
