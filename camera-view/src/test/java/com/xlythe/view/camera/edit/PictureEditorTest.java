package com.xlythe.view.camera.edit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.location.Location;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import com.xlythe.view.camera.Exif;
import com.xlythe.view.camera.MediaTags;
import com.xlythe.view.camera.MediaTestFiles;
import com.xlythe.view.camera.gl.ColorMatrix;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Edits real pictures, decoded and encoded for real, and checks what a viewer would show.
 *
 * <p>Every source is lopsided: its four quarters are red, green, blue and yellow from the top
 * left, reading across, so a turn, a mirror or a crop in the wrong place fails.</p>
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 37)
public class PictureEditorTest {
    private static final int RED = Color.RED;
    private static final int GREEN = Color.GREEN;
    private static final int BLUE = Color.BLUE;
    private static final int YELLOW = Color.YELLOW;

    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    private Context context;
    private File output;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        output = new File(folder.getRoot(), "edited");
    }

    // ---------- Geometry ----------

    @Test
    public void aQuarterTurnTurnsThePictureClockwise() throws IOException {
        File source = jpeg(quarters(80, 40));
        // A clear overlay, so the picture is redrawn rather than only said to turn.
        edit(source, new PictureEdits.Builder().setRotationDegrees(90)
                .setOverlay(Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)).build());
        Bitmap seen = seen(output);
        assertEquals(40, seen.getWidth());
        assertEquals(80, seen.getHeight());
        assertQuarters(seen, BLUE, RED, YELLOW, GREEN);
    }

    @Test
    public void flipsAndACropApplyToThePictureAsTurned() throws IOException {
        File source = jpeg(quarters(80, 40));
        // Turned, blue is at the top left; mirrored, red is; the crop keeps that quarter.
        edit(source, new PictureEdits.Builder()
                .setRotationDegrees(90)
                .setFlippedHorizontally(true)
                .setCrop(new RectF(0, 0, 0.5f, 0.5f))
                .build());
        Bitmap seen = seen(output);
        assertEquals(20, seen.getWidth());
        assertEquals(40, seen.getHeight());
        assertColor(RED, seen.getPixel(10, 20));
        assertColor(RED, seen.getPixel(2, 2));
        assertColor(RED, seen.getPixel(17, 37));
    }

    @Test
    public void aVerticalFlipMirrorsTopToBottom() throws IOException {
        File source = png(quarters(80, 40));
        edit(source, new PictureEdits.Builder().setFlippedVertically(true).build());
        assertQuarters(seen(output), BLUE, YELLOW, RED, GREEN);
    }

    @Test
    public void straighteningLeavesEmptyCornersClearOrWhite() throws IOException {
        Bitmap opaque = quarters(100, 100);
        PictureEdits edits = new PictureEdits.Builder().setStraightenDegrees(30).build();

        edit(png(opaque), edits);
        Bitmap clear = seen(output);
        assertEquals(0, Color.alpha(clear.getPixel(1, 1)));
        assertEquals(255, Color.alpha(clear.getPixel(50, 50)));

        File jpegOutput = new File(folder.getRoot(), "straightened.jpg");
        PictureEditor editor = new PictureEditor(context, Uri.fromFile(jpeg(opaque)), jpegOutput,
                edits);
        editor.edit(jpegOutput);
        assertColor(Color.WHITE, seen(jpegOutput).getPixel(1, 1));
    }

    @Test
    public void anOrientedPictureIsEditedAsItIsSeenAndSavedUpright() throws IOException {
        // Stored on its side: what is seen is the quarters picture once it is turned.
        Bitmap seenBefore = quarters(80, 40);
        File source = jpeg(turn(seenBefore, 270));
        setOrientation(source, ExifInterface.ORIENTATION_ROTATE_90);
        assertQuarters(seen(source), RED, GREEN, BLUE, YELLOW);

        edit(source, new PictureEdits.Builder().setCrop(new RectF(0, 0, 1, 0.5f)).build());
        MediaTags tags = MediaTags.open(output);
        assertEquals(0, tags.getRotation());
        assertFalse(tags.isMirrored());
        Bitmap stored = BitmapFactory.decodeFile(output.getPath());
        assertEquals(80, stored.getWidth());
        assertEquals(20, stored.getHeight());
        assertColor(RED, stored.getPixel(10, 10));
        assertColor(GREEN, stored.getPixel(70, 10));
    }

    @Test
    public void aLimitOnPixelsScalesThePictureDown() throws IOException {
        File source = jpeg(quarters(400, 200));
        PictureEditor editor = new PictureEditor(context, Uri.fromFile(source), output,
                new PictureEdits.Builder().setColorMatrix(ColorMatrix.sepia()).build());
        editor.setMaxOutputPixels(20_000);
        editor.edit(output);
        Bitmap seen = seen(output);
        assertTrue(seen.getWidth() * seen.getHeight() <= 20_000);
        assertEquals(2f, seen.getWidth() / (float) seen.getHeight(), 0.05f);
        assertEquals(PictureEditor.FORMAT_JPEG, MediaTags.open(output).getFormat());
    }

    // ---------- Colour and overlays ----------

    @Test
    public void aFilterChangesThePictureButNotItsOverlay() throws IOException {
        Bitmap overlay = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888);
        new Canvas(overlay).drawRect(0, 0, 20, 10, paint(Color.MAGENTA));
        edit(png(quarters(80, 40)), new PictureEdits.Builder()
                .setColorMatrix(ColorMatrix.grayscale())
                .setOverlay(overlay)
                .build());
        Bitmap seen = seen(output);
        int grey = seen.getPixel(60, 10);
        assertEquals(Color.red(grey), Color.green(grey), 1);
        assertEquals(Color.green(grey), Color.blue(grey), 1);
        assertColor(Color.MAGENTA, seen.getPixel(5, 5));
    }

    @Test
    public void anOverlayCanBeFilteredToo() throws IOException {
        Bitmap overlay = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888);
        new Canvas(overlay).drawRect(0, 0, 20, 10, paint(Color.MAGENTA));
        edit(png(quarters(80, 40)), new PictureEdits.Builder()
                .setColorMatrix(ColorMatrix.grayscale())
                .setOverlay(overlay)
                .setOverlayFiltered(true)
                .build());
        int mark = seen(output).getPixel(5, 5);
        assertEquals(Color.red(mark), Color.green(mark), 1);
    }

    @Test
    public void anOverlayStaysWhereItWasDrawnWhenThePictureTurns() throws IOException {
        // Drawn at half size over the top left of the picture.
        Bitmap overlay = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888);
        new Canvas(overlay).drawRect(0, 0, 10, 5, paint(Color.MAGENTA));
        edit(png(quarters(80, 40)), new PictureEdits.Builder()
                .setRotationDegrees(90)
                .setOverlay(overlay)
                .build());
        Bitmap seen = seen(output);
        // The picture's top left is the top right once turned clockwise.
        assertColor(Color.MAGENTA, seen.getPixel(35, 5));
        assertColor(BLUE, seen.getPixel(5, 5));
    }

    // ---------- Turns and flips alone ----------

    @Test
    public void aTurnAloneKeepsEveryPixelAndChangesTheOrientation() throws IOException {
        File source = jpeg(quarters(80, 40));
        edit(source, new PictureEdits.Builder().setRotationDegrees(90).build());
        MediaTags tags = MediaTags.open(output);
        assertEquals(90, tags.getRotation());
        assertPixelsEqual(BitmapFactory.decodeFile(source.getPath()),
                BitmapFactory.decodeFile(output.getPath()));
        assertQuarters(seen(output), BLUE, RED, YELLOW, GREEN);
    }

    @Test
    public void turnsAndFlipsAddToTheOrientationAFileHas() throws IOException {
        File source = jpeg(quarters(80, 40));
        setOrientation(source, ExifInterface.ORIENTATION_ROTATE_90);
        edit(source, new PictureEdits.Builder().setRotationDegrees(90).build());
        assertEquals(180, MediaTags.open(output).getRotation());

        edit(source, new PictureEdits.Builder().setRotationDegrees(90)
                .setFlippedHorizontally(true).build());
        MediaTags tags = MediaTags.open(output);
        assertEquals(180, tags.getRotation());
        assertTrue(tags.isMirrored());
        assertPixelsEqual(BitmapFactory.decodeFile(source.getPath()),
                BitmapFactory.decodeFile(output.getPath()));
    }

    @Test
    public void aTurnIntoAnotherFormatIsDrawnUpright() throws IOException {
        File source = jpeg(quarters(80, 40));
        PictureEditor editor = new PictureEditor(context, Uri.fromFile(source), output,
                new PictureEdits.Builder().setRotationDegrees(270).build());
        editor.setOutputFormat(PictureEditor.FORMAT_PNG);
        editor.edit(output);
        MediaTags tags = MediaTags.open(output);
        assertEquals(MediaTags.FORMAT_PNG, tags.getFormat());
        assertEquals(0, tags.getRotation());
        assertQuarters(seen(output), GREEN, YELLOW, RED, BLUE);
    }

    // ---------- Formats ----------

    @Test
    public void picturesConvertBetweenFormats() throws IOException {
        File source = jpeg(quarters(80, 40));
        for (int format : new int[] {PictureEditor.FORMAT_PNG, PictureEditor.FORMAT_WEBP,
                PictureEditor.FORMAT_JPEG}) {
            PictureEditor editor = new PictureEditor(context, Uri.fromFile(source), output,
                    new PictureEdits.Builder().build());
            editor.setOutputFormat(format);
            editor.edit(output);
            assertEquals(format, MediaTags.open(output).getFormat());
            assertQuarters(seen(output), RED, GREEN, BLUE, YELLOW);
        }
    }

    @Test
    public void transparencyBecomesWhiteInAFormatWithoutIt() throws IOException {
        Bitmap clear = quarters(40, 40);
        clear.setPixel(0, 0, Color.TRANSPARENT);
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 10; x++) {
                clear.setPixel(x, y, Color.TRANSPARENT);
            }
        }
        PictureEditor editor = new PictureEditor(context, Uri.fromFile(png(clear)), output,
                new PictureEdits.Builder().build());
        editor.setOutputFormat(PictureEditor.FORMAT_JPEG);
        editor.edit(output);
        assertColor(Color.WHITE, seen(output).getPixel(3, 3));

        PictureEditor lossless = new PictureEditor(context, Uri.fromFile(png(clear)), output,
                new PictureEdits.Builder().build());
        lossless.setOutputFormat(PictureEditor.FORMAT_WEBP);
        lossless.setQuality(100);
        lossless.edit(output);
        assertEquals(0, Color.alpha(seen(output).getPixel(3, 3)));
    }

    @Test
    public void theSameFormatIsKeptWhereItCanBeWritten() {
        assertEquals(PictureEditor.FORMAT_PNG,
                PictureEditor.resolveFormat(PictureEditor.FORMAT_SAME, MediaTags.FORMAT_PNG));
        assertEquals(PictureEditor.FORMAT_WEBP,
                PictureEditor.resolveFormat(PictureEditor.FORMAT_SAME, MediaTags.FORMAT_WEBP));
        assertEquals(PictureEditor.FORMAT_JPEG,
                PictureEditor.resolveFormat(PictureEditor.FORMAT_SAME, MediaTags.FORMAT_JPEG));
        assertEquals(PictureEditor.FORMAT_PNG,
                PictureEditor.resolveFormat(PictureEditor.FORMAT_SAME, MediaTags.FORMAT_GIF));
        assertEquals(PictureEditor.FORMAT_JPEG,
                PictureEditor.resolveFormat(PictureEditor.FORMAT_SAME, MediaTags.FORMAT_DNG));
        assertEquals(PictureEditor.FORMAT_JPEG,
                PictureEditor.resolveFormat(PictureEditor.FORMAT_SAME, MediaTags.FORMAT_UNKNOWN));
        // No HEIF encoder off a device.
        assertEquals(PictureEditor.FORMAT_JPEG,
                PictureEditor.resolveFormat(PictureEditor.FORMAT_SAME, MediaTags.FORMAT_HEIF));
        assertEquals(PictureEditor.FORMAT_AVIF,
                PictureEditor.resolveFormat(PictureEditor.FORMAT_AVIF, MediaTags.FORMAT_JPEG));
    }

    @Test
    public void onlyTheFormatsAnEncoderExistsForAreSupported() {
        assertTrue(PictureEditor.isOutputFormatSupported(PictureEditor.FORMAT_SAME));
        assertTrue(PictureEditor.isOutputFormatSupported(PictureEditor.FORMAT_JPEG));
        assertTrue(PictureEditor.isOutputFormatSupported(PictureEditor.FORMAT_PNG));
        assertTrue(PictureEditor.isOutputFormatSupported(PictureEditor.FORMAT_WEBP));
        assertFalse(PictureEditor.isOutputFormatSupported(PictureEditor.FORMAT_HEIF));
        assertFalse(PictureEditor.isOutputFormatSupported(PictureEditor.FORMAT_AVIF));
        assertFalse(PictureEditor.isOutputFormatSupported(4242));
    }

    @Test
    public void aFormatWithNoEncoderFails() throws IOException {
        PictureEditor editor = new PictureEditor(context, Uri.fromFile(jpeg(quarters(8, 8))),
                output, new PictureEdits.Builder().build());
        editor.setOutputFormat(PictureEditor.FORMAT_HEIF);
        assertThrows(IOException.class, () -> editor.edit(output));
    }

    @Test
    public void anAddedEncoderWritesItsFormat() throws IOException {
        int format = 4243;
        PictureEditor.registerEncoder(format, (bitmap, quality, stream) -> {
            stream.write(("ADDED " + bitmap.getWidth() + "x" + bitmap.getHeight() + " " + quality)
                    .getBytes(StandardCharsets.US_ASCII));
        });
        assertTrue(PictureEditor.isOutputFormatSupported(format));
        PictureEditor editor = new PictureEditor(context, Uri.fromFile(jpeg(quarters(8, 6))),
                output, new PictureEdits.Builder().build());
        editor.setOutputFormat(format);
        editor.setQuality(80);
        editor.edit(output);
        assertEquals("ADDED 8x6 80",
                new String(Files.readAllBytes(output.toPath()), StandardCharsets.US_ASCII));
    }

    @Test
    public void tooLargeAPictureForTheFormatFails() throws IOException {
        // A tall sliver, cheap to hold, that WebP cannot be.
        File source = png(Bitmap.createBitmap(1, 17000, Bitmap.Config.ARGB_8888));
        PictureEditor editor = new PictureEditor(context, Uri.fromFile(source), output,
                new PictureEdits.Builder().build());
        editor.setOutputFormat(PictureEditor.FORMAT_WEBP);
        assertThrows(IOException.class, () -> editor.edit(output));
    }

    // ---------- Metadata ----------

    @Test
    public void metadataComesThroughIntoEveryFormat() throws IOException {
        File source = jpeg(quarters(320, 240));
        Exif exif = new Exif(source);
        exif.setAttribute(ExifInterface.TAG_MAKE, "Xlythe");
        exif.setAttribute(ExifInterface.TAG_MODEL, "Editor");
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:27 12:34:56");
        exif.setAttribute(ExifInterface.TAG_ORIENTATION,
                Integer.toString(ExifInterface.ORIENTATION_ROTATE_180));
        exif.attachThumbnail();
        exif.save();
        MediaTags located = MediaTags.open(source);
        Location location = new Location("test");
        location.setLatitude(37.42);
        location.setLongitude(-122.08);
        assertTrue(located.setLocation(location));
        located.save();
        long captured = MediaTags.open(source).getCaptureTime();

        for (int format : new int[] {PictureEditor.FORMAT_JPEG, PictureEditor.FORMAT_PNG,
                PictureEditor.FORMAT_WEBP}) {
            PictureEditor editor = new PictureEditor(context, Uri.fromFile(source), output,
                    new PictureEdits.Builder().setCrop(new RectF(0, 0, 0.5f, 1)).build());
            editor.setOutputFormat(format);
            editor.edit(output);
            MediaTags tags = MediaTags.open(output);
            assertEquals("Xlythe", tags.getMake());
            assertEquals("Editor", tags.getModel());
            assertEquals(captured, tags.getCaptureTime());
            assertNotNull(tags.getLocation());
            assertEquals(37.42, tags.getLocation().getLatitude(), 0.001);
            assertEquals("the picture is saved upright", 0, tags.getRotation());
            // Upside down as stored, so the left half as seen is the stored right half, turned:
            // yellow above green.
            assertQuarters(seen(output), YELLOW, YELLOW, GREEN, GREEN);
            if (format == PictureEditor.FORMAT_JPEG) {
                assertEquals("160", tags.getExifTag(0xA002));
                assertEquals("240", tags.getExifTag(0xA003));
                assertTrue(new ExifInterface(output.getPath()).hasThumbnail());
            }
        }
    }

    @Test
    public void aPanoramaKeepsWhereItSitsOnTheSphere() throws IOException {
        MediaTags.Panorama panorama = new MediaTags.Panorama.Builder()
                .setProjectionType(MediaTags.Panorama.PROJECTION_EQUIRECTANGULAR)
                .setFullPanoWidthPixels(400)
                .setFullPanoHeightPixels(200)
                .setCroppedAreaImageWidthPixels(200)
                .setCroppedAreaImageHeightPixels(100)
                .setCroppedAreaLeftPixels(100)
                .setCroppedAreaTopPixels(50)
                .build();
        File source = withXmp(jpeg(quarters(200, 100)), MediaTestFiles.panoramaXmp(panorama));
        assertNotNull(MediaTags.open(source).getPanorama());

        edit(source, new PictureEdits.Builder().setCrop(new RectF(0.5f, 0, 1, 1)).build());
        MediaTags.Panorama edited = MediaTags.open(output).getPanorama();
        assertNotNull(edited);
        assertEquals(Integer.valueOf(200), edited.getCroppedAreaLeftPixels());
        assertEquals(Integer.valueOf(100), edited.getCroppedAreaImageWidthPixels());
        assertEquals(Integer.valueOf(400), edited.getFullPanoWidthPixels());

        edit(source, new PictureEdits.Builder().setRotationDegrees(90)
                .setColorMatrix(ColorMatrix.sepia()).build());
        assertNull("a turned panorama is no longer one", MediaTags.open(output).getPanorama());
    }

    // ---------- Motion photos ----------

    @Test
    public void aMotionPhotoKeepsItsClipThroughAFilter() throws IOException {
        byte[] clip = MediaTestFiles.movie();
        File source = motionPhoto(clip);
        edit(source, new PictureEdits.Builder().setColorMatrix(ColorMatrix.sepia()).build());
        MediaTags.MotionPhoto motion = MediaTags.open(output).getMotionPhoto();
        assertNotNull(motion);
        assertEquals(MediaTags.MotionPhoto.FORMAT_MOTION_PHOTO, motion.getFormat());
        assertArrayEquals(clip, clipOf(output, motion));
        assertEquals(1234, motion.getPresentationTimestampUs());
    }

    @Test
    public void aCropDropsTheClipCleanly() throws IOException {
        File source = motionPhoto(MediaTestFiles.movie());
        edit(source, new PictureEdits.Builder().setCrop(new RectF(0, 0, 0.5f, 0.5f)).build());
        assertNoClip(output);
    }

    @Test
    public void aTurnTurnsTheClipToo() throws IOException {
        byte[] clip = MediaTestFiles.movie();
        assertEquals(90, MediaTags.open(file(clip)).getRotation());
        File source = motionPhoto(clip);
        edit(source, new PictureEdits.Builder().setRotationDegrees(90).build());
        MediaTags tags = MediaTags.open(output);
        assertEquals("only the orientation changed", 90, tags.getRotation());
        MediaTags.MotionPhoto motion = tags.getMotionPhoto();
        assertNotNull(motion);
        assertEquals(180, MediaTags.open(file(clipOf(output, motion)))
                .getRotation());
    }

    @Test
    public void aFlipDropsTheClipThatCannotBeMirrored() throws IOException {
        File source = motionPhoto(MediaTestFiles.movie());
        edit(source, new PictureEdits.Builder().setFlippedHorizontally(true).build());
        assertTrue(MediaTags.open(output).isMirrored());
        assertNoClip(output);
    }

    @Test
    public void theClipCanBeDroppedOrKeptWhateverTheEdit() throws IOException {
        byte[] clip = MediaTestFiles.movie();
        File source = motionPhoto(clip);
        PictureEditor dropping = new PictureEditor(context, Uri.fromFile(source), output,
                new PictureEdits.Builder().setColorMatrix(ColorMatrix.sepia()).build());
        dropping.setMotionPhotoPolicy(PictureEditor.MOTION_PHOTO_DROP);
        dropping.edit(output);
        assertNoClip(output);

        PictureEditor keeping = new PictureEditor(context, Uri.fromFile(source), output,
                new PictureEdits.Builder().setCrop(new RectF(0, 0, 0.5f, 0.5f)).build());
        keeping.setMotionPhotoPolicy(PictureEditor.MOTION_PHOTO_KEEP);
        keeping.edit(output);
        MediaTags.MotionPhoto motion = MediaTags.open(output).getMotionPhoto();
        assertNotNull(motion);
        assertArrayEquals(clip, clipOf(output, motion));
    }

    @Test
    public void aClipGoesOnlyIntoAFormatThatCarriesOne() throws IOException {
        File source = motionPhoto(MediaTestFiles.movie());
        PictureEditor editor = new PictureEditor(context, Uri.fromFile(source), output,
                new PictureEdits.Builder().build());
        editor.setOutputFormat(PictureEditor.FORMAT_PNG);
        editor.setMotionPhotoPolicy(PictureEditor.MOTION_PHOTO_KEEP);
        editor.edit(output);
        assertNull(MediaTags.open(output).getMotionPhoto());
    }

    @Test
    public void aSamsungClipIsKeptInGooglesForm() throws IOException {
        byte[] clip = MediaTestFiles.movie();
        File source = file(MediaTestFiles.concat(bytes(jpeg(quarters(80, 40))),
                MediaTestFiles.samsungTrailer(clip)));
        assertEquals(MediaTags.MotionPhoto.FORMAT_SAMSUNG,
                MediaTags.open(source).getMotionPhoto().getFormat());

        edit(source, new PictureEdits.Builder().setRotationDegrees(180).build());
        MediaTags tags = MediaTags.open(output);
        MediaTags.MotionPhoto motion = tags.getMotionPhoto();
        assertNotNull(motion);
        assertEquals(MediaTags.MotionPhoto.FORMAT_MOTION_PHOTO, motion.getFormat());
        assertEquals(270, MediaTags.open(file(clipOf(output, motion)))
                .getRotation());
        byte[] edited = bytes(output);
        assertEquals("nothing of Samsung's trailer is left", -1,
                indexOf(edited, "MotionPhoto_Data".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(-1, indexOf(edited, "SEFT".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void whetherAClipIsKeptAndHowItTurns() {
        PictureEdits none = new PictureEdits.Builder().build();
        PictureEdits turned = new PictureEdits.Builder().setRotationDegrees(270).build();
        PictureEdits halfTurnByFlips = new PictureEdits.Builder().setFlippedHorizontally(true)
                .setFlippedVertically(true).build();
        PictureEdits mirrored = new PictureEdits.Builder().setFlippedVertically(true).build();
        PictureEdits cropped = new PictureEdits.Builder()
                .setCrop(new RectF(0, 0, 0.5f, 1)).build();
        int auto = PictureEditor.MOTION_PHOTO_AUTO;
        int keep = PictureEditor.MOTION_PHOTO_KEEP;
        int jpeg = PictureEditor.FORMAT_JPEG;
        assertEquals(0, PictureEditor.clipTurn(auto, none, jpeg));
        assertEquals(270, PictureEditor.clipTurn(auto, turned, jpeg));
        assertEquals(180, PictureEditor.clipTurn(auto, halfTurnByFlips, jpeg));
        assertEquals(-1, PictureEditor.clipTurn(auto, mirrored, jpeg));
        assertEquals(-1, PictureEditor.clipTurn(auto, cropped, jpeg));
        assertEquals(180, PictureEditor.clipTurn(keep, mirrored, jpeg));
        assertEquals(0, PictureEditor.clipTurn(keep, cropped, PictureEditor.FORMAT_HEIF));
        assertEquals(-1, PictureEditor.clipTurn(keep, none, PictureEditor.FORMAT_WEBP));
        assertEquals(-1, PictureEditor.clipTurn(PictureEditor.MOTION_PHOTO_DROP, none, jpeg));
    }

    // ---------- Running ----------

    @Test
    public void theResultArrivesOnTheMainThread() throws IOException {
        File source = jpeg(quarters(40, 20));
        PictureEditor editor = new PictureEditor(context, Uri.fromFile(source), output,
                new PictureEdits.Builder().setRotationDegrees(90).build());
        Listener listener = new Listener();
        editor.setOnPictureEditedListener(listener);
        assertSame(listener, editor.getOnPictureEditedListener());
        assertFalse(editor.isRunning());
        editor.start();
        assertTrue(editor.isRunning());
        editor.start();
        listener.await();
        assertSame(output, listener.edited);
        assertFalse(listener.failed);
        assertFalse(editor.isRunning());
        assertEquals(90, MediaTags.open(output).getRotation());
    }

    @Test
    public void somethingThatIsNotAPictureFails() throws IOException {
        File source = file("not a picture".getBytes(StandardCharsets.US_ASCII));
        PictureEditor editor = new PictureEditor(context, Uri.fromFile(source), output,
                new PictureEdits.Builder().setColorMatrix(ColorMatrix.sepia()).build());
        Listener listener = new Listener();
        editor.setOnPictureEditedListener(listener);
        editor.start();
        listener.await();
        assertTrue(listener.failed);
        assertFalse(output.exists());
        assertEquals("nothing is left behind", 1, folder.getRoot().listFiles().length);
    }

    @Test
    public void aCancelledEditWritesNothingAndSaysNothing() throws Exception {
        File source = jpeg(quarters(800, 600));
        PictureEditor editor = new PictureEditor(context, Uri.fromFile(source), output,
                new PictureEdits.Builder().setColorMatrix(ColorMatrix.sepia()).build());
        Listener listener = new Listener();
        editor.setOnPictureEditedListener(listener);
        editor.cancel();
        editor.start();
        editor.cancel();
        assertFalse(editor.isRunning());
        long until = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < until) {
            ShadowLooper.idleMainLooper();
            Thread.sleep(20);
        }
        assertFalse(listener.called);
        assertFalse(output.exists());
    }

    @Test
    public void settingsAreCheckedAndKept() {
        PictureEditor editor = new PictureEditor(context, Uri.EMPTY, output,
                new PictureEdits.Builder().build());
        assertEquals(PictureEditor.FORMAT_SAME, editor.getOutputFormat());
        assertEquals(PictureEditor.DEFAULT_QUALITY, editor.getQuality());
        assertEquals(0, editor.getMaxOutputPixels());
        assertEquals(PictureEditor.MOTION_PHOTO_AUTO, editor.getMotionPhotoPolicy());
        editor.setOutputFormat(PictureEditor.FORMAT_WEBP);
        editor.setQuality(0);
        editor.setMaxOutputPixels(1000);
        editor.setMotionPhotoPolicy(PictureEditor.MOTION_PHOTO_KEEP);
        assertEquals(PictureEditor.FORMAT_WEBP, editor.getOutputFormat());
        assertEquals(0, editor.getQuality());
        assertEquals(1000, editor.getMaxOutputPixels());
        assertEquals(PictureEditor.MOTION_PHOTO_KEEP, editor.getMotionPhotoPolicy());
        assertThrows(IllegalArgumentException.class, () -> editor.setQuality(101));
        assertThrows(IllegalArgumentException.class, () -> editor.setQuality(-1));
        assertThrows(IllegalArgumentException.class, () -> editor.setMaxOutputPixels(-1));
    }

    @Test
    public void decodingIsNeverCoarserThanTheResult() {
        assertEquals(1, PictureEditor.sampleSize(1f));
        assertEquals(1, PictureEditor.sampleSize(0.6f));
        assertEquals(2, PictureEditor.sampleSize(0.5f));
        assertEquals(2, PictureEditor.sampleSize(0.3f));
        assertEquals(4, PictureEditor.sampleSize(0.25f));
        assertTrue(PictureEditor.memoryBudget(context) > 0);
        assertTrue(PictureEditor.keepsTransparency(PictureEditor.FORMAT_PNG));
        assertTrue(PictureEditor.keepsTransparency(PictureEditor.FORMAT_WEBP));
        assertFalse(PictureEditor.keepsTransparency(PictureEditor.FORMAT_JPEG));
        assertFalse(PictureEditor.keepsTransparency(PictureEditor.FORMAT_HEIF));
    }

    // ---------- Helpers ----------

    private void edit(File source, PictureEdits edits) throws IOException {
        new PictureEditor(context, Uri.fromFile(source), output, edits).edit(output);
    }

    /** Red, green, blue and yellow quarters, reading across from the top left. */
    static Bitmap quarters(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawRect(0, 0, width / 2f, height / 2f, paint(RED));
        canvas.drawRect(width / 2f, 0, width, height / 2f, paint(GREEN));
        canvas.drawRect(0, height / 2f, width / 2f, height, paint(BLUE));
        canvas.drawRect(width / 2f, height / 2f, width, height, paint(YELLOW));
        return bitmap;
    }

    private static Paint paint(int color) {
        Paint paint = new Paint();
        paint.setColor(color);
        return paint;
    }

    private static Bitmap turn(Bitmap bitmap, int degrees) {
        Matrix matrix = new Matrix();
        matrix.setRotate(degrees);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix,
                false);
    }

    private File jpeg(Bitmap bitmap) throws IOException {
        return encode(bitmap, Bitmap.CompressFormat.JPEG);
    }

    private File png(Bitmap bitmap) throws IOException {
        return encode(bitmap, Bitmap.CompressFormat.PNG);
    }

    private File encode(Bitmap bitmap, Bitmap.CompressFormat format) throws IOException {
        File file = folder.newFile();
        try (OutputStream out = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(format, 100, out));
        }
        return file;
    }

    private File file(byte[] bytes) throws IOException {
        File file = folder.newFile();
        Files.write(file.toPath(), bytes);
        return file;
    }

    private File withXmp(File jpeg, String packet) throws IOException {
        return file(MediaTestFiles.withXmp(bytes(jpeg), packet));
    }

    private File motionPhoto(byte[] clip) throws IOException {
        byte[] still = MediaTestFiles.withXmp(bytes(jpeg(quarters(80, 40))),
                MediaTestFiles.motionPhotoXmpWithTime(clip.length));
        File file = file(MediaTestFiles.concat(still, clip));
        assertNotNull(MediaTags.open(file).getMotionPhoto());
        return file;
    }

    private static byte[] bytes(File file) throws IOException {
        return Files.readAllBytes(file.toPath());
    }

    private static byte[] clipOf(File file, MediaTags.MotionPhoto motion) throws IOException {
        byte[] bytes = bytes(file);
        return Arrays.copyOfRange(bytes, (int) motion.getVideoOffset(),
                (int) (motion.getVideoOffset() + motion.getVideoLength()));
    }

    private static void assertNoClip(File file) throws IOException {
        MediaTags tags = MediaTags.open(file);
        assertNull(tags.getMotionPhoto());
        assertNull(tags.getXmp("http://ns.google.com/photos/1.0/camera/", "MotionPhoto"));
        assertEquals("nothing of the clip is left", -1,
                indexOf(bytes(file), "ftyp".getBytes(StandardCharsets.US_ASCII)));
    }

    private static int indexOf(byte[] bytes, byte[] pattern) {
        outer:
        for (int i = 0; i + pattern.length <= bytes.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (bytes[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static void setOrientation(File file, int orientation) throws IOException {
        ExifInterface exif = new ExifInterface(file.getPath());
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(orientation));
        exif.saveAttributes();
    }

    /** The picture as a viewer shows it: decoded, then turned as its metadata says. */
    private static Bitmap seen(File file) throws IOException {
        Bitmap stored = BitmapFactory.decodeFile(file.getPath());
        assertNotNull("decodes", stored);
        MediaTags tags = MediaTags.open(file);
        Matrix matrix = PictureGeometry.orientationMatrix(stored.getWidth(), stored.getHeight(),
                tags.getRotation(), tags.isMirrored());
        boolean transposed = PictureGeometry.isTransposed(tags.getRotation());
        Bitmap seen = Bitmap.createBitmap(transposed ? stored.getHeight() : stored.getWidth(),
                transposed ? stored.getWidth() : stored.getHeight(), Bitmap.Config.ARGB_8888);
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
        assertEquals(message, Color.red(expected), Color.red(actual), 24);
        assertEquals(message, Color.green(expected), Color.green(actual), 24);
        assertEquals(message, Color.blue(expected), Color.blue(actual), 24);
    }

    private static void assertPixelsEqual(Bitmap expected, Bitmap actual) {
        assertEquals(expected.getWidth(), actual.getWidth());
        assertEquals(expected.getHeight(), actual.getHeight());
        int[] a = new int[expected.getWidth() * expected.getHeight()];
        int[] b = new int[a.length];
        expected.getPixels(a, 0, expected.getWidth(), 0, 0, expected.getWidth(),
                expected.getHeight());
        actual.getPixels(b, 0, actual.getWidth(), 0, 0, actual.getWidth(), actual.getHeight());
        assertArrayEquals(a, b);
    }

    /** Records the result, and waits for it while the main looper runs. */
    private static final class Listener implements PictureEditor.OnPictureEditedListener {
        @Nullable File edited;
        boolean failed;
        boolean called;

        @Override
        public void onPictureEdited(@NonNull File file) {
            edited = file;
            called = true;
        }

        @Override
        public void onFailure() {
            failed = true;
            called = true;
        }

        void await() {
            long until = System.currentTimeMillis() + 20_000;
            while (!called && System.currentTimeMillis() < until) {
                ShadowLooper.idleMainLooper();
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            assertTrue("the editor reported", called);
        }
    }
}
