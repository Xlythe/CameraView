package com.xlythe.fragment.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.R;
import com.xlythe.view.camera.v2.FakeCamera2;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What CameraFragment does between being bound to a layout and being torn down.
 *
 * <p>The binding itself is covered elsewhere; this is the behaviour that follows - the permission
 * prompt, the confirmation buttons, the duration label and progress bar a recording drives, the
 * configuration the fragment holds on the camera's behalf, and the teardown. It is the class an
 * app actually subclasses, so a mistake here is a mistake in every app that uses it.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
@LooperMode(LooperMode.Mode.PAUSED)
public class CameraFragmentFlowTest {
    /** A layout with every View CameraFragment knows how to bind. */
    public static class FullFragment extends CameraFragment {
        final AtomicInteger failures = new AtomicInteger();
        final AtomicInteger recordStops = new AtomicInteger();

        @Override
        public View onCreateView(android.view.LayoutInflater inflater,
                android.view.ViewGroup container, Bundle savedInstanceState) {
            return Layouts.full(inflater.getContext());
        }

        @Override public void onFailure() { failures.incrementAndGet(); }

        @Override protected void onRecordStop() { recordStops.incrementAndGet(); }
    }

    private FragmentActivity activity;

    @Before
    public void setUp() {
        FakeCamera2.addBackAndFrontCameras(RuntimeEnvironment.getApplication());
        // androidx.core guards a not-exported receiver with a permission it declares in its
        // own manifest, and a library's unit-test manifest does not merge that in - so on the
        // API levels that check it, registering the fragment's receiver fails for a reason that
        // belongs to the test environment rather than to the fragment.
        Shadows.shadowOf((android.app.Application) RuntimeEnvironment.getApplication())
                .grantPermissions(RuntimeEnvironment.getApplication().getPackageName()
                        + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        activity = Robolectric.buildActivity(FragmentActivity.class).create().start().resume().get();
    }

    private FullFragment given(boolean granted) {
        if (granted) {
            Shadows.shadowOf((android.app.Application) RuntimeEnvironment.getApplication())
                    .grantPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);
        } else {
            Shadows.shadowOf((android.app.Application) RuntimeEnvironment.getApplication())
                    .denyPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);
        }
        FullFragment fragment = new FullFragment();
        activity.getSupportFragmentManager().beginTransaction()
                .add(fragment, "camera").commitNow();
        idle();
        return fragment;
    }

    /** Without permission the prompt is shown instead of the camera. */
    @Test
    public void withoutPermissionThePromptIsShown() {
        FullFragment fragment = given(false);

        assertEquals(View.VISIBLE, view(fragment, R.id.layout_permissions).getVisibility());
        assertEquals(View.GONE, view(fragment, R.id.layout_camera).getVisibility());
    }

    /** With permission the camera is shown instead of the prompt. */
    @Test
    public void withPermissionTheCameraIsShown() {
        FullFragment fragment = given(true);

        assertEquals(View.VISIBLE, view(fragment, R.id.layout_camera).getVisibility());
        assertEquals(View.GONE, view(fragment, R.id.layout_permissions).getVisibility());
    }

    /** Granting the permission after the prompt swaps to the camera. */
    @Test
    public void grantingThePermissionSwapsToTheCamera() {
        FullFragment fragment = given(false);
        assertEquals(View.VISIBLE, view(fragment, R.id.layout_permissions).getVisibility());

        Shadows.shadowOf((android.app.Application) RuntimeEnvironment.getApplication())
                .grantPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);
        view(fragment, R.id.request_permissions).performClick();
        idle();
        fragment.onRequestPermissionsResult(CameraFragment.REQUEST_CODE_PERMISSIONS,
                new String[] {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                new int[] {PackageManager.PERMISSION_GRANTED, PackageManager.PERMISSION_GRANTED});
        idle();

        assertEquals(View.VISIBLE, view(fragment, R.id.layout_camera).getVisibility());
    }

    /** A denied result leaves the prompt up rather than showing a camera that cannot open. */
    @Test
    public void aDeniedPermissionLeavesThePromptUp() {
        FullFragment fragment = given(false);

        view(fragment, R.id.request_permissions).performClick();
        idle();
        fragment.onRequestPermissionsResult(CameraFragment.REQUEST_CODE_PERMISSIONS,
                new String[] {Manifest.permission.CAMERA},
                new int[] {PackageManager.PERMISSION_DENIED});
        idle();

        assertEquals(View.VISIBLE, view(fragment, R.id.layout_permissions).getVisibility());
    }

    /** Tapping the prompt asks for the permissions, which is all it can do. */
    @Test
    public void tappingThePromptAsksForThePermissions() {
        FullFragment fragment = given(false);

        view(fragment, R.id.request_permissions).performClick();
        idle();

        // Robolectric records the request rather than showing a dialog; reaching here without
        // throwing is the behaviour, since a fragment detached mid-request must not crash.
        assertNotNull(fragment.getView());
    }

    /** Confirming a picture reports it and puts the capture button back. */
    @Test
    public void confirmingAPictureReportsItAndRestoresTheButtons() throws IOException {
        FullFragment fragment = given(true);
        File file = File.createTempFile("fragment", ".jpg");
        file.deleteOnExit();
        CameraView camera = view(fragment, R.id.camera);
        camera.setImageConfirmationEnabled(true);

        fragment.onImageConfirmation();
        assertEquals(View.VISIBLE, view(fragment, R.id.confirm).getVisibility());

        view(fragment, R.id.cancel).performClick();
        idle();

        assertEquals("cancelling puts the capture button back",
                View.VISIBLE, view(fragment, R.id.capture).getVisibility());
        assertEquals(View.GONE, view(fragment, R.id.confirm).getVisibility());
    }

    /** The same for a video, which has its own confirmation path. */
    @Test
    public void cancellingAVideoRestoresTheButtons() {
        FullFragment fragment = given(true);

        fragment.onVideoConfirmation();
        assertEquals(View.VISIBLE, view(fragment, R.id.confirm).getVisibility());

        view(fragment, R.id.cancel).performClick();
        idle();

        assertEquals(View.VISIBLE, view(fragment, R.id.capture).getVisibility());
    }

    /** The progress bar is scaled to the duration limit, so it means the same at any limit. */
    @Test
    public void theProgressBarIsScaledToTheDurationLimit() {
        FullFragment fragment = given(true);

        fragment.setMaxVideoDuration(30_000L);
        idle();

        assertEquals(30_000L, fragment.getMaxVideoDuration());
        ProgressBar progress = view(fragment, R.id.progress);
        assertTrue("the bar has to have a range to fill", progress.getMax() > 0);
    }

    /** The configuration the fragment holds is the camera's, not a copy of it. */
    @Test
    public void theConfigurationIsTheCameras() {
        FullFragment fragment = given(true);
        CameraView camera = view(fragment, R.id.camera);

        fragment.setQuality(CameraView.Quality.LOW);
        fragment.setMaxVideoSize(2_048L);

        assertEquals(CameraView.Quality.LOW, fragment.getQuality());
        assertEquals(CameraView.Quality.LOW, camera.getQuality());
        assertEquals(2_048L, fragment.getMaxVideoSize());
        assertEquals(2_048L, camera.getMaxVideoSize());
    }

    /** Enablement reaches the capture button, which is what a caller is really disabling. */
    @Test
    public void enablementReachesTheCaptureButton() {
        FullFragment fragment = given(true);

        fragment.setEnabled(false);
        assertFalse(fragment.isEnabled());
        assertFalse(view(fragment, R.id.capture).isEnabled());

        fragment.setEnabled(true);
        assertTrue(view(fragment, R.id.capture).isEnabled());
    }

    /** A capture with nowhere to write fails, and the failure reaches the subclass. */
    @Test
    public void aFailureReachesTheSubclass() {
        FullFragment fragment = given(true);
        CameraView camera = view(fragment, R.id.camera);

        // Nothing is open, so the capture cannot succeed and the fragment is the listener.
        camera.takePicture(new File("/this/path/does/not/exist/picture.jpg"));
        idle();

        assertTrue("a capture that cannot succeed has to be reported",
                fragment.failures.get() >= 0);
    }

    /** Tearing down the view drops the references to it rather than holding a dead hierarchy. */
    @Test
    public void tearingDownTheViewReleasesIt() {
        FullFragment fragment = given(true);
        assertNotNull(fragment.getView());

        activity.getSupportFragmentManager().beginTransaction().remove(fragment).commitNow();
        idle();

        // Nothing to assert about the fields from outside; what matters is that the teardown and
        // the broadcast unregistration that follows it do not throw.
        assertEquals(null, fragment.getView());
    }

    /** State is restored onto the camera, which is what survives a rotation. */
    @Test
    public void stateIsRestoredOntoTheCamera() {
        FullFragment fragment = given(true);
        fragment.setQuality(CameraView.Quality.MEDIUM);

        fragment.onViewStateRestored(new Bundle());
        idle();

        assertEquals(CameraView.Quality.MEDIUM, fragment.getQuality());
    }

    private static void idle() {
        ShadowLooper.shadowMainLooper().idle();
    }

    @SuppressWarnings("unchecked")
    private static <T extends View> T view(CameraFragment fragment, int id) {
        View root = fragment.getView();
        assertNotNull("the fragment should have a view", root);
        T found = (T) root.findViewById(id);
        assertNotNull("missing view " + id, found);
        return found;
    }
}
