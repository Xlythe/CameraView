package com.xlythe.view.camera.stream;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.io.PipedInputStream;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(minSdk = 21)
public class LossyPipedOutputStreamTest {

    @Test
    public void testLossyBehavior() throws IOException {
        PipedInputStream snk = new PipedInputStream();
        LossyPipedOutputStream out = new LossyPipedOutputStream(snk);

        out.write(1);
        assertEquals(1, snk.available());

        out.flush();
        // Now that we flushed, since available() > 0, the next writes should be dropped!
        out.write(2);
        out.write(new byte[]{3, 4});
        out.write(new byte[]{5, 6}, 0, 2);

        // Available should still be 1 because packets were dropped
        assertEquals(1, snk.available());

        // Read the 1 to clear sink
        assertEquals(1, snk.read());
        assertEquals(0, snk.available());

        // Now writes should succeed again
        out.write(7);
        assertEquals(1, snk.available());

        out.close();
        snk.close();
    }
}
