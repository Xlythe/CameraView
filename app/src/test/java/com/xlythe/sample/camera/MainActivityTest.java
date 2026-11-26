package com.xlythe.sample.camera;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertNotNull;

@RunWith(RobolectricTestRunner.class)
@Config(minSdk = 33)
public class MainActivityTest {

    @Test
    public void testActivityCreation() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).create().get();
        assertNotNull(activity);
    }
}
