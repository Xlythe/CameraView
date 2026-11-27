package com.xlythe.sample.camera;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

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
}
