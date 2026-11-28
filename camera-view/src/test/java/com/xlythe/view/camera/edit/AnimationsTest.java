package com.xlythe.view.camera.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Animated stickers told from their bytes, an animated PNG's frames, and their timing. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 37)
public class AnimationsTest {
    @Test
    public void stickersAreToldFromTheirFirstBytes() throws IOException {
        byte[] gif = resource("editor/two_frames.gif");
        assertTrue(Animations.isGif(gif));
        assertFalse(Animations.isPng(gif));
        assertTrue(Animations.isAnimatedWebp(resource("media/skia_stoplight_animated.webp")));
        assertFalse(Animations.isAnimatedWebp(resource("media/androidx_ic_launcher_round.webp")));
        assertTrue(Animations.isPng(resource("media/apng_bouncing_beach_ball.png")));
        assertFalse(Animations.isGif(new byte[] {'G', 'I', 'F'}));
        assertNull("before Android 9 a GIF's frames wait for the shared decoder",
                Animations.decodeLegacy(gif, Long.MAX_VALUE));
    }

    @Test
    public void anAnimatedPngIsComposedFrameByFrame() throws IOException {
        Animation animation = Animations.decodePng(resource("media/apng_bouncing_beach_ball.png"),
                Long.MAX_VALUE);
        assertNotNull(animation);
        assertTrue(animation.frames.length > 1);
        assertEquals(animation.frames.length, animation.delays.length);
        assertFalse("the ball moved", animation.frames[0].sameAs(animation.frames[1]));
    }

    @Test
    public void aStillPngIsNotAnAnimation() throws IOException {
        assertNull(Animations.decodePng(resource("media/pngsuite_ct1n0g04_tEXt.png"),
                Long.MAX_VALUE));
    }

    @Test
    public void anAnimationTooLargeIsRefused() throws IOException {
        byte[] png = resource("media/apng_bouncing_beach_ball.png");
        assertThrows(IOException.class, () -> Animations.decodePng(png, 40));
    }

    @Test
    public void anAnimationThatPlaysOnceHoldsItsLastFrame() {
        Bitmap a = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        Bitmap b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        Animation once = new Animation(new Bitmap[] {a, b}, new int[] {100, 100}, 1);
        assertEquals(1, once.frameAt(150));
        assertEquals(1, once.frameAt(10_000));
        assertEquals(0, once.frameAt(-5));
        assertTrue(once.bitmapAt(150) == b);
        assertThrows(IllegalArgumentException.class,
                () -> new Animation(new Bitmap[] {a}, new int[] {1, 2}, 0));
    }

    @Test
    public void theNextFrameIsDueWhenTheShownOneEnds() {
        Bitmap a = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        Bitmap b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        Animation forever = new Animation(new Bitmap[] {a, b}, new int[] {100, 40}, 0);
        assertEquals(100, forever.untilNextFrame(0));
        assertEquals(30, forever.untilNextFrame(110));
        assertEquals(60, forever.untilNextFrame(180));
        Animation once = new Animation(new Bitmap[] {a, b}, new int[] {100, 40}, 1);
        assertEquals(-1, once.untilNextFrame(140));
        assertEquals(-1, new Animation(new Bitmap[] {a}, new int[] {100}, 0).untilNextFrame(5));
    }

    static byte[] resource(String name) throws IOException {
        try (InputStream input = AnimationsTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(name, input);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }
}
