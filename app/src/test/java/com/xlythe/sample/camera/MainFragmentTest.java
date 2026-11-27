package com.xlythe.sample.camera;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import com.xlythe.view.camera.CameraView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.HashSet;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
@Config(minSdk = 33, qualifiers = "en")
public class MainFragmentTest {

    /** The exposure label is the index times the camera's step, in EV, to one decimal place. */
    @Test
    public void theExposureLabelIsInEv() {
        Context context = RuntimeEnvironment.getApplication();

        assertEquals("+0.7 EV", MainFragment.formatExposure(context, 4, 1 / 6f));
        assertEquals("+1.0 EV", MainFragment.formatExposure(context, 6, 1 / 6f));
        assertEquals("-0.7 EV", MainFragment.formatExposure(context, -2, 1 / 3f));
        assertEquals("0 EV", MainFragment.formatExposure(context, 0, 1 / 3f));
    }

    /** Each photo mode has an icon and a description of its own, for its button in the row. */
    @Test
    public void everyPhotoModeHasItsOwnIconAndDescription() {
        int[] modes = {
                CameraView.PhotoMode.BACKGROUND_BLUR,
                CameraView.PhotoMode.HDR,
                CameraView.PhotoMode.NIGHT,
                CameraView.PhotoMode.FACE_RETOUCH,
                CameraView.PhotoMode.AUTO,
        };
        Set<Integer> icons = new HashSet<>();
        Set<Integer> descriptions = new HashSet<>();
        for (int mode : modes) {
            icons.add(MainFragment.photoModeIcon(mode));
            descriptions.add(MainFragment.photoModeDescription(mode));
        }
        assertEquals(modes.length, icons.size());
        assertEquals(modes.length, descriptions.size());
        assertEquals("Night", RuntimeEnvironment.getApplication().getString(
                MainFragment.photoModeDescription(CameraView.PhotoMode.NIGHT)));
    }
}
