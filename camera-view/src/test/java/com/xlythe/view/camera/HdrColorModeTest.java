package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.view.View;
import android.view.Window;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** One HDR colour mode shared by everything in a window that shows HDR. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class HdrColorModeTest {
    @Test
    public void theLastToLetGoPutsTheOldModeBack() {
        Window window = Robolectric.buildActivity(Activity.class).setup().get().getWindow();
        window.setColorMode(ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT);

        HdrColorMode.acquire(window);
        HdrColorMode.acquire(window);
        assertEquals(ActivityInfo.COLOR_MODE_HDR, window.getColorMode());
        assertEquals(2, HdrColorMode.holds(window));

        HdrColorMode.release(window);
        assertEquals(ActivityInfo.COLOR_MODE_HDR, window.getColorMode());
        HdrColorMode.release(window);
        assertEquals(ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT, window.getColorMode());
        assertEquals(0, HdrColorMode.holds(window));

        // A release with nothing held changes nothing.
        HdrColorMode.release(window);
        assertEquals(ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT, window.getColorMode());
    }

    @Test
    public void eachWindowIsHeldOnItsOwn() {
        Window first = Robolectric.buildActivity(Activity.class).setup().get().getWindow();
        Window second = Robolectric.buildActivity(Activity.class).setup().get().getWindow();
        HdrColorMode.acquire(first);
        assertEquals(ActivityInfo.COLOR_MODE_HDR, first.getColorMode());
        assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, second.getColorMode());
        HdrColorMode.release(first);
        assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, first.getColorMode());
    }

    @Test
    @Config(sdk = 25)
    public void beforeOreoThereIsNoHdrMode() {
        Window window = Robolectric.buildActivity(Activity.class).setup().get().getWindow();
        HdrColorMode.acquire(window);
        assertEquals(0, HdrColorMode.holds(window));
        HdrColorMode.release(window);
    }

    @Test
    public void findsAViewsWindow() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        assertSame(activity.getWindow(), HdrColorMode.windowOf(new View(activity)));
        assertNull(HdrColorMode.windowOf(new View(ApplicationProvider.getApplicationContext())));
    }
}
