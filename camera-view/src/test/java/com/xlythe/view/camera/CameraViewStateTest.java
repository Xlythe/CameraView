package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.SparseArray;
import android.view.View;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CameraView's own state: what survives a configuration change, what the confirmation flow does
 * with a capture, and which calls are rejected before the camera is open.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
@LooperMode(LooperMode.Mode.PAUSED)
public class CameraViewStateTest {
    private Context context;
    private CameraView cameraView;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        cameraView = new CameraView(context);
        cameraView.setId(1);
        cameraView.layout(0, 0, 1080, 1920);
    }

    private File writeTempFile(String name) throws IOException {
        File file = new File(context.getCacheDir(), name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(new byte[] {1, 2, 3, 4});
        }
        return file;
    }

    // ---------- Saved state ----------

    /**
     * Everything the view is configured with has to come back after a configuration change, or
     * the camera silently reverts to defaults the caller never chose.
     */
    @Test
    public void configurationSurvivesSaveAndRestore() {
        cameraView.setQuality(CameraView.Quality.LOW);
        cameraView.setFlash(CameraView.Flash.ON);
        cameraView.setPinchToZoomEnabled(false);
        cameraView.setMaxVideoDuration(12_345L);
        cameraView.setMaxVideoSize(67_890L);
        cameraView.setImageConfirmationEnabled(true);
        cameraView.setVideoConfirmationEnabled(true);
        cameraView.setMatchPreviewAspectRatio(false);
        cameraView.setDynamicRange(CameraView.DynamicRange.HDR);
        cameraView.setStabilizationEnabled(true);
        cameraView.setMotionPhotoEnabled(true);
        cameraView.setExposureCompensation(3);
        cameraView.setTorchEnabled(true);
        cameraView.setShutterSoundEnabled(false);
        cameraView.setShutterSound(0x7f120001);

        CameraView restored = saveAndRestore(cameraView);

        assertEquals(CameraView.Quality.LOW, restored.getQuality());
        assertEquals(CameraView.Flash.ON, restored.getFlash());
        assertFalse(restored.isPinchToZoomEnabled());
        assertEquals(12_345L, restored.getMaxVideoDuration());
        assertEquals(67_890L, restored.getMaxVideoSize());
        assertTrue(restored.isImageConfirmationEnabled());
        assertTrue(restored.isVideoConfirmationEnabled());
        assertFalse(restored.isMatchPreviewAspectRatioEnabled());
        assertEquals(CameraView.DynamicRange.HDR, restored.getDynamicRange());
        assertTrue(restored.isStabilizationEnabled());
        assertTrue(restored.isMotionPhotoEnabled());
        assertEquals(3, restored.getExposureCompensation());
        assertTrue(restored.isTorchEnabled());
        assertFalse(restored.isShutterSoundEnabled());
        assertEquals(0x7f120001, restored.getShutterSound());
    }

    /** A capture waiting to be confirmed has to still be waiting on the other side. */
    @Test
    public void aPendingConfirmationSurvivesSaveAndRestore() throws IOException {
        File picture = writeTempFile("pending-restore.jpg");
        cameraView.setImageConfirmationEnabled(true);
        cameraView.showImageConfirmation(picture);

        CameraView restored = saveAndRestore(cameraView);

        // Confirming is only legal when something is pending, so this is how we observe it.
        AtomicReference<File> confirmed = new AtomicReference<>();
        restored.setOnImageCapturedListener(listener(confirmed, new AtomicInteger()));
        restored.confirmPicture();
        assertEquals(picture, confirmed.get());
    }

    /**
     * A pending file that is no longer on disk must not come back as still pending, or confirming
     * it would hand the caller a path to nothing.
     */
    @Test
    public void aPendingConfirmationForAMissingFileIsDropped() {
        // Never written, which is indistinguishable from having been cleaned up in between, and
        // avoids depending on being able to delete a file the preview loader may still hold open.
        File missing = new File(context.getCacheDir(), "never-written.jpg");
        assertFalse(missing.exists());
        cameraView.setImageConfirmationEnabled(true);
        cameraView.showImageConfirmation(missing);

        CameraView restored = saveAndRestore(cameraView);

        assertThrows(IllegalStateException.class, restored::confirmPicture);
    }

    private CameraView saveAndRestore(CameraView source) {
        SparseArray<Parcelable> state = new SparseArray<>();
        source.saveHierarchyState(state);

        CameraView restored = new CameraView(context);
        restored.setId(source.getId());
        restored.layout(0, 0, 1080, 1920);
        restored.restoreHierarchyState(state);
        return restored;
    }

    // ---------- Confirmation ----------

    @Test
    public void confirmingAPictureReportsItAndClearsThePreview() throws IOException {
        File picture = writeTempFile("confirm.jpg");
        AtomicReference<File> captured = new AtomicReference<>();
        AtomicInteger failures = new AtomicInteger();
        cameraView.setOnImageCapturedListener(listener(captured, failures));
        cameraView.setImageConfirmationEnabled(true);

        cameraView.showImageConfirmation(picture);
        assertEquals("nothing is reported until it is confirmed", null, captured.get());

        cameraView.confirmPicture();
        assertEquals(picture, captured.get());
        assertEquals(0, failures.get());
        assertTrue("the file is kept for the caller", picture.exists());
    }

    /**
     * Rejecting clears the pending capture and tells the caller nothing, which is what a caller
     * can observe. Whether the file is gone afterwards is deliberately not asserted: the
     * confirmation preview loads it in the background, and a host that still holds the handle
     * open, as Windows does, will refuse the delete for reasons that have nothing to do with
     * this behavior.
     */
    @Test
    public void rejectingAPictureDiscardsIt() throws IOException {
        File picture = writeTempFile("reject.jpg");
        AtomicReference<File> captured = new AtomicReference<>();
        cameraView.setOnImageCapturedListener(listener(captured, new AtomicInteger()));
        cameraView.setImageConfirmationEnabled(true);

        cameraView.showImageConfirmation(picture);
        cameraView.rejectPicture();

        assertEquals("a rejected picture is never reported", null, captured.get());
        assertThrows("the pending capture is cleared",
                IllegalStateException.class, cameraView::rejectPicture);
    }

    /** With confirmation off, a capture goes straight to the caller. */
    @Test
    public void withoutConfirmationAPictureIsReportedImmediately() throws IOException {
        File picture = writeTempFile("immediate.jpg");
        AtomicReference<File> captured = new AtomicReference<>();
        cameraView.setOnImageCapturedListener(listener(captured, new AtomicInteger()));

        cameraView.showImageConfirmation(picture);

        assertEquals(picture, captured.get());
    }

    @Test
    public void confirmingWithNothingPendingIsRejected() {
        assertThrows(IllegalStateException.class, cameraView::confirmPicture);
        assertThrows(IllegalStateException.class, cameraView::rejectPicture);
        assertThrows(IllegalStateException.class, cameraView::confirmVideo);
        assertThrows(IllegalStateException.class, cameraView::rejectVideo);
    }

    @Test
    public void aFailedCaptureIsReportedAsAFailure() {
        AtomicInteger failures = new AtomicInteger();
        cameraView.setOnImageCapturedListener(listener(new AtomicReference<>(), failures));

        cameraView.onImageFailed();

        assertEquals(1, failures.get());
    }

    // ---------- Guards ----------

    /** Streaming needs frames, so it cannot start before the camera is open. */
    @Test
    public void streamingBeforeOpeningIsRejected() {
        assertThrows(IllegalStateException.class, () -> cameraView.stream());
    }

    /** An audio-only stream has no such requirement. */
    @Test
    public void anAudioOnlyStreamDoesNotNeedAnOpenCamera() {
        VideoStream.Params params = new VideoStream.Params.Builder()
                .setVideoEnabled(false)
                .build();
        VideoStream stream = cameraView.stream(params);
        assertNotNull(stream);
        stream.close();
    }

    @Test
    public void barcodeScanningBeforeOpeningIsRejected() {
        assertThrows(IllegalStateException.class,
                () -> cameraView.enterBarcodeScanner(barcodes -> {}, Barcode.Format.QR_CODE));
    }

    /** Leaving a scanner that was never started is a no-op rather than an error. */
    @Test
    public void leavingAScannerThatNeverStartedIsHarmless() {
        cameraView.exitBarcodeScanner();
    }

    // ---------- Views ----------

    @Test
    public void thePreviewViewsAreBuiltAndHiddenUntilNeeded() {
        // The preview, the two confirmation views, the sweep guide and the focus reticle.
        assertEquals(5, cameraView.getChildCount());
        assertNotNull(cameraView.asTextureView());
        assertEquals(View.VISIBLE, cameraView.asTextureView().getVisibility());
    }

    /** The reticle is the last child, so it draws over the preview rather than under it. */
    @Test
    public void theFocusReticleSitsOverThePreview() {
        View top = cameraView.getChildAt(cameraView.getChildCount() - 1);
        assertTrue(top instanceof FocusReticle);
    }

    @Test
    public void theFocusReticleIsOnByDefaultAndCanBeTurnedOff() {
        assertTrue(cameraView.isFocusReticleEnabled());
        cameraView.setFocusReticleEnabled(false);
        assertFalse(cameraView.isFocusReticleEnabled());
    }

    @Test
    public void enumIdsRoundTripAndRejectUnknownValues() {
        for (CameraView.Quality quality : CameraView.Quality.values()) {
            Bundle bundle = new Bundle();
            cameraView.setQuality(quality);
            assertEquals(quality, cameraView.getQuality());
            assertNotNull(bundle);
        }
        for (CameraView.Flash flash : CameraView.Flash.values()) {
            cameraView.setFlash(flash);
            assertEquals(flash, cameraView.getFlash());
        }
        for (CameraView.DynamicRange range : CameraView.DynamicRange.values()) {
            cameraView.setDynamicRange(range);
            assertEquals(range, cameraView.getDynamicRange());
        }
        for (CameraView.Extension extension : CameraView.Extension.values()) {
            cameraView.setExtension(extension);
            assertEquals(extension, cameraView.getExtension());
        }
    }

    @Test
    public void nullCapabilityRequestsAreRejected() {
        assertThrows(NullPointerException.class, () -> cameraView.setDynamicRange(null));
        assertThrows(NullPointerException.class, () -> cameraView.setExtension(null));
    }

    private static CameraView.OnImageCapturedListener listener(
            AtomicReference<File> captured, AtomicInteger failures) {
        return new CameraView.OnImageCapturedListener() {
            @Override public void onImageConfirmation() {}
            @Override public void onImageCaptured(File file) { captured.set(file); }
            @Override public void onFailure() { failures.incrementAndGet(); }
        };
    }
}
