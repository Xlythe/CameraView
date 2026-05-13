package com.xlythe.view.camera;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(minSdk = 21)
public class VideoViewTest {

    private Context context;
    private VideoView videoView;

    @Before
    public void setup() {
        context = RuntimeEnvironment.application;
        videoView = new VideoView(context);
    }

    @Test
    public void testInitialProperties() {
        assertFalse(videoView.isPlaying());
        assertFalse(videoView.isMirrored());
        assertFalse(videoView.isLooping());
        assertNull(videoView.getFile());
        assertFalse(videoView.hasStream());
    }

    @Test
    public void testPropertySetters() {
        videoView.setShouldMirror(true);
        assertTrue(videoView.isMirrored());

        videoView.setShouldLoop(true);
        assertTrue(videoView.isLooping());

        File testFile = new File(context.getCacheDir(), "test.mp4");
        videoView.setFile(testFile);
        assertEquals(testFile, videoView.getFile());
    }
}
