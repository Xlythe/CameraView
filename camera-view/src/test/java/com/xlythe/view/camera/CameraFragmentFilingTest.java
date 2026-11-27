package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import com.xlythe.fragment.camera.CameraFragment;

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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * CameraFragment's capture button takes pictures and records videos without a file, so every
 * capture it makes is filed where the gallery looks and reaches a subclass by uri.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
@LooperMode(LooperMode.Mode.PAUSED)
public class CameraFragmentFilingTest {
    /** Overrides only the callbacks a capture without a file reaches. */
    public static class FilingFragment extends CameraFragment {
        final List<Uri> saved = new ArrayList<>();
        final List<File> captured = new ArrayList<>();

        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                @Nullable Bundle savedInstanceState) {
            Context context = inflater.getContext();
            FrameLayout root = new FrameLayout(context);

            FrameLayout holder = new FrameLayout(context);
            holder.setId(R.id.layout_camera);
            CameraView camera = new CameraViewFilingTest.RecordingCameraView(context);
            camera.setId(R.id.camera);
            holder.addView(camera);
            Button capture = new Button(context);
            capture.setId(R.id.capture);
            holder.addView(capture);
            root.addView(holder);

            FrameLayout permissions = new FrameLayout(context);
            permissions.setId(R.id.layout_permissions);
            View request = new View(context);
            request.setId(R.id.request_permissions);
            permissions.addView(request);
            root.addView(permissions);
            return root;
        }

        @Override
        public void onImageSaved(@NonNull Uri uri) {
            saved.add(uri);
        }

        @Override
        public void onVideoSaved(@NonNull Uri uri) {
            saved.add(uri);
        }

        @Override
        public void onImageCaptured(File file) {
            captured.add(file);
        }

        @Override
        public void onVideoCaptured(File file) {
            captured.add(file);
        }
    }

    private Application context;
    private FilingFragment fragment;
    private CameraViewFilingTest.RecordingCameraView camera;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(context).grantPermissions(
                Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
                // androidx.core declares this for its not-exported receivers in a manifest a
                // library's unit tests do not merge in.
                context.getPackageName() + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        FragmentActivity activity = Robolectric.buildActivity(FragmentActivity.class)
                .create().start().resume().get();
        fragment = new FilingFragment();
        activity.getSupportFragmentManager().beginTransaction()
                .add(fragment, "camera").commitNow();
        camera = fragment.requireView().findViewById(R.id.camera);
    }

    @Test
    public void aTapTakesAPictureThatIsFiled() throws Exception {
        View capture = fragment.requireView().findViewById(R.id.capture);
        long now = SystemClock.uptimeMillis();
        capture.dispatchTouchEvent(touch(MotionEvent.ACTION_DOWN, now, now));
        capture.dispatchTouchEvent(touch(MotionEvent.ACTION_UP, now, now + 20));
        ShadowLooper.idleMainLooper();

        assertEquals(1, camera.pictures.size());
        File picture = camera.pictures.get(0);
        assertEquals("the tap took the no-file path",
                CameraView.captureDirectory(context), picture.getParentFile());

        camera.showImageConfirmation(write(picture));
        awaitSaved();

        assertEquals(1, fragment.saved.size());
        assertTrue(fragment.saved.get(0).toString().startsWith(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString()));
        assertEquals(0, fragment.captured.size());
    }

    @Test
    public void aHoldRecordsAVideoThatIsFiled() throws Exception {
        View capture = fragment.requireView().findViewById(R.id.capture);
        long now = SystemClock.uptimeMillis();
        capture.dispatchTouchEvent(touch(MotionEvent.ACTION_DOWN, now, now));
        ShadowLooper.shadowMainLooper()
                .idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 50));

        assertEquals(1, camera.videos.size());
        File video = camera.videos.get(0);
        assertEquals(CameraView.captureDirectory(context), video.getParentFile());

        camera.showVideoConfirmation(write(video));
        awaitSaved();

        assertEquals(1, fragment.saved.size());
        assertTrue(fragment.saved.get(0).toString().startsWith(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI.toString()));
        assertEquals(0, fragment.captured.size());
    }

    private static MotionEvent touch(int action, long downTime, long eventTime) {
        return MotionEvent.obtain(downTime, eventTime, action, 0f, 0f, 0);
    }

    private static File write(File file) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(new byte[] {1, 2, 3});
        }
        return file;
    }

    /** Runs the main looper until filing, which happens on a thread of its own, has reported. */
    private void awaitSaved() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (fragment.saved.isEmpty() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper();
            Thread.sleep(10);
        }
    }
}
