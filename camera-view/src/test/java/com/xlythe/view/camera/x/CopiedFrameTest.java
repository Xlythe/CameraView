package com.xlythe.view.camera.x;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.ByteBuffer;

/**
 * Copying a RAW frame out of CameraX's buffer into the packed rows DngCreator reads.
 *
 * <p>A mistake here does not throw: it shears or shifts the picture, which nothing downstream
 * would notice.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class CopiedFrameTest {
    private static final int WIDTH = 3;
    private static final int HEIGHT = 4;

    /** Two bytes a pixel, so a row of three is six bytes. */
    private static final int ROW_BYTES = WIDTH * 2;

    /** Padding a camera might add to each row, filled with a value no pixel takes. */
    private static final byte PADDING = (byte) 0xEE;

    @Test
    public void unpaddedRowsAreCopiedAsTheyAre() {
        ByteBuffer packed = CopiedFrame.pack(frame(ROW_BYTES, false), ROW_BYTES, WIDTH, HEIGHT);

        assertPixels(packed);
    }

    /** Padding goes, and every row starts where its stride says it does. */
    @Test
    public void paddedRowsLoseTheirPadding() {
        int stride = ROW_BYTES + 10;

        ByteBuffer packed = CopiedFrame.pack(frame(stride, false), stride, WIDTH, HEIGHT);

        assertPixels(packed);
    }

    /** A last row that stops short of a full stride, as camera buffers often do. */
    @Test
    public void aShortLastRowIsStillCopied() {
        int stride = ROW_BYTES + 10;

        ByteBuffer packed = CopiedFrame.pack(frame(stride, true), stride, WIDTH, HEIGHT);

        assertPixels(packed);
    }

    /** The source is read through a copy of its position, so it can be read again. */
    @Test
    public void theSourceIsLeftWhereItWas() {
        ByteBuffer source = frame(ROW_BYTES, false);

        CopiedFrame.pack(source, ROW_BYTES, WIDTH, HEIGHT);

        assertEquals(0, source.position());
        assertEquals(source.capacity(), source.limit());
    }

    /** A frame whose pixel bytes count up from one, with padding after each row. */
    private static ByteBuffer frame(int stride, boolean shortLastRow) {
        int size = stride * HEIGHT - (shortLastRow ? stride - ROW_BYTES : 0);
        ByteBuffer buffer = ByteBuffer.allocateDirect(size);
        for (int i = 0; i < size; i++) {
            int column = i % stride;
            buffer.put(column < ROW_BYTES
                    ? (byte) (1 + (i / stride) * ROW_BYTES + column) : PADDING);
        }
        buffer.clear();
        return buffer;
    }

    private static void assertPixels(ByteBuffer packed) {
        assertTrue(packed.isDirect());
        assertEquals(0, packed.position());
        assertEquals(ROW_BYTES * HEIGHT, packed.remaining());
        for (int i = 0; i < ROW_BYTES * HEIGHT; i++) {
            assertEquals("byte " + i, (byte) (1 + i), packed.get(i));
        }
    }
}
