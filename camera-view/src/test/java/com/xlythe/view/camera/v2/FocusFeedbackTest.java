package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;

import androidx.annotation.NonNull;

import com.xlythe.view.camera.CameraView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * What an app is told about a focus request, as the Camera2 backend reports it.
 *
 * <p>The library draws no focus affordance of its own, so an app draws one and needs three things
 * to draw it honestly: that the camera took the request, where it was aimed in the view's own
 * coordinates, and what the camera made of it. The last is the one worth testing hardest - a
 * reticle that confirms a lock nobody reported is a lie, and it is the easy thing to build by
 * accident from the touch event alone.</p>
 *
 * <p>Focus states are built rather than captured, for the reason {@code FaceDetectionTest} gives:
 * Robolectric runs no pipeline, so what is under test is the translation.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class FocusFeedbackTest {
    /** The whole focus area, as a tap at the middle of the view produces one. */
    private static final Rect FOCUS = new Rect(-100, -100, 100, 100);

    private static final Rect METERING = new Rect(-200, -200, 200, 200);

    private static final int VIEW_SIZE = 1000;

    private Context context;
    private FakeCamera2.TestCameraView cameraView;
    private Camera2Module module;

    private final List<RectF> requested = new ArrayList<>();
    private final List<Boolean> completed = new ArrayList<>();

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        FakeCamera2.addBackAndFrontCameras(context);
        cameraView = FakeCamera2.laidOutCameraView(context, VIEW_SIZE, VIEW_SIZE);
        module = new Camera2Module(cameraView);
        cameraView.setFocusListener(new CameraView.FocusListener() {
            @Override
            public void onFocusRequested(@NonNull RectF area) {
                requested.add(area);
            }

            @Override
            public void onFocusCompleted(@NonNull RectF area, boolean locked) {
                completed.add(locked);
            }
        });
    }

    @After
    public void tearDown() {
        if (module == null) return;
        module.close();
        FakeCamera2.idleMainLooper();
        cameraView.release();
    }

    /** A camera that publishes a focus region can be told where to look. */
    @Test
    public void aCameraWithFocusRegionsReportsThatItCanFocus() {
        assertTrue(module.isFocusSupported());
    }

    /**
     * The request is reported where it landed on the view, not in the camera's coordinates.
     *
     * <p>The fake back camera is mounted at 90 degrees, so the two spaces are a quarter turn
     * apart; an area at the middle of the sensor is still the middle of the view, and an app
     * drawing at what it is given does not have to know that.</p>
     */
    @Test
    public void aFocusRequestIsReportedWhereItLandsOnTheView() {
        module.open();
        FakeCamera2.idleMainLooper();

        module.focus(new Rect(FOCUS), new Rect(METERING));
        FakeCamera2.idleMainLooper();

        assertEquals(1, requested.size());
        RectF area = requested.get(0);
        assertEquals(VIEW_SIZE / 2f, area.centerX(), 1f);
        assertEquals(VIEW_SIZE / 2f, area.centerY(), 1f);
        assertTrue("an area to draw around, not a point: " + area, area.width() > 0);
        assertTrue("and nothing is reported before the camera answers", completed.isEmpty());
    }

    /** A camera with no session has nothing to focus, so nothing is announced. */
    @Test
    public void aClosedCameraReportsNoRequest() {
        module.focus(new Rect(FOCUS), new Rect(METERING));
        FakeCamera2.idleMainLooper();

        assertTrue("a request the camera never took must not be reported", requested.isEmpty());
        assertTrue(completed.isEmpty());
    }

    /**
     * Both of the states that mean the lens has stopped moving answer the request.
     *
     * <p>The preview runs continuous autofocus, so a tap moves the region the camera is already
     * converging on and the result arrives as a passive state rather than the locked one a
     * triggered focus would give. A backend that only watched for the locked states would leave
     * every tap-to-focus unanswered on a real camera.</p>
     */
    @Test
    public void theCameraConvergingIsWhatConfirmsTheFocus() {
        givenAFocusRequest();

        module.onFocusCaptured(afState(CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN));
        FakeCamera2.idleMainLooper();
        assertTrue("a lens still moving is not an answer", completed.isEmpty());

        module.onFocusCaptured(afState(CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED));
        FakeCamera2.idleMainLooper();
        assertEquals(List.of(true), completed);
    }

    @Test
    public void aLockedFocusIsConfirmed() {
        givenAFocusRequest();

        module.onFocusCaptured(afState(CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED));
        FakeCamera2.idleMainLooper();

        assertEquals(List.of(true), completed);
    }

    /** A camera that gave up is reported as having given up, rather than left to time out. */
    @Test
    public void aFocusThatFailedIsReportedAsFailed() {
        givenAFocusRequest();

        module.onFocusCaptured(afState(CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED));
        FakeCamera2.idleMainLooper();

        assertEquals(List.of(false), completed);
    }

    @Test
    public void aPassiveSearchThatFoundNothingIsReportedAsFailed() {
        givenAFocusRequest();

        module.onFocusCaptured(afState(CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED));
        FakeCamera2.idleMainLooper();

        assertEquals(List.of(false), completed);
    }

    /**
     * Every request is answered once, and nothing else is.
     *
     * <p>A camera reports its focus state with every frame it completes, whether or not anything
     * asked it to, so an affordance driven by these callbacks would otherwise be told that a
     * focus finished over and over for as long as the preview ran.</p>
     */
    @Test
    public void aRequestIsAnsweredExactlyOnce() {
        givenAFocusRequest();

        module.onFocusCaptured(afState(CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED));
        module.onFocusCaptured(afState(CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED));
        module.onFocusCaptured(afState(CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED));
        FakeCamera2.idleMainLooper();

        assertEquals(List.of(true), completed);
    }

    /** A focus state arriving with nothing outstanding is not an answer to anything. */
    @Test
    public void aStateReportedWithNoRequestOutstandingIsIgnored() {
        module.open();
        FakeCamera2.idleMainLooper();

        module.onFocusCaptured(afState(CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED));
        FakeCamera2.idleMainLooper();

        assertTrue(completed.isEmpty());
    }

    /**
     * A camera that publishes no focus state at all leaves the request to time out.
     *
     * <p>Which is the honest outcome: nothing converged as far as anyone can tell, and an app
     * holding a reticle has to be let go of it either way.</p>
     */
    @Test
    public void aCameraThatNeverAnswersTimesOutAsUnconverged() {
        givenAFocusRequest();

        TotalCaptureResult silent = mock(TotalCaptureResult.class);
        when(silent.get(CaptureResult.CONTROL_AF_STATE)).thenReturn(null);
        module.onFocusCaptured(silent);
        FakeCamera2.idleMainLooper();
        assertTrue("nothing to report yet", completed.isEmpty());

        ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(5));

        assertEquals("the request still has to be answered", List.of(false), completed);
    }

    /** A second request supersedes the first rather than queueing behind it. */
    @Test
    public void aSecondRequestReplacesTheFirst() {
        givenAFocusRequest();

        module.focus(new Rect(-800, -800, -600, -600), new Rect(-900, -900, -500, -500));
        FakeCamera2.idleMainLooper();
        assertEquals(2, requested.size());
        assertFalse("the two were aimed at different places",
                requested.get(0).equals(requested.get(1)));

        module.onFocusCaptured(afState(CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED));
        FakeCamera2.idleMainLooper();

        assertEquals("one answer, for the request that is still outstanding",
                List.of(true), completed);
    }

    private void givenAFocusRequest() {
        module.open();
        FakeCamera2.idleMainLooper();
        module.focus(new Rect(FOCUS), new Rect(METERING));
        FakeCamera2.idleMainLooper();
        assertEquals("the camera has to have taken the request", 1, requested.size());
    }

    private static TotalCaptureResult afState(int state) {
        TotalCaptureResult result = mock(TotalCaptureResult.class);
        when(result.get(CaptureResult.CONTROL_AF_STATE)).thenReturn(state);
        return result;
    }
}
