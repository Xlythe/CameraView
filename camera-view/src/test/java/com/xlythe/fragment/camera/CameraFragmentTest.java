package com.xlythe.fragment.camera;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

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
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class CameraFragmentTest {

    public static class TestCameraFragment extends CameraFragment {
        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
            Context context = inflater.getContext();
            FrameLayout root = new FrameLayout(context);
            root.setId(R.id.layout_camera);

            CameraView cameraView = new CameraView(context);
            cameraView.setId(R.id.camera);
            root.addView(cameraView);

            View permissions = new View(context);
            permissions.setId(R.id.layout_permissions);
            root.addView(permissions);

            View request = new View(context);
            request.setId(R.id.request_permissions);
            root.addView(request);

            return root;
        }
    }

    private FragmentActivity activity;
    private TestCameraFragment fragment;

    @Before
    public void setup() {
        // androidx.core guards a not-exported receiver with a permission it declares in its
        // own manifest, and a library's unit-test manifest does not merge that in - so on the
        // API levels that check it, registering the fragment's receiver fails for a reason that
        // belongs to the test environment rather than to the fragment.
        Shadows.shadowOf((android.app.Application) RuntimeEnvironment.getApplication())
                .grantPermissions(RuntimeEnvironment.getApplication().getPackageName()
                        + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        activity = Robolectric.buildActivity(FragmentActivity.class).create().start().resume().get();
        fragment = new TestCameraFragment();
        activity.getSupportFragmentManager().beginTransaction().add(fragment, "camera").commitNow();
    }

    @Test
    public void testFragmentProperties() {
        assertNotNull(fragment);
        fragment.setQuality(CameraView.Quality.MAX);
        assertEquals(CameraView.Quality.MAX, fragment.getQuality());

        fragment.setMaxVideoDuration(5000L);
        assertEquals(5000L, fragment.getMaxVideoDuration());

        fragment.setMaxVideoSize(1024L);
        assertEquals(1024L, fragment.getMaxVideoSize());
    }

    /**
     * Detaching runs onDestroyView but not onDetach, so the camera state receiver is still
     * registered while the Views are gone. It used to reach a released CameraView from there.
     */
    @Test
    public void stateBroadcastSurvivesADetachedView() {
        activity.getSupportFragmentManager().beginTransaction().detach(fragment).commitNow();

        activity.sendBroadcast(new Intent(CameraView.ACTION_CAMERA_STATE_CHANGED)
                .setPackage(activity.getPackageName()));
    }

    /** Stopping a fragment whose View is already gone must not reach through to the camera. */
    @Test
    public void stopWithoutAViewDoesNotTouchTheCamera() {
        activity.getSupportFragmentManager().beginTransaction().detach(fragment).commitNow();

        fragment.onStop();
    }
}
