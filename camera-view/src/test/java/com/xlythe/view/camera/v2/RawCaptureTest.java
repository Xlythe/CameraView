package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraMetadata;
import android.media.FakeImage;
import android.media.ImageReader;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.annotation.NonNull;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.CaptureDelivery;
import com.xlythe.view.camera.shadow.ShadowCallbackCameraCaptureSession;
import com.xlythe.view.camera.shadow.ShadowInjectableImageReader;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPausedAsyncTask;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RAW capture on the camera2 backend.
 *
 * <p>What can be asserted without a sensor: that the format is offered only when the camera says
 * both things that make it possible, that a RAW session carries a reader at the size DngCreator
 * accepts, that a frame is paired with the result carrying its timestamp, and that the reader
 * outlives a frame still being written when the session goes away. The DNG itself needs a camera,
 * and RawCaptureDeviceTest is where it is opened.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, qualifiers = "w360dp-h800dp-xxxhdpi", shadows = {
        ShadowCallbackCameraCaptureSession.class,
        ShadowInjectableImageReader.class})
public class RawCaptureTest {
    /** Stands in for encoded JPEG bytes; nothing in the path parses them. */
    private static final byte[] JPEG_BYTES =
            "not-really-a-jpeg".getBytes(StandardCharsets.US_ASCII);

    private Context context;
    private FakeCamera2.TestCameraView cameraView;
    private Camera2Module module;
    private final AtomicReference<File> captured = new AtomicReference<>();
    private final AtomicInteger reports = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();

    /** Where each picture taken without a file was filed, in the order the listener heard. */
    private final List<Uri> saved = new ArrayList<>();

    /** Writes handed to AsyncTask, held until a test runs them. */
    private final List<Runnable> writes = new ArrayList<>();

    /** Readers already open when the test started, which it does not count. */
    private int readersBefore;

    @Before
    public void setUp() {
        // Robolectric runs no resetter for a shadow a test declares itself.
        ShadowInjectableImageReader.reset();
        ShadowCallbackCameraCaptureSession.reset();
        readersBefore = ShadowInjectableImageReader.openReaderCount();
        ShadowPausedAsyncTask.overrideExecutor(writes::add);
        context = RuntimeEnvironment.getApplication();
        CaptureDelivery.allowDngInMediaStore();
    }

    @After
    public void tearDown() {
        if (module == null) return;
        module.close();
        FakeCamera2.idleMainLooper();
        runWrites();
        cameraView.release();
    }

    private Camera2Module givenACamera(FakeCamera2.RawSupport rawSupport) {
        FakeCamera2.addBackAndFrontCameras(context, rawSupport);
        return givenAView();
    }

    private Camera2Module givenAView() {
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        cameraView.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
            @Override public void onImageConfirmation() {}

            @Override public void onImageCaptured(File file) {
                reports.incrementAndGet();
                captured.set(file);
            }

            @Override public void onImageSaved(@NonNull Uri uri) {
                saved.add(uri);
            }

            @Override public void onFailure() {
                reports.incrementAndGet();
                failures.incrementAndGet();
            }
        });
        module = new Camera2Module(cameraView);
        return module;
    }

    @Test
    public void aRawCapableCameraOffersRawAndRawJpeg() {
        Camera2Module module = givenACamera(FakeCamera2.RawSupport.FULL);

        assertTrue(module.isPictureFormatSupported(CameraView.PictureFormat.JPEG));
        assertTrue(module.isPictureFormatSupported(CameraView.PictureFormat.RAW));
        assertTrue(module.isPictureFormatSupported(CameraView.PictureFormat.RAW_JPEG));
    }

    /** The capability alone is a promise with no stream to keep it. */
    @Test
    public void theCapabilityWithoutTheFormatIsNotEnough() {
        Camera2Module module = givenACamera(FakeCamera2.RawSupport.CAPABILITY_ONLY);

        assertFalse(module.isPictureFormatSupported(CameraView.PictureFormat.RAW));
        assertFalse(module.isPictureFormatSupported(CameraView.PictureFormat.RAW_JPEG));
    }

    /** And the stream alone has nothing published to interpret it by. */
    @Test
    public void theFormatWithoutTheCapabilityIsNotEnough() {
        Camera2Module module = givenACamera(FakeCamera2.RawSupport.FORMAT_ONLY);

        assertFalse(module.isPictureFormatSupported(CameraView.PictureFormat.RAW));
        assertFalse(module.isPictureFormatSupported(CameraView.PictureFormat.RAW_JPEG));
    }

    /** A camera with neither says no, and asking anyway keeps the format it can write. */
    @Test
    public void aCameraWithoutRawKeepsJpeg() {
        Camera2Module module = givenACamera(FakeCamera2.RawSupport.NONE);

        module.setPictureFormat(CameraView.PictureFormat.RAW);

        assertFalse(module.isPictureFormatSupported(CameraView.PictureFormat.RAW));
        assertEquals(CameraView.PictureFormat.JPEG, module.getPictureFormat());
    }

    /** Characteristics the camera cannot report are a camera that cannot write RAW. */
    @Test
    public void unreadableCharacteristicsAreNoRaw() {
        assertFalse(Camera2Module.writesRaw(null));
        assertFalse(Camera2Module.writesRaw(FakeCamera2.characteristics(
                CameraMetadata.LENS_FACING_BACK, 90, CameraMetadata.INFO_DEVICE_TYPE_BUILT_IN,
                true, CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
                FakeCamera2.RawSupport.CAPABILITY_ONLY)));
    }

    /**
     * A RAW session carries a RAW_SENSOR reader at the sensor's size, beside the JPEG one.
     *
     * <p>The fake lists a larger remosaic size first, which DngCreator would refuse.</p>
     */
    @Test
    public void aRawSessionConfiguresASensorSizedReader() {
        Camera2Module module = givenACamera(FakeCamera2.RawSupport.FULL);
        module.setPictureFormat(CameraView.PictureFormat.RAW_JPEG);

        module.open();
        FakeCamera2.idleMainLooper();

        ImageReader raw = ShadowInjectableImageReader.latestReader(ImageFormat.RAW_SENSOR);
        assertNotNull("expected a RAW reader, found "
                + ShadowInjectableImageReader.describeOpenReaders(), raw);
        assertEquals(FakeCamera2.SIZE_RAW.getWidth(), raw.getWidth());
        assertEquals(FakeCamera2.SIZE_RAW.getHeight(), raw.getHeight());
        assertNotNull("RAW_JPEG needs the JPEG reader too",
                ShadowInjectableImageReader.latestReader(ImageFormat.JPEG));
    }

    /** A JPEG session on the same camera pays for no RAW reader. */
    @Test
    public void aJpegSessionConfiguresNoRawReader() {
        Camera2Module module = givenACamera(FakeCamera2.RawSupport.FULL);

        module.open();
        FakeCamera2.idleMainLooper();

        assertNull(ShadowInjectableImageReader.latestReader(ImageFormat.RAW_SENSOR));
    }

    /** The RAW reader belongs to the session, so asking for RAW rebuilds it. */
    @Test
    public void changingTheFormatRebuildsTheSession() {
        Camera2Module module = givenACamera(FakeCamera2.RawSupport.FULL);
        module.open();
        FakeCamera2.idleMainLooper();

        module.setPictureFormat(CameraView.PictureFormat.RAW);
        FakeCamera2.idleMainLooper();

        assertNotNull(ShadowInjectableImageReader.latestReader(ImageFormat.RAW_SENSOR));
    }

    /**
     * A capture asked for straight after a format change waits for that format's session.
     *
     * <p>The camera configures the new session on its own schedule, so at the moment of the call
     * the session that can honour it does not exist yet. Failing there would be a failure the
     * caller could neither see the reason for nor avoid.</p>
     */
    @Test
    public void aCaptureWaitsForTheSessionItsFormatNeeds() {
        Camera2Module module = givenACamera(FakeCamera2.RawSupport.FULL);
        module.open();
        FakeCamera2.idleMainLooper();
        int capturesBefore = ShadowCallbackCameraCaptureSession.getCompletedCaptureCount();
        File output = new File(context.getCacheDir(), "waited.dng");

        module.setPictureFormat(CameraView.PictureFormat.RAW);
        module.takePicture(output);

        assertEquals("waiting is not failing", 0, reports.get());
        FakeCamera2.idleMainLooper();
        assertTrue("the capture should have gone to the new session",
                ShadowCallbackCameraCaptureSession.getCompletedCaptureCount() > capturesBefore);
        deliverRawFrame();
        assertEquals("the frame should be on its way to disk", 1, writes.size());
    }

    /** With no session coming, a capture after a format change is answered at once. */
    @Test
    public void aCaptureAfterAFormatChangeOnAClosedCameraFailsAtOnce() {
        Camera2Module module = givenACamera(FakeCamera2.RawSupport.FULL);

        module.setPictureFormat(CameraView.PictureFormat.RAW);
        FakeCamera2.idleMainLooper();
        module.takePicture(new File(context.getCacheDir(), "closed.dng"));

        assertEquals(1, failures.get());
    }

    /** A frame is paired with the result carrying its timestamp, and only then written. */
    @Test
    public void aRawFrameIsWrittenOnceItsResultArrives() {
        Camera2Module module = givenACamera(FakeCamera2.RawSupport.FULL);
        module.setPictureFormat(CameraView.PictureFormat.RAW);
        module.open();
        FakeCamera2.idleMainLooper();

        module.takePicture(new File(context.getCacheDir(), "paired.dng"));
        FakeCamera2.idleMainLooper();
        assertTrue("the result is in, the frame is not", writes.isEmpty());

        FakeImage frame = deliverRawFrame();
        assertEquals(1, writes.size());
        assertFalse("the writer owns the frame now", frame.isClosed());

        runWrites();
        assertTrue("the frame goes back once the write is over", frame.isClosed());
        assertEquals("one shutter press, one answer", 1, reports.get());
    }

    /** A frame from another exposure is not written with this capture's metadata. */
    @Test
    public void aFrameFromAnotherExposureIsNotPaired() {
        Camera2Module module = givenACamera(FakeCamera2.RawSupport.FULL);
        module.setPictureFormat(CameraView.PictureFormat.RAW);
        module.open();
        FakeCamera2.idleMainLooper();
        module.takePicture(new File(context.getCacheDir(), "unpaired.dng"));
        FakeCamera2.idleMainLooper();

        ImageReader raw = ShadowInjectableImageReader.latestReader(ImageFormat.RAW_SENSOR);
        assertNotNull(raw);
        ShadowInjectableImageReader.offerImage(raw, FakeImage.raw(8, 8,
                ShadowCallbackCameraCaptureSession.getLastSensorTimestamp() + 1));
        FakeCamera2.idleMainLooper();

        assertTrue(writes.isEmpty());
    }

    /**
     * Closing the session while a DNG is being written keeps the reader until the write is done.
     *
     * <p>This is the crash. Closing an ImageReader frees the buffers behind the frames it handed
     * out, and DngCreator reading one that was freed takes the process down.</p>
     */
    @Test
    public void aSessionClosedMidWriteKeepsTheReaderUntilTheFrameIsBack() {
        Camera2Module module = givenACamera(FakeCamera2.RawSupport.FULL);
        module.setPictureFormat(CameraView.PictureFormat.RAW);
        module.open();
        FakeCamera2.idleMainLooper();
        module.takePicture(new File(context.getCacheDir(), "midwrite.dng"));
        FakeCamera2.idleMainLooper();
        FakeImage frame = deliverRawFrame();
        assertEquals(1, writes.size());

        module.close();
        FakeCamera2.idleMainLooper();

        assertNotNull("the RAW reader has to outlive the write, found "
                        + ShadowInjectableImageReader.describeOpenReaders(),
                ShadowInjectableImageReader.latestReader(ImageFormat.RAW_SENSOR));
        assertFalse(frame.isClosed());

        runWrites();

        assertTrue(frame.isClosed());
        assertNull("and go once the frame is back",
                ShadowInjectableImageReader.latestReader(ImageFormat.RAW_SENSOR));
        assertEquals(readersBefore, ShadowInjectableImageReader.openReaderCount());
    }

    /**
     * RAW_JPEG is reported once, after both files, so both are on disk when the caller hears.
     */
    @Test
    public void rawJpegIsReportedAfterBothFiles() {
        Camera2Module module = givenACamera(FakeCamera2.RawSupport.FULL);
        module.setPictureFormat(CameraView.PictureFormat.RAW_JPEG);
        module.open();
        FakeCamera2.idleMainLooper();
        File output = new File(context.getCacheDir(), "both.jpg");

        module.takePicture(output);
        FakeCamera2.idleMainLooper();
        ImageReader jpeg = ShadowInjectableImageReader.latestReader(ImageFormat.JPEG);
        assertNotNull(jpeg);
        ShadowInjectableImageReader.offerImage(jpeg, FakeImage.jpeg(1920, 1080, JPEG_BYTES));
        FakeCamera2.idleMainLooper();
        runWrites();

        assertEquals("the DNG is not written yet", 0, reports.get());
        assertTrue("the JPEG is", output.isFile());

        deliverRawFrame();
        runWrites();

        assertEquals(1, reports.get());
    }

    /**
     * A switch to a lens that cannot write RAW keeps the format but configures no RAW output, and
     * a capture in it is refused rather than written as a JPEG the caller did not ask for.
     */
    @Test
    public void aLensWithoutRawRefusesARawCapture() {
        FakeCamera2.addCamera(context, FakeCamera2.BACK_ID, CameraMetadata.LENS_FACING_BACK, 90,
                CameraMetadata.INFO_DEVICE_TYPE_BUILT_IN, true,
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL, FakeCamera2.RawSupport.FULL);
        FakeCamera2.addCamera(context, FakeCamera2.FRONT_ID, CameraMetadata.LENS_FACING_FRONT,
                270, CameraMetadata.INFO_DEVICE_TYPE_BUILT_IN, true,
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL, FakeCamera2.RawSupport.NONE);
        Camera2Module module = givenAView();
        module.setPictureFormat(CameraView.PictureFormat.RAW);
        module.open();
        FakeCamera2.idleMainLooper();
        File output = new File(context.getCacheDir(), "refused.dng");

        module.setLensFacing(CameraView.LensFacing.FRONT);
        FakeCamera2.idleMainLooper();
        module.takePicture(output);
        FakeCamera2.idleMainLooper();

        assertFalse(module.isPictureFormatSupported(CameraView.PictureFormat.RAW));
        assertNull(ShadowInjectableImageReader.latestReader(ImageFormat.RAW_SENSOR));
        assertEquals(1, failures.get());
        assertTrue(writes.isEmpty());
        assertFalse(output.exists());
    }

    /** The RAW reader holds native buffers, so a closed camera leaves none behind. */
    @Test
    public void theRawReaderIsReleasedWithTheCamera() {
        Camera2Module module = givenACamera(FakeCamera2.RawSupport.FULL);
        module.setPictureFormat(CameraView.PictureFormat.RAW);
        module.open();
        FakeCamera2.idleMainLooper();

        module.close();
        FakeCamera2.idleMainLooper();

        assertEquals("leaked: " + ShadowInjectableImageReader.describeOpenReaders(),
                readersBefore, ShadowInjectableImageReader.openReaderCount());
    }

    /** A RAW picture taken without a file is filed in the gallery as the DNG it is. */
    @Test
    public void aRawPictureWithoutAFileIsFiledAsADng() throws Exception {
        Camera2Module module = givenACamera(FakeCamera2.RawSupport.FULL);
        module.setPictureFormat(CameraView.PictureFormat.RAW);
        module.open();
        FakeCamera2.idleMainLooper();
        File output = CaptureDelivery.ownPictureFile(cameraView, CameraView.PictureFormat.RAW);
        assertTrue("a RAW capture is never cached as a JPEG", output.getName().endsWith(".dng"));

        module.takePicture(output);
        FakeCamera2.idleMainLooper();
        deliverRawFrame();
        runWrites();
        awaitFiling();

        assertEquals(0, failures.get());
        assertEquals(1, saved.size());
        List<ShadowContentResolver.InsertStatement> inserts = resolver().getInsertStatements();
        assertEquals(1, inserts.size());
        ContentValues row = inserts.get(0).getContentValues();
        assertEquals(output.getName(), row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        assertEquals("image/x-adobe-dng", row.getAsString(MediaStore.MediaColumns.MIME_TYPE));
        assertNoCacheFiles();
    }

    /**
     * RAW_JPEG taken without a file files both under one name and is heard twice, the JPEG
     * first, however the two writes finish.
     */
    @Test
    public void aRawJpegPictureWithoutAFileFilesBothTheJpegFirst() throws Exception {
        Camera2Module module = givenACamera(FakeCamera2.RawSupport.FULL);
        module.setPictureFormat(CameraView.PictureFormat.RAW_JPEG);
        module.open();
        FakeCamera2.idleMainLooper();
        File output =
                CaptureDelivery.ownPictureFile(cameraView, CameraView.PictureFormat.RAW_JPEG);

        module.takePicture(output);
        FakeCamera2.idleMainLooper();
        // The DNG is written before the JPEG, the reverse of the order they are heard in.
        deliverRawFrame();
        runWrites();
        ImageReader jpeg = ShadowInjectableImageReader.latestReader(ImageFormat.JPEG);
        assertNotNull(jpeg);
        ShadowInjectableImageReader.offerImage(jpeg, FakeImage.jpeg(1920, 1080, JPEG_BYTES));
        FakeCamera2.idleMainLooper();
        runWrites();
        awaitFiling();

        assertEquals(0, failures.get());
        assertEquals(2, saved.size());
        List<ShadowContentResolver.InsertStatement> inserts = resolver().getInsertStatements();
        assertEquals(2, inserts.size());
        ContentValues first = inserts.get(0).getContentValues();
        ContentValues second = inserts.get(1).getContentValues();
        assertEquals(output.getName(), first.getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        assertEquals("image/jpeg", first.getAsString(MediaStore.MediaColumns.MIME_TYPE));
        String base = output.getName().substring(0, output.getName().lastIndexOf('.'));
        assertEquals(base + ".dng", second.getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        assertEquals("image/x-adobe-dng", second.getAsString(MediaStore.MediaColumns.MIME_TYPE));
        assertArrayEquals("the JPEG is heard first", JPEG_BYTES, read(saved.get(0)));
        assertNoCacheFiles();
    }

    /** A RAW_JPEG picture that loses its DNG is a failure, and files neither half. */
    @Test
    public void aRawJpegPictureWithoutAFileThatLosesItsDngFilesNeither() throws Exception {
        Camera2Module module = givenACamera(FakeCamera2.RawSupport.FULL);
        module.setPictureFormat(CameraView.PictureFormat.RAW_JPEG);
        module.open();
        FakeCamera2.idleMainLooper();
        File output =
                CaptureDelivery.ownPictureFile(cameraView, CameraView.PictureFormat.RAW_JPEG);

        module.takePicture(output);
        FakeCamera2.idleMainLooper();
        ImageReader jpeg = ShadowInjectableImageReader.latestReader(ImageFormat.JPEG);
        assertNotNull(jpeg);
        ShadowInjectableImageReader.offerImage(jpeg, FakeImage.jpeg(1920, 1080, JPEG_BYTES));
        FakeCamera2.idleMainLooper();
        runWrites();
        assertTrue("the JPEG is on disc", output.isFile());
        // The camera closes before the RAW frame arrives, so the DNG is never written.
        module.close();
        FakeCamera2.idleMainLooper();
        runWrites();

        assertEquals(1, failures.get());
        assertEquals(0, saved.size());
        assertTrue("nothing reaches MediaStore", resolver().getInsertStatements().isEmpty());
        assertNoCacheFiles();
    }

    private ShadowContentResolver resolver() {
        return Shadows.shadowOf(context.getContentResolver());
    }

    private byte[] read(Uri uri) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            assertNotNull("nothing at " + uri, input);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[64];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    /** Asserts takePicture()'s cache directory holds nothing once a capture is filed or lost. */
    private void assertNoCacheFiles() {
        File[] left = CaptureDelivery.captureDirectory(cameraView).listFiles();
        assertTrue("left in the cache: " + Arrays.toString(left),
                left == null || left.length == 0);
    }

    /** Runs the main looper until filing, which has a thread of its own, has been heard. */
    private void awaitFiling() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (saved.isEmpty() && failures.get() == 0 && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper();
            Thread.sleep(10);
        }
        assertTrue("the picture was never filed", !saved.isEmpty() || failures.get() > 0);
    }

    /** Hands the RAW reader the frame for the last capture, with that capture's timestamp. */
    private FakeImage deliverRawFrame() {
        ImageReader raw = ShadowInjectableImageReader.latestReader(ImageFormat.RAW_SENSOR);
        assertNotNull("no RAW reader in " + ShadowInjectableImageReader.describeOpenReaders(),
                raw);
        FakeImage frame = FakeImage.raw(8, 8,
                ShadowCallbackCameraCaptureSession.getLastSensorTimestamp());
        ShadowInjectableImageReader.offerImage(raw, frame);
        FakeCamera2.idleMainLooper();
        return frame;
    }

    /** Runs the writes handed to AsyncTask, and the reports they post back. */
    private void runWrites() {
        while (!writes.isEmpty()) {
            writes.remove(0).run();
            FakeCamera2.idleMainLooper();
        }
    }
}
