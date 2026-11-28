package com.xlythe.view.camera.viewer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import android.graphics.Bitmap;
import android.graphics.Color;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/** Animated PNGs, decoded and composed frame by frame as a browser shows them. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ApngDecoderTest {
    /** One frame to write: where it goes, its colour, and its operations. */
    static final class FrameSpec {
        final int x;
        final int y;
        final int width;
        final int height;
        final int color;
        final int delayNumerator;
        final int delayDenominator;
        final int dispose;
        final int blend;

        FrameSpec(int x, int y, int width, int height, int color, int delayNumerator,
                int delayDenominator, int dispose, int blend) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.color = color;
            this.delayNumerator = delayNumerator;
            this.delayDenominator = delayDenominator;
            this.dispose = dispose;
            this.blend = blend;
        }
    }

    static byte[] chunk(String type, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        write32(out, data.length);
        out.write(name, 0, 4);
        out.write(data, 0, data.length);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        write32(out, (int) crc.getValue());
        return out.toByteArray();
    }

    static void write32(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }

    /** Compressed RGBA rows of one colour, each behind its filter byte. */
    static byte[] pixels(int width, int height, int color) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (int y = 0; y < height; y++) {
            raw.write(0);
            for (int x = 0; x < width; x++) {
                raw.write(Color.red(color));
                raw.write(Color.green(color));
                raw.write(Color.blue(color));
                raw.write(Color.alpha(color));
            }
        }
        Deflater deflater = new Deflater();
        deflater.setInput(raw.toByteArray());
        deflater.finish();
        byte[] buffer = new byte[4096];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (!deflater.finished()) {
            int length = deflater.deflate(buffer);
            out.write(buffer, 0, length);
        }
        return out.toByteArray();
    }

    static byte[] control(int sequence, FrameSpec frame) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write32(out, sequence);
        write32(out, frame.width);
        write32(out, frame.height);
        write32(out, frame.x);
        write32(out, frame.y);
        out.write(frame.delayNumerator >> 8);
        out.write(frame.delayNumerator);
        out.write(frame.delayDenominator >> 8);
        out.write(frame.delayDenominator);
        out.write(frame.dispose);
        out.write(frame.blend);
        return out.toByteArray();
    }

    /**
     * An animated PNG of RGBA frames. The default image is the first frame unless it is given
     * separately, in which case it shows to readers that do not animate and is not a frame.
     */
    static byte[] apng(int width, int height, int plays, Integer defaultColor,
            FrameSpec... frames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}, 0, 8);
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        write32(header, width);
        write32(header, height);
        header.write(new byte[] {8, 6, 0, 0, 0}, 0, 5);
        byte[] ihdr = chunk("IHDR", header.toByteArray());
        out.write(ihdr, 0, ihdr.length);
        ByteArrayOutputStream actl = new ByteArrayOutputStream();
        write32(actl, frames.length);
        write32(actl, plays);
        byte[] control = chunk("acTL", actl.toByteArray());
        out.write(control, 0, control.length);
        byte[] gamma = chunk("gAMA", new byte[] {0, 0, (byte) 0xB1, (byte) 0x8F});
        out.write(gamma, 0, gamma.length);
        int sequence = 0;
        for (int i = 0; i < frames.length; i++) {
            FrameSpec frame = frames[i];
            if (i == 0 && defaultColor != null) {
                byte[] idat = chunk("IDAT", pixels(width, height, defaultColor));
                out.write(idat, 0, idat.length);
            }
            byte[] fctl = chunk("fcTL", control(sequence++, frame));
            out.write(fctl, 0, fctl.length);
            byte[] data = pixels(frame.width, frame.height, frame.color);
            if (i == 0 && defaultColor == null) {
                byte[] idat = chunk("IDAT", data);
                out.write(idat, 0, idat.length);
            } else {
                ByteArrayOutputStream fdat = new ByteArrayOutputStream();
                write32(fdat, sequence++);
                fdat.write(data, 0, data.length);
                byte[] chunk = chunk("fdAT", fdat.toByteArray());
                out.write(chunk, 0, chunk.length);
            }
        }
        byte[] end = chunk("IEND", new byte[0]);
        out.write(end, 0, end.length);
        return out.toByteArray();
    }

    /** Four frames that use every dispose and blend operation. */
    static byte[] everyOperation() {
        return apng(4, 4, 3, null,
                new FrameSpec(0, 0, 4, 4, Color.RED, 1, 10, 0, 0),
                new FrameSpec(1, 1, 2, 2, Color.GREEN, 0, 0, 1, 1),
                new FrameSpec(0, 0, 4, 4, Color.BLUE, 1, 1000, 2, 0),
                new FrameSpec(0, 0, 1, 1, Color.WHITE, 5, 0, 0, 1));
    }

    @Test
    public void composesEachFrameOverTheLast() throws IOException {
        ApngDecoder.Animation animation = ApngDecoder.decode(everyOperation());
        assertNotNull(animation);
        assertEquals(4, animation.frames.length);
        assertEquals(3, animation.plays);
        Bitmap first = animation.frames[0];
        assertEquals(4, first.getWidth());
        assertEquals(Color.RED, first.getPixel(2, 2));

        Bitmap second = animation.frames[1];
        assertEquals("blended over the first", Color.GREEN, second.getPixel(2, 2));
        assertEquals(Color.RED, second.getPixel(0, 0));

        Bitmap third = animation.frames[2];
        assertEquals(Color.BLUE, third.getPixel(2, 2));

        // The third frame put back what was there before it: the first, with the second's
        // region cleared as the second asked.
        Bitmap fourth = animation.frames[3];
        assertEquals(Color.WHITE, fourth.getPixel(0, 0));
        assertEquals(Color.TRANSPARENT, fourth.getPixel(2, 2));
        assertEquals(Color.RED, fourth.getPixel(3, 3));
    }

    @Test
    public void readsEachFramesDelay() throws IOException {
        ApngDecoder.Animation animation = ApngDecoder.decode(everyOperation());
        assertNotNull(animation);
        // A tenth of a second; none at all, as a browser shows it; too quick, as it would be
        // shown; and five hundredths, the denominator defaulting to a hundred.
        assertEquals(100, animation.delays[0]);
        assertEquals(100, animation.delays[1]);
        assertEquals(20, animation.delays[2]);
        assertEquals(50, animation.delays[3]);
    }

    @Test
    public void aDefaultImageApartFromTheAnimationIsNotAFrame() throws IOException {
        ApngDecoder.Animation animation = ApngDecoder.decode(apng(2, 2, 0, Color.BLACK,
                new FrameSpec(0, 0, 2, 2, Color.RED, 1, 10, 0, 0),
                new FrameSpec(0, 0, 2, 2, Color.BLUE, 1, 10, 0, 0)));
        assertNotNull(animation);
        assertEquals(2, animation.frames.length);
        assertEquals(Color.RED, animation.frames[0].getPixel(1, 1));
        assertEquals(Color.BLUE, animation.frames[1].getPixel(1, 1));
    }

    @Test
    public void aFirstFrameThatPutsBackWhatWasBeforeItClearsInstead() throws IOException {
        ApngDecoder.Animation animation = ApngDecoder.decode(apng(2, 2, 0, null,
                new FrameSpec(0, 0, 2, 2, Color.RED, 1, 10, 2, 0),
                new FrameSpec(0, 0, 1, 1, Color.BLUE, 1, 10, 0, 1)));
        assertNotNull(animation);
        assertEquals(Color.TRANSPARENT, animation.frames[1].getPixel(1, 1));
        assertEquals(Color.BLUE, animation.frames[1].getPixel(0, 0));
    }

    @Test
    public void aPngWithoutAnimationIsNone() throws IOException {
        byte[] still = apng(2, 2, 0, null, new FrameSpec(0, 0, 2, 2, Color.RED, 1, 10, 0, 0));
        // Without its acTL, it is a plain PNG.
        String text = new String(still, StandardCharsets.ISO_8859_1);
        int at = text.indexOf("acTL") - 4;
        byte[] plain = new byte[still.length - 20];
        System.arraycopy(still, 0, plain, 0, at);
        System.arraycopy(still, at + 20, plain, at, still.length - at - 20);
        assertNull(ApngDecoder.decode(plain));
    }

    @Test
    public void refusesWhatIsNotAPng() {
        try {
            ApngDecoder.decode(new byte[64]);
            fail("not a PNG");
        } catch (IOException expected) {
            // As documented.
        }
    }

    @Test
    public void aChunkThatRunsPastTheEndStopsTheRead() throws IOException {
        byte[] whole = everyOperation();
        byte[] cut = new byte[whole.length - 30];
        System.arraycopy(whole, 0, cut, 0, cut.length);
        ApngDecoder.Animation animation = ApngDecoder.decode(cut);
        assertNotNull(animation);
        assertEquals("the last frame's data was cut, but its control was read", 4,
                animation.frames.length);
    }

    @Test
    public void largeAnimationsAreKeptSmaller() {
        assertEquals(1, ApngDecoder.sampleSize(100, 100, 20));
        assertEquals(2, ApngDecoder.sampleSize(2000, 2000, 4));
        assertEquals(64, ApngDecoder.sampleSize(1_000_000, 1_000_000, 1000));
    }

    @Test
    public void choosesTheFrameATimeFallsIn() throws IOException {
        ApngDecoder.Animation animation = ApngDecoder.decode(everyOperation());
        assertNotNull(animation);
        assertEquals(0, AnimationView.frameAt(animation, 0));
        assertEquals(1, AnimationView.frameAt(animation, 150));
        assertEquals(2, AnimationView.frameAt(animation, 210));
        assertEquals(3, AnimationView.frameAt(animation, 250));
        assertEquals("loops", 0, AnimationView.frameAt(animation, 270));
        assertEquals("stops on the last frame after its plays", 3,
                AnimationView.frameAt(animation, 270 * 3 + 5));
        ApngDecoder.Animation still = new ApngDecoder.Animation(animation.frames,
                new int[] {0, 0, 0, 0}, 0);
        assertEquals(0, AnimationView.frameAt(still, 1000));
        assertEquals(100, AnimationView.untilNextFrame(animation, 0));
        assertEquals(50, AnimationView.untilNextFrame(animation, 150));
        assertEquals(10, AnimationView.untilNextFrame(animation, 210));
        assertEquals("loops", 100, AnimationView.untilNextFrame(animation, 270));
        assertEquals("done", 0, AnimationView.untilNextFrame(animation, 270 * 3));
        assertEquals(0, AnimationView.untilNextFrame(still, 5));
    }
}
