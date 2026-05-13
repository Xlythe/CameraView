package com.xlythe.fragment.camera;

import android.content.Context;
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
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(RobolectricTestRunner.class)
@Config(minSdk = 33)
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

    private TestCameraFragment fragment;

    @Before
    public void setup() {
        FragmentActivity activity = Robolectric.buildActivity(FragmentActivity.class).create().start().resume().get();
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
}
