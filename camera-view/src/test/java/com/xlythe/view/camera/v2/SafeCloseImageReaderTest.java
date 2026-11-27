package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.ImageFormat;
import android.media.FakeImage;
import android.media.ImageReader;

import com.xlythe.view.camera.shadow.ShadowInjectableImageReader;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * A reader that outlives the frames it handed out.
 *
 * <p>Closing an ImageReader frees the buffers behind its frames, and a DNG is written straight out
 * of one of them on another thread. A session rebuilt mid-write closed its reader under the writer,
 * and DngCreator read freed memory and took the process down. So a close only stops new frames,
 * and the reader goes once the last frame is given back.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, shadows = ShadowInjectableImageReader.class)
public class SafeCloseImageReaderTest {
    /** Readers left open by earlier tests sharing this sandbox, which this test does not count. */
    private int readersBefore;

    private SafeCloseImageReader reader;

    @Before
    public void setUp() {
        // Robolectric runs no resetter for a shadow a test declares itself.
        ShadowInjectableImageReader.reset();
        readersBefore = ShadowInjectableImageReader.openReaderCount();
        reader = new SafeCloseImageReader(8, 8, ImageFormat.RAW_SENSOR, 2);
        reader.setOnImageAvailableListener(r -> {}, null);
    }

    @Test
    public void closingWithNothingOutReleasesTheReader() {
        reader.close();

        assertEquals(readersBefore, ShadowInjectableImageReader.openReaderCount());
    }

    /** The case that crashed: the session closes while a frame is still being written. */
    @Test
    public void closingWhileAFrameIsOutWaitsForTheFrame() {
        FakeImage image = FakeImage.raw(8, 8, 1L);
        offer(image);
        SafeCloseImageReader.Frame frame = reader.acquireNextImage();
        assertNotNull(frame);
        assertSame(image, frame.getImage());
        assertEquals(1L, frame.getTimestamp());

        reader.close();

        assertEquals("the reader has to outlive the frame it handed out",
                readersBefore + 1, ShadowInjectableImageReader.openReaderCount());
        assertFalse(image.isClosed());

        frame.close();

        assertTrue(image.isClosed());
        assertEquals("and go once the frame is back",
                readersBefore, ShadowInjectableImageReader.openReaderCount());
    }

    /** Every frame out holds the reader, not just the first. */
    @Test
    public void theLastFrameBackReleasesTheReader() {
        offer(FakeImage.raw(8, 8, 1L));
        offer(FakeImage.raw(8, 8, 2L));
        SafeCloseImageReader.Frame first = reader.acquireNextImage();
        SafeCloseImageReader.Frame second = reader.acquireNextImage();
        assertNotNull(first);
        assertNotNull(second);
        reader.close();

        first.close();
        assertEquals(readersBefore + 1, ShadowInjectableImageReader.openReaderCount());

        second.close();
        assertEquals(readersBefore, ShadowInjectableImageReader.openReaderCount());
    }

    /** Closing a frame twice counts once, or the reader would be released under another. */
    @Test
    public void aFrameClosedTwiceCountsOnce() {
        offer(FakeImage.raw(8, 8, 1L));
        offer(FakeImage.raw(8, 8, 2L));
        SafeCloseImageReader.Frame first = reader.acquireNextImage();
        SafeCloseImageReader.Frame second = reader.acquireNextImage();
        assertNotNull(first);
        assertNotNull(second);
        reader.close();

        first.close();
        first.close();

        assertEquals("the second frame still holds the reader",
                readersBefore + 1, ShadowInjectableImageReader.openReaderCount());
        second.close();
        assertEquals(readersBefore, ShadowInjectableImageReader.openReaderCount());
    }

    /** A frame given back before the close leaves nothing to wait for. */
    @Test
    public void aFrameBackBeforeTheCloseDoesNotHoldIt() {
        offer(FakeImage.raw(8, 8, 1L));
        SafeCloseImageReader.Frame frame = reader.acquireLatestImage();
        assertNotNull(frame);
        frame.close();

        reader.close();
        reader.close();

        assertEquals(readersBefore, ShadowInjectableImageReader.openReaderCount());
    }

    /** A closed reader hands out nothing more, even with frames still queued in it. */
    @Test
    public void aClosedReaderHandsOutNothing() {
        offer(FakeImage.raw(8, 8, 1L));
        offer(FakeImage.raw(8, 8, 2L));
        SafeCloseImageReader.Frame held = reader.acquireNextImage();
        assertNotNull(held);
        reader.close();

        assertNull(reader.acquireNextImage());
        assertNull(reader.acquireLatestImage());
        held.close();
    }

    @Test
    public void anEmptyReaderHasNoFrame() {
        assertNull(reader.acquireNextImage());
        assertNull(reader.acquireLatestImage());
        reader.close();
        assertEquals(readersBefore, ShadowInjectableImageReader.openReaderCount());
    }

    private void offer(FakeImage image) {
        ImageReader latest = ShadowInjectableImageReader.latestReader();
        assertNotNull(latest);
        ShadowInjectableImageReader.offerImage(latest, image);
    }
}
