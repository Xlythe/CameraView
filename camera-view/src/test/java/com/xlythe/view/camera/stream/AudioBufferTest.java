package com.xlythe.view.camera.stream;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(minSdk = 21)
public class AudioBufferTest {

    private static class TestAudioBuffer extends AudioBuffer {
        @Override
        protected boolean validSize(int size) {
            return size > 0;
        }

        @Override
        protected int getMinBufferSize(int sampleRate) {
            return sampleRate == 16000 ? 2048 : -1;
        }
    }

    @Test
    public void testAudioBufferSelection() {
        TestAudioBuffer buffer = new TestAudioBuffer();
        assertEquals(16000, buffer.getSampleRate());
        assertEquals(2048, buffer.getSize());
        assertNotNull(buffer.data());
        assertEquals(2048, buffer.data().length);
    }
}
