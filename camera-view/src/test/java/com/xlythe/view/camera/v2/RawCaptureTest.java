package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.ImageFormat;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.shadow.ShadowCallbackCameraCaptureSession;
import com.xlythe.view.camera.shadow.ShadowInjectableImageReader;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import androidx.exifinterface.media.ExifInterface;

/**
 * RAW still capture on the Camera2 backend.
 *
 * <p>What can be asserted without a sensor is the honesty of the answer and the shape of the
 * session: that the format is offered only when the camera makes both of the claims that make it
 * possible, that a session asked for RAW really configures a full-sensor RAW_SENSOR reader
 * alongside its JPEG one, and that a session which cannot refuses rather than quietly writing a
 * JPEG. Writing the DNG itself needs DngCreator, which needs a camera.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, qualifiers = "w360dp-h800dp-xxxhdpi", shadows = {
        ShadowCallbackCameraCaptureSession.class,
        ShadowInjectableImageReader.class})
public class RawCaptureTest {
    private Context context;
    private FakeCamera2.TestCameraView cameraView;
    private Camera2Module module;
    private final AtomicReference<File> captured = new AtomicReference<>();
    private final AtomicBoolean failed = new AtomicBoolean();

    /**
     * How many readers were already open when this test started.
     *
     * <p>Robolectric reuses a sandbox across test classes with the same configuration, so the
     * registry is not empty on the way in, and what this class is counting is the readers one
     * session adds rather than the number in existence.</p>
     */
    private int readersBefore;

    /**
     * RAW is switched off for applications, and switched on here.
     *
     * <p>It crashes the process on a device - the DNG write outlives the ImageReader that owns
     * the frame - so no app may ask for it yet. None of that happens against a fake camera,
     * which frees nothing, so the machinery stays covered while it waits for the fix.</p>
     */
    @Before
    public void enableRawForTheseTests() {
        Camera2Module.sRawCaptureEnabled = true;
    }

    @After
    public void disableRawAgain() {
        Camera2Module.sRawCaptureEnabled = false;
    }

    @Before
    public void setUp() {
        readersBefore = ShadowInjectableImageReader.openReaderCount();
        context = RuntimeEnvironment.getApplication();
    }

    @After
    public void tearDown() {
        if (module == null) return;
        module.close();
        FakeCamera2.idleMainLooper();
        cameraView.release();
    }

    private Camera2Module givenACamera(FakeCamera2.RawSupport rawSupport) {
        FakeCamera2.addBackAndFrontCameras(context, rawSupport);
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        cameraView.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
            @Override public void onImageConfirmation() {}
            @Override public void onImageCaptured(File file) { captured.set(file); }
            @Override public void onFailure() { failed.set(true); }
        });
        module = new Camera2Module(cameraView);
        return module;
    }

    /** A camera that makes both claims can be asked for a DNG, with or without a JPEG beside it. */
    @Test
    public void aRawCapableCameraOffersRaw() {
        Camera2Module module = givenACamera(FakeCamera2.RawSupport.FULL);

        assertTrue(module.isPictureFormatSupported(CameraView.PictureFormat.JPEG));
        assertTrue(module.isPictureFormatSupported(CameraView.PictureFormat.RAW));
        assertTrue(module.isPictureFormatSupported(CameraView.PictureFormat.RAW_JPEG));
    }

    /**
     * And Ultra HDR is still refused on this backend, however capable the camera is.
     *
     * <p>Its gain map is composed from exposures CameraX takes for the purpose. Camera2 has no
     * equivalent, and a JPEG written without the gain map while claiming the format would be
     * indistinguishable to the caller from one with it.</p>
     */
    @Test
    public void ultraHdrIsStillRefused() {
        Camera2Module module = givenACamera(FakeCamera2.RawSupport.FULL);

        assertFalse(module.isPictureFormatSupported(CameraView.PictureFormat.ULTRA_HDR));
    }

    /** The capability alone is a promise the configuration cannot keep. */
    @Test
    public void theCapabilityWithoutTheFormatIsNotEnough() {
        Camera2Module module = givenACamera(FakeCamera2.RawSupport.CAPABILITY_ONLY);

        assertFalse(module.isPictureFormatSupported(CameraView.PictureFormat.RAW));
        assertFalse(module.isPictureFormatSupported(CameraView.PictureFormat.RAW_JPEG));
    }

    /** And the format alone is a stream with nothing published to interpret it by. */
    @Test
    public void theFormatWithoutTheCapabilityIsNotEnough() {
        Camera2Module module = givenACamera(FakeCamera2.RawSupport.FORMAT_ONLY);

        assertFalse(module.isPictureFormatSupported(CameraView.PictureFormat.RAW));
        assertFalse(module.isPictureFormatSupported(CameraView.PictureFormat.RAW_JPEG));
    }

    /** A camera that cannot write a DNG keeps the format it can write when asked for one. */
    @Test
    public void anUnsupportedFormatIsDeclinedRatherThanAccepted() {
        Camera2Module module = givenACamera(FakeCamera2.RawSupport.NONE);

        module.setPictureFormat(CameraView.PictureFormat.RAW);

        assertEquals(CameraView.PictureFormat.JPEG, module.getPictureFormat());
    }

    /** One that can, keeps the format it was asked for. */
    @Test
    public void askingForRawIsHonoured() {
        Camera2Module module = givenACamera(FakeCamera2.RawSupport.FULL);

        module.setPictureFormat(CameraView.PictureFormat.RAW);

        assertEquals(CameraView.PictureFormat.RAW, module.getPictureFormat());
    }

    /**
     * A RAW session really does carry a RAW_SENSOR reader at the sensor's own size.
     *
     * <p>The part that would otherwise fail silently: the format can be accepted while the
     * session that gets configured has no RAW output in it, and a caller would only find out
     * when the DNG never arrived.</p>
     */
    @Test
    public void aRawSessionConfiguresAFullSensorReader() {
        Camera2Module module = givenACamera(FakeCamera2.RawSupport.FULL);
        module.setPictureFormat(CameraView.PictureFormat.RAW);

        module.open();
        FakeCamera2.idleMainLooper();

        assertTrue("expected a full-sensor RAW reader, found "
                        + ShadowInjectableImageReader.describeOpenReaders(),
                hasRawReader());
        assertEquals("the JPEG reader stays: RAW_JPEG needs both, and the combination Camera2"
                        + " guarantees has both in it",
                2, ShadowInjectableImageReader.openReaderCount() - readersBefore);
    }

    /** A JPEG session on the same camera pays for no RAW reader at all. */
    @Test
    public void aJpegSessionConfiguresNoRawReader() {
        Camera2Module module = givenACamera(FakeCamera2.RawSupport.FULL);

        module.open();
        FakeCamera2.idleMainLooper();

        assertFalse(hasRawReader());
        assertEquals("the JPEG reader and nothing else",
                1, ShadowInjectableImageReader.openReaderCount() - readersBefore);
    }

    /** Asking for RAW on a running camera rebuilds the session, which is where the surface lives. */
    @Test
    public void changingTheFormatRebuildsTheSession() {
        Camera2Module module = givenACamera(FakeCamera2.RawSupport.FULL);
        module.open();
        FakeCamera2.idleMainLooper();
        assertFalse(hasRawReader());

        module.setPictureFormat(CameraView.PictureFormat.RAW);
        FakeCamera2.idleMainLooper();

        assertTrue("expected the new session to carry a RAW reader, found "
                        + ShadowInjectableImageReader.describeOpenReaders(),
                hasRawReader());
    }

    /**
     * A capture asked for straight after a format change waits for that format's session.
     *
     * <p>This asserted the opposite once, and documented the race as though it were the
     * contract. Changing the format rebuilds the session and the camera configures it on its
     * own schedule, so a caller who writes the two lines in the order they mean them used to
     * get a refusal they could neither see the reason for nor avoid. The capture is now held
     * until the session is configured, and dropped with a failure if it never is.</p>
     *
     * <p>A recording session is the other way to reach the refusal and still does: it carries
     * no RAW output, because Camera2 guarantees no combination with a preview, a recording and
     * a full-sensor RAW stream in it.</p>
     */
    @Test
    public void aCaptureWaitsForTheFormatChangeItFollows() {
        Camera2Module module = givenACamera(FakeCamera2.RawSupport.FULL);
        module.open();
        FakeCamera2.idleMainLooper();
        File output = new File(context.getCacheDir(), "refused.dng");

        module.setPictureFormat(CameraView.PictureFormat.RAW);
        module.takePicture(output);

        // Checked before the looper runs, which is the whole point: at this instant the session
        // that can take the picture does not exist, and the old behaviour reported a failure
        // here. Waiting is not the same as failing. What happens after the session configures
        // needs a camera, and RawCaptureDeviceTest is where that is asserted.
        assertFalse("a capture must not be refused for a session it could have waited for",
                failed.get());
        assertFalse("and nothing written in its place", output.exists());
    }

    /** The reader holds native buffers, so a closed camera must leave none of them behind. */
    @Test
    public void theRawReaderIsReleasedWithTheCamera() {
        Camera2Module module = givenACamera(FakeCamera2.RawSupport.FULL);
        module.setPictureFormat(CameraView.PictureFormat.RAW);
        module.open();
        FakeCamera2.idleMainLooper();
        assertTrue(hasRawReader());

        module.close();
        FakeCamera2.idleMainLooper();

        assertEquals("leaked readers: " + ShadowInjectableImageReader.describeOpenReaders(),
                readersBefore, ShadowInjectableImageReader.openReaderCount());
    }

    /**
     * The DNG is told which way up it is the same way the JPEG beside it is.
     *
     * <p>A DNG cannot be reopened and amended, so the orientation is worked out before the file
     * is written rather than applied to it afterwards. These are the values Exif's rotate
     * followed by flipHorizontally arrives at, which is what the JPEG path does.</p>
     */
    @Test
    public void theDngCarriesTheOrientationTheJpegWould() {
        assertEquals(ExifInterface.ORIENTATION_NORMAL,
                PictureSession.exifOrientation(0, false));
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90,
                PictureSession.exifOrientation(90, false));
        assertEquals(ExifInterface.ORIENTATION_ROTATE_180,
                PictureSession.exifOrientation(180, false));
        assertEquals(ExifInterface.ORIENTATION_ROTATE_270,
                PictureSession.exifOrientation(270, false));

        // A front-facing lens, where the flip is folded into the same tag.
        assertEquals(ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
                PictureSession.exifOrientation(0, true));
        assertEquals(ExifInterface.ORIENTATION_TRANSPOSE,
                PictureSession.exifOrientation(90, true));
        assertEquals(ExifInterface.ORIENTATION_FLIP_VERTICAL,
                PictureSession.exifOrientation(180, true));
        assertEquals(ExifInterface.ORIENTATION_TRANSVERSE,
                PictureSession.exifOrientation(270, true));
    }

    /** Angles outside one turn, and ones that are not right angles, still answer something sane. */
    @Test
    public void anOrientationIsAlwaysOneOfTheEight() {
        assertEquals("a full turn is no rotation", ExifInterface.ORIENTATION_NORMAL,
                PictureSession.exifOrientation(360, false));
        assertEquals("and a negative angle is the same turn the other way",
                ExifInterface.ORIENTATION_ROTATE_270, PictureSession.exifOrientation(-90, false));
        assertEquals("an angle no orientation can express reads as unrotated",
                ExifInterface.ORIENTATION_NORMAL, PictureSession.exifOrientation(45, false));
    }

    /** Whether any open reader is a full-sensor RAW one. */
    private static boolean hasRawReader() {
        return ShadowInjectableImageReader.describeOpenReaders().contains(
                FakeCamera2.SIZE_RAW.getWidth() + "x" + FakeCamera2.SIZE_RAW.getHeight()
                        + " format=" + ImageFormat.RAW_SENSOR);
    }
}
