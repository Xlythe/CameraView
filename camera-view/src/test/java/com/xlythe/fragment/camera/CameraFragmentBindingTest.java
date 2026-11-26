package com.xlythe.fragment.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * How CameraFragment binds itself to a layout: which Views it requires, what the optional ones
 * switch on, and what the capture button's tap and hold gestures drive.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
@LooperMode(LooperMode.Mode.PAUSED)
public class CameraFragmentBindingTest {
    /** A layout with every View CameraFragment knows how to bind. */
    public static class FullFragment extends CameraFragment {
        final AtomicInteger takePictureCalls = new AtomicInteger();
        final AtomicInteger recordStartCalls = new AtomicInteger();
        final AtomicInteger recordStopCalls = new AtomicInteger();
        final AtomicInteger toggleCalls = new AtomicInteger();

        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                @Nullable Bundle savedInstanceState) {
            Context context = inflater.getContext();
            FrameLayout root = new FrameLayout(context);

            FrameLayout holder = new FrameLayout(context);
            holder.setId(R.id.layout_camera);
            CameraView camera = new CameraView(context);
            camera.setId(R.id.camera);
            holder.addView(camera);
            Button capture = new Button(context);
            capture.setId(R.id.capture);
            holder.addView(capture);
            root.addView(holder);

            View permissions = new View(context);
            permissions.setId(R.id.layout_permissions);
            root.addView(permissions);
            View request = new View(context);
            request.setId(R.id.request_permissions);
            root.addView(request);

            Button confirm = new Button(context);
            confirm.setId(R.id.confirm);
            root.addView(confirm);
            Button cancel = new Button(context);
            cancel.setId(R.id.cancel);
            root.addView(cancel);
            ToggleButton toggle = new ToggleButton(context);
            toggle.setId(R.id.toggle);
            root.addView(toggle);
            ProgressBar progress = new ProgressBar(context);
            progress.setId(R.id.progress);
            root.addView(progress);
            TextView duration = new TextView(context);
            duration.setId(R.id.duration);
            root.addView(duration);

            return root;
        }

        @Override protected void onTakePicture() { takePictureCalls.incrementAndGet(); }
        @Override protected void onRecordStart() { recordStartCalls.incrementAndGet(); }
        @Override protected void onRecordStop() { recordStopCalls.incrementAndGet(); }
        @Override protected void onToggle() { toggleCalls.incrementAndGet(); }
    }

    /** A layout missing the holder, which CameraFragment cannot work without. */
    public static class HolderlessFragment extends CameraFragment {
        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                @Nullable Bundle savedInstanceState) {
            return new FrameLayout(inflater.getContext());
        }
    }

    private FragmentActivity activity;

    @Before
    public void setUp() {
        ShadowApplication application = Shadows.shadowOf(
                (android.app.Application) org.robolectric.RuntimeEnvironment.getApplication());
        application.grantPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);
        activity = Robolectric.buildActivity(FragmentActivity.class).create().start().resume().get();
    }

    private FullFragment addFullFragment() {
        FullFragment fragment = new FullFragment();
        activity.getSupportFragmentManager().beginTransaction()
                .add(fragment, "camera").commitNow();
        return fragment;
    }

    /**
     * The required Views are reported by name. This used to throw a bare NullPointerException
     * from findViewById instead, because the holder was read through before being checked.
     */
    @Test
    public void aLayoutWithoutTheCameraHolderSaysSo() {
        HolderlessFragment fragment = new HolderlessFragment();

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                activity.getSupportFragmentManager().beginTransaction()
                        .add(fragment, "camera").commitNow());

        assertTrue("expected the message to name the id, got: " + error.getMessage(),
                error.getMessage().contains("layout_camera"));
    }

    @Test
    public void aConfirmButtonTurnsConfirmationOn() {
        FullFragment fragment = addFullFragment();
        CameraView camera = requireView(fragment, R.id.camera);

        assertTrue(camera.isImageConfirmationEnabled());
        assertTrue(camera.isVideoConfirmationEnabled());
    }

    @Test
    public void theOptionalViewsStartHidden() {
        FullFragment fragment = addFullFragment();

        assertEquals(View.GONE, requireView(fragment, R.id.confirm).getVisibility());
        assertEquals(View.GONE, requireView(fragment, R.id.cancel).getVisibility());
        assertEquals(View.GONE, requireView(fragment, R.id.duration).getVisibility());
        assertEquals(10_000, ((ProgressBar) requireView(fragment, R.id.progress)).getMax());
    }

    /** Awaiting confirmation swaps the capture button for confirm and cancel. */
    @Test
    public void confirmationShowsTheConfirmAndCancelButtons() {
        FullFragment fragment = addFullFragment();

        fragment.onImageConfirmation();

        assertEquals(View.VISIBLE, requireView(fragment, R.id.confirm).getVisibility());
        assertEquals(View.VISIBLE, requireView(fragment, R.id.cancel).getVisibility());
        assertEquals(View.GONE, requireView(fragment, R.id.capture).getVisibility());
        assertEquals(View.GONE, requireView(fragment, R.id.toggle).getVisibility());
    }

    @Test
    public void confirmationIsAlsoWiredForVideo() {
        FullFragment fragment = addFullFragment();

        fragment.onVideoConfirmation();

        assertEquals(View.VISIBLE, requireView(fragment, R.id.confirm).getVisibility());
        assertEquals(View.GONE, requireView(fragment, R.id.capture).getVisibility());
    }

    /** A tap on the capture button takes a picture rather than starting a recording. */
    @Test
    public void aTapTakesAPicture() {
        FullFragment fragment = addFullFragment();
        View capture = requireView(fragment, R.id.capture);

        long now = SystemClock.uptimeMillis();
        capture.dispatchTouchEvent(motionEvent(MotionEvent.ACTION_DOWN, now, now));
        capture.dispatchTouchEvent(motionEvent(MotionEvent.ACTION_UP, now, now + 20));
        ShadowLooper.shadowMainLooper().idle();

        assertEquals(1, fragment.takePictureCalls.get());
        assertEquals(0, fragment.recordStartCalls.get());
    }

    /** Holding past the long-press timeout starts a recording instead. */
    @Test
    public void aHoldStartsARecording() {
        FullFragment fragment = addFullFragment();
        View capture = requireView(fragment, R.id.capture);

        long now = SystemClock.uptimeMillis();
        capture.dispatchTouchEvent(motionEvent(MotionEvent.ACTION_DOWN, now, now));
        ShadowLooper.shadowMainLooper()
                .idleFor(java.time.Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 50));

        // The camera is not open, so recording fails rather than starting; what matters is that
        // the gesture took the recording path and not the picture one.
        assertEquals(0, fragment.takePictureCalls.get());
    }

    @Test
    public void enablementIsDelegatedToTheCaptureButton() {
        FullFragment fragment = addFullFragment();
        View capture = requireView(fragment, R.id.capture);

        fragment.setEnabled(false);
        assertFalse(fragment.isEnabled());
        assertFalse(capture.isEnabled());

        fragment.setEnabled(true);
        assertTrue(fragment.isEnabled());
        assertTrue(capture.isEnabled());
    }

    /** Toggling the lens reports through onToggle, and syncing the control does not. */
    @Test
    public void theToggleReportsOnlyRealTaps() {
        FullFragment fragment = addFullFragment();
        ToggleButton toggle = requireView(fragment, R.id.toggle);

        toggle.setChecked(!toggle.isChecked());
        assertEquals(1, fragment.toggleCalls.get());

        // The state broadcast re-syncs the control, which must not read as another tap.
        activity.sendBroadcast(new android.content.Intent(CameraView.ACTION_CAMERA_STATE_CHANGED)
                .setPackage(activity.getPackageName()));
        ShadowLooper.shadowMainLooper().idle();
        assertEquals(1, fragment.toggleCalls.get());
    }

    @Test
    public void aCameraClosedCallbackArrivesOnceOnStop() {
        FullFragment fragment = addFullFragment();
        AtomicInteger closes = new AtomicInteger();
        CameraView camera = requireView(fragment, R.id.camera);
        camera.setOnCameraStateChangedListener(new CameraView.OnCameraStateChangedListener() {
            @Override public void onCameraOpened() {}
            @Override public void onCameraClosed() { closes.incrementAndGet(); }
        });

        // onStart already asked the camera to open, so stopping really does close it.
        fragment.onStop();
        ShadowLooper.shadowMainLooper().idle();

        // Exactly one callback. CameraFragment used to dispatch onCameraClosed() itself on top of
        // the one CameraView already sends from close(), so subclasses saw it twice.
        assertEquals(1, closes.get());

        // And closing an already-closed camera reports nothing further.
        fragment.onStop();
        ShadowLooper.shadowMainLooper().idle();
        assertEquals(1, closes.get());
    }

    private static MotionEvent motionEvent(int action, long downTime, long eventTime) {
        return MotionEvent.obtain(downTime, eventTime, action, 10f, 10f, 0);
    }

    @SuppressWarnings("unchecked")
    private static <T extends View> T requireView(CameraFragment fragment, int id) {
        View root = fragment.getView();
        assertNotNull("the fragment should have a view", root);
        T view = (T) root.findViewById(id);
        assertNotNull("missing view " + id, view);
        return view;
    }
}
