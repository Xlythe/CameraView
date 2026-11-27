package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.annotation.NonNull;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * What CameraView does with a burst before any camera is involved: the cases it refuses, which
 * every backend shares because they are decided before a backend is asked.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class CameraViewBurstTest {
    private Context context;
    private CameraView cameraView;
    private File directory;

    /** Everything a burst listener heard. */
    static final class Heard implements CameraView.OnBurstListener {
        final List<File> captured = new ArrayList<>();
        final List<Integer> finishes = new ArrayList<>();

        @Override
        public void onBurstImageCaptured(@NonNull File file) {
            captured.add(file);
        }

        @Override
        public void onBurstFinished(int count) {
            finishes.add(count);
        }
    }

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        cameraView = new CameraView(context);
        directory = new File(context.getCacheDir(), "burst");
    }

    /** A button pressed before the camera opens has to hear that nothing was taken. */
    @Test
    public void aBurstOnAClosedCameraFinishesWithNothing() {
        Heard heard = new Heard();

        cameraView.startBurst(directory, 5, heard);

        assertEquals(List.of(0), heard.finishes);
        assertEquals(0, heard.captured.size());
    }

    @Test
    public void aBurstTakesAtLeastOnePicture() {
        try {
            cameraView.startBurst(directory, 0, new Heard());
            fail("expected a count of zero to be refused");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    /** Releasing a burst button with no burst running is ordinary, not an error. */
    @Test
    public void stoppingWithNoBurstDoesNothing() {
        cameraView.stopBurst();
    }
}
