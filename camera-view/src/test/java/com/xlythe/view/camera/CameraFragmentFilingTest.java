package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Application;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import com.xlythe.fragment.camera.CameraFragmentBindingTest;

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
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** CameraFragment files every capture where the gallery looks, with nothing to call. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
@LooperMode(LooperMode.Mode.PAUSED)
public class CameraFragmentFilingTest {
    /** Overrides the capture callback without calling super, which must not stop the filing. */
    public static class FilingFragment extends CameraFragmentBindingTest.FullFragment {
        final AtomicInteger captured = new AtomicInteger();
        final List<Uri> filed = new ArrayList<>();

        @Override
        public void onImageCaptured(File file) {
            captured.incrementAndGet();
        }

        @Override
        public void onCaptureFiled(@Nullable Uri uri) {
            filed.add(uri);
        }
    }

    private Application context;
    private FragmentActivity activity;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(context).grantPermissions(
                Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
                // androidx.core declares this for its not-exported receivers in a manifest a
                // library's unit tests do not merge in.
                context.getPackageName() + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        activity = Robolectric.buildActivity(FragmentActivity.class)
                .create().start().resume().get();
    }

    @Test
    public void aPictureIsFiledEvenWhenTheSubclassSkipsSuper() throws Exception {
        FilingFragment fragment = new FilingFragment();
        activity.getSupportFragmentManager().beginTransaction()
                .add(fragment, "camera").commitNow();
        CameraView camera = fragment.requireView().findViewById(R.id.camera);
        assertNotNull(camera);
        camera.setImageConfirmationEnabled(false);

        camera.showImageConfirmation(picture());

        long deadline = System.currentTimeMillis() + 5_000;
        while (fragment.filed.isEmpty() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper();
            Thread.sleep(10);
        }
        assertEquals(1, fragment.captured.get());
        assertEquals(1, fragment.filed.size());
        Uri filed = fragment.filed.get(0);
        assertNotNull("the picture should have been filed", filed);
        assertTrue(filed.toString().startsWith(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString()));
    }

    private File picture() throws IOException {
        File file = new File(context.getCacheDir(), "picture.jpg");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(new byte[] {1, 2, 3});
        }
        return file;
    }
}
