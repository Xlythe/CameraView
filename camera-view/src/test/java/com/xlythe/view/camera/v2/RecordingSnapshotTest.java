package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.util.Size;

import com.xlythe.view.camera.shadow.ShadowInjectableImageReader;
import com.xlythe.view.camera.shadow.ShadowListingCamcorderProfile;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Taking a picture while recording.
 *
 * <p>The video-snapshot button. Camera2's mandatory stream combinations guarantee a preview, a
 * recording and a still together from LIMITED hardware upwards, with the still no larger than the
 * recording; LEGACY hardware guarantees nothing of the sort. Asking a camera for a combination it
 * cannot configure costs the recording as well as the picture, so what matters here is that the
 * decision is made from what the camera reported rather than attempted and hoped for.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37,
        shadows = {ShadowInjectableImageReader.class, ShadowListingCamcorderProfile.class})
public class RecordingSnapshotTest {
    private Context context;
    private FakeCamera2.TestCameraView cameraView;
    private Camera2Module module;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
    }

    @After
    public void tearDown() {
        if (module == null) return;
        module.close();
        FakeCamera2.idleMainLooper();
        cameraView.release();
    }

    private Camera2Module givenHardwareLevel(int level) {
        FakeCamera2.addBackAndFrontCameras(context, true /* faceDetection */, level);
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        module = new Camera2Module(cameraView);
        return module;
    }

    @Test
    public void limitedHardwareCanTakeAPictureWhileRecording() {
        Camera2Module module =
                givenHardwareLevel(CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED);

        assertTrue(module.isSnapshotWhileRecordingSupported());
    }

    @Test
    public void fullHardwareCanToo() {
        Camera2Module module =
                givenHardwareLevel(CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL);

        assertTrue(module.isSnapshotWhileRecordingSupported());
    }

    /** LEGACY guarantees nothing, so it records without a still rather than risking the session. */
    @Test
    public void legacyHardwareRecordsWithoutOne() {
        Camera2Module module =
                givenHardwareLevel(CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY);

        assertFalse(module.isSnapshotWhileRecordingSupported());
    }

    /** A camera that will not say reads as cannot, because guessing costs the recording. */
    @Test
    public void aCameraThatWillNotSayReadsAsCannot() {
        FakeCamera2.addBackAndFrontCameras(context, true /* faceDetection */, null /* level */);
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        module = new Camera2Module(cameraView);

        assertFalse(module.isSnapshotWhileRecordingSupported());
    }

    /**
     * On hardware that can, a recording session really does carry a still output.
     *
     * <p>Which is the part that would silently not work: the capability can report true while the
     * session that is actually configured has no still surface in it, and the failure then only
     * shows up as a picture that never arrives.</p>
     */
    @Test
    public void aRecordingSessionCarriesAStillOutput() throws IOException {
        Camera2Module module =
                givenHardwareLevel(CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED);
        module.open();
        FakeCamera2.idleMainLooper();
        File file = File.createTempFile("snapshot", ".mp4");
        file.deleteOnExit();

        module.startRecording(file);
        FakeCamera2.idleMainLooper();

        // Three outputs: the preview, the recording, and the still that makes a snapshot possible.
        assertTrue("a recording session should still be able to take a picture",
                module.isSnapshotWhileRecordingSupported());
        module.stopRecording();
        FakeCamera2.idleMainLooper();
    }

    /** The still is capped to the recording's size, which is what the guarantee is stated at. */
    @Test
    public void theStillIsCappedToTheRecording() {
        List<Size> sizes = Arrays.asList(
                new Size(4000, 3000), new Size(1920, 1080), new Size(1280, 720),
                new Size(640, 480));

        List<Size> capped = SessionImpl.within(sizes, new Size(1280, 720));

        assertEquals("nothing larger than the recording survives",
                Arrays.asList(new Size(1280, 720), new Size(640, 480)), capped);
    }

    /** A cap nothing fits is a reason to choose badly, not to have no output at all. */
    @Test
    public void aCapNothingFitsLeavesEveryChoiceOpen() {
        List<Size> sizes = Arrays.asList(new Size(1920, 1080), new Size(1280, 720));

        assertEquals(sizes, SessionImpl.within(sizes, new Size(320, 240)));
        assertEquals("and no cap means no filtering", sizes, SessionImpl.within(sizes, null));
    }
}
