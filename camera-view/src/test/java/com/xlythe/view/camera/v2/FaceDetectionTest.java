package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.Face;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The camera's own face detector, as the Camera2 backend exposes it.
 *
 * <p>The capture results are built rather than captured: Robolectric's shadows never run a real
 * pipeline, so what is under test is the translation and the coordinate mapping - that a face the
 * camera reported in sensor pixels becomes a rectangle on the view - rather than the camera
 * finding anything.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
public class FaceDetectionTest {
    private Context context;
    private FakeCamera2.TestCameraView cameraView;
    private Camera2Module module;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
    }

    /** Stands up a module on cameras that can, or cannot, report faces. */
    private Camera2Module givenCamera(boolean faceDetection) {
        FakeCamera2.addBackAndFrontCameras(context, faceDetection);
        cameraView = FakeCamera2.laidOutCameraView(context, 1000, 1000);
        module = new Camera2Module(cameraView);
        return module;
    }

    @After
    public void tearDown() {
        if (module == null) return;
        module.close();
        FakeCamera2.idleMainLooper();
        cameraView.release();
    }

    @Test
    public void aCameraThatReportsFaceModesSupportsDetection() {
        Camera2Module module = givenCamera(true);

        assertTrue(module.isFaceDetectionSupported());
        assertTrue("starting is accepted", module.setFaceDetectionEnabled(true));
        assertTrue("and stopping always is", module.setFaceDetectionEnabled(false));
    }

    /** A camera reporting no face modes refuses, rather than accepting and never reporting. */
    @Test
    public void aCameraWithoutFaceModesDeclines() {
        Camera2Module module = givenCamera(false);

        assertFalse(module.isFaceDetectionSupported());
        assertFalse(module.setFaceDetectionEnabled(true));
        assertTrue("stopping something that never started is not a failure",
                module.setFaceDetectionEnabled(false));
        assertFalse("and the view reports the refusal",
                cameraView.setFaceDetectionListener((faces, faceToView) -> {}));
    }

    /** CameraView reports the refusal, and does not keep a listener that will never be called. */
    @Test
    public void theViewReportsWhetherTheCameraTookTheListener() {
        Camera2Module module = givenCamera(true);
        AtomicInteger reports = new AtomicInteger();
        CameraView.FaceDetectionListener listener = (faces, faceToView) -> reports.incrementAndGet();

        assertTrue(cameraView.setFaceDetectionListener(listener));
        assertTrue(cameraView.isFaceDetectionSupported());

        assertTrue("removing a listener always succeeds",
                cameraView.setFaceDetectionListener(null));
        module.onFacesCaptured(resultWith(face(new Rect(0, 0, 100, 100), 90, 3)));
        FakeCamera2.idleMainLooper();
        assertEquals("a removed listener hears nothing", 0, reports.get());
    }

    @Test
    public void facesAreTranslatedWithTheirLandmarks() {
        Camera2Module module = givenCamera(true);
        List<Face> received = new ArrayList<>();
        cameraView.setFaceDetectionListener((faces, faceToView) -> received.addAll(faces));

        android.hardware.camera2.params.Face detected =
                face(new Rect(1000, 750, 2000, 1500), 85, 7);
        when(detected.getLeftEyePosition()).thenReturn(new Point(1200, 1000));
        when(detected.getRightEyePosition()).thenReturn(new Point(1800, 1000));
        when(detected.getMouthPosition()).thenReturn(new Point(1500, 1300));

        module.onFacesCaptured(resultWith(detected));
        FakeCamera2.idleMainLooper();

        assertEquals(1, received.size());
        Face face = received.get(0);
        assertEquals(new Rect(1000, 750, 2000, 1500), face.getBounds());
        assertEquals(85, face.getScore());
        assertEquals(7, face.getId());
        assertTrue(face.hasLandmarks());
        assertEquals(new Point(1200, 1000), face.getLeftEyePosition());
        assertEquals(new Point(1800, 1000), face.getRightEyePosition());
        assertEquals(new Point(1500, 1300), face.getMouthPosition());
        assertTrue(face.toString().contains("score=85"));
    }

    /** A camera in simple mode gives bounds and nothing else, which is not an error. */
    @Test
    public void aFaceWithoutLandmarksIsStillAFace() {
        Camera2Module module = givenCamera(true);
        List<Face> received = new ArrayList<>();
        cameraView.setFaceDetectionListener((faces, faceToView) -> received.addAll(faces));

        module.onFacesCaptured(resultWith(
                face(new Rect(0, 0, 400, 400), 50, Face.ID_UNKNOWN)));
        FakeCamera2.idleMainLooper();

        assertEquals(1, received.size());
        assertFalse(received.get(0).hasLandmarks());
        assertEquals(Face.ID_UNKNOWN, received.get(0).getId());
    }

    /**
     * The matrix maps sensor pixels onto the view.
     *
     * <p>The fake sensor's active array is 4000x3000 and the back camera is mounted at 90 degrees,
     * so a 1000x1000 view shows the middle of the array turned a quarter turn. What matters is
     * that a face in the center of the frame maps to the center of the view and that the mapping
     * stays inside it.</p>
     */
    @Test
    public void theMatrixPutsACenteredFaceInTheCenterOfTheView() {
        Camera2Module module = givenCamera(true);
        List<Matrix> transforms = new ArrayList<>();
        cameraView.setFaceDetectionListener((faces, faceToView) -> transforms.add(faceToView));

        module.onFacesCaptured(resultWith(face(new Rect(1900, 1400, 2100, 1600), 90, 1)));
        FakeCamera2.idleMainLooper();

        assertEquals(1, transforms.size());
        float[] center = {2000f, 1500f};
        transforms.get(0).mapPoints(center);
        assertEquals(500f, center[0], 1f);
        assertEquals(500f, center[1], 1f);
    }

    /** An empty result clears whatever was drawn once, then stops costing anything. */
    @Test
    public void anEmptyResultIsReportedOnceAndThenSkipped() {
        Camera2Module module = givenCamera(true);
        AtomicInteger reports = new AtomicInteger();
        cameraView.setFaceDetectionListener((faces, faceToView) -> reports.incrementAndGet());

        module.onFacesCaptured(resultWith(face(new Rect(0, 0, 100, 100), 90, 1)));
        FakeCamera2.idleMainLooper();
        assertEquals(1, reports.get());

        module.onFacesCaptured(resultWith());
        FakeCamera2.idleMainLooper();
        assertEquals("the camera losing the face has to be reported", 2, reports.get());

        module.onFacesCaptured(resultWith());
        module.onFacesCaptured(resultWith());
        FakeCamera2.idleMainLooper();
        assertEquals("but staying empty is not reported again", 2, reports.get());

        module.onFacesCaptured(resultWith(face(new Rect(0, 0, 100, 100), 90, 1)));
        FakeCamera2.idleMainLooper();
        assertEquals("and a face coming back is", 3, reports.get());
    }

    /** A result with no face metadata at all reads as no faces, not a crash. */
    @Test
    public void aResultWithoutFaceMetadataIsEmpty() {
        Camera2Module module = givenCamera(true);
        List<List<Face>> reports = new ArrayList<>();
        cameraView.setFaceDetectionListener((faces, faceToView) -> reports.add(faces));
        module.onFacesCaptured(resultWith(face(new Rect(0, 0, 100, 100), 90, 1)));
        FakeCamera2.idleMainLooper();

        TotalCaptureResult blank = mock(TotalCaptureResult.class);
        when(blank.get(CaptureResult.STATISTICS_FACES)).thenReturn(null);
        module.onFacesCaptured(blank);
        FakeCamera2.idleMainLooper();

        assertEquals(2, reports.size());
        assertTrue(reports.get(1).isEmpty());
    }

    private static android.hardware.camera2.params.Face face(Rect bounds, int score, int id) {
        android.hardware.camera2.params.Face face =
                mock(android.hardware.camera2.params.Face.class);
        when(face.getBounds()).thenReturn(bounds);
        when(face.getScore()).thenReturn(score);
        when(face.getId()).thenReturn(id);
        return face;
    }

    private static TotalCaptureResult resultWith(
            android.hardware.camera2.params.Face... faces) {
        TotalCaptureResult result = mock(TotalCaptureResult.class);
        when(result.get(CaptureResult.STATISTICS_FACES)).thenReturn(faces);
        assertNotNull(result);
        return result;
    }

    /** Guards against the characteristics drifting out from under the support check. */
    @Test
    public void theFakeCameraReportsTheModesTheCheckLooksFor() throws Exception {
        FakeCamera2.addBackAndFrontCameras(context);
        CameraCharacteristics characteristics = ((android.hardware.camera2.CameraManager)
                context.getSystemService(Context.CAMERA_SERVICE))
                .getCameraCharacteristics("0");
        int[] modes = characteristics.get(
                CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
        assertNotNull(modes);
        boolean full = false;
        for (int mode : modes) {
            full |= mode == CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL;
        }
        assertTrue("the richer mode is the one the backend prefers", full);
    }
}
