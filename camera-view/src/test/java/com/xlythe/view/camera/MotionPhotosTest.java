package com.xlythe.view.camera;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Holding a picture back for its clip, whichever of the two arrives first, and handing on the
 * still as it was whenever the clip comes to nothing.
 *
 * <p>The clips are fakes that write a few bytes shaped like an MP4, and the pictures are read back
 * through {@link MediaTags}, as a viewer would.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class MotionPhotosTest {
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    /** A clip source whose clips the test hands out by hand. */
    private static final class FakeSource implements MotionPhotos.ClipSource {
        /** Whether a capture finds frames to cut a clip from. */
        boolean live = true;
        /** Every capture asked for, in order. */
        final List<FakeRequest> requests = new ArrayList<>();

        @Nullable
        @Override
        public MotionPhotos.ClipRequest capture(@NonNull MotionPhotos.ClipListener listener) {
            if (!live) {
                return null;
            }
            FakeRequest request = new FakeRequest(listener);
            requests.add(request);
            return request;
        }
    }

    private static final class FakeRequest implements MotionPhotos.ClipRequest {
        final MotionPhotos.ClipListener listener;
        boolean cancelled;

        FakeRequest(MotionPhotos.ClipListener listener) {
            this.listener = listener;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    /** A clip that writes a fixed number of MP4-shaped bytes, or fails to. */
    private static final class FakeClip implements MotionPhotos.ClipFile {
        final byte[] mp4;
        final boolean fails;
        File writtenTo;

        FakeClip(int length, boolean fails) {
            mp4 = fakeMp4(length);
            this.fails = fails;
        }

        @Override
        public long getPresentationTimestampUs() {
            return 1_500_000;
        }

        @Override
        public void writeTo(@NonNull File file) throws IOException {
            writtenTo = file;
            if (fails) {
                throw new IOException("The muxer refused");
            }
            try (OutputStream output = new FileOutputStream(file)) {
                output.write(mp4);
            }
        }
    }

    private FakeSource mSource;
    private MotionPhotos mMotionPhotos;
    private int mDelivered;

    @Before
    public void setUp() {
        mSource = new FakeSource();
        mMotionPhotos = new MotionPhotos(folder.getRoot(), Runnable::run);
    }

    private final Runnable mDeliver = () -> mDelivered++;

    private void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void noSourceOrNoFramesIsNoMotionPhoto() throws IOException {
        File picture = jpeg("a.jpg");

        assertFalse(mMotionPhotos.begin(picture, null));
        mSource.live = false;
        assertFalse(mMotionPhotos.begin(picture, mSource));
        assertFalse(mMotionPhotos.isPending());
        assertFalse("a picture with no clip coming is handed on by the caller",
                mMotionPhotos.finish(picture, mDeliver));
    }

    @Test
    public void aStillWaitsForItsClip() throws IOException {
        File picture = jpeg("a.jpg");
        assertTrue(mMotionPhotos.begin(picture, mSource));
        assertTrue(mMotionPhotos.isPending());

        assertTrue(mMotionPhotos.finish(picture, mDeliver));
        idle();
        assertEquals("not before the clip", 0, mDelivered);

        FakeClip clip = new FakeClip(5000, false);
        mSource.requests.get(0).listener.onClip(clip);
        idle();

        assertEquals(1, mDelivered);
        assertFalse(mMotionPhotos.isPending());
        assertMotionPhoto(picture, clip);
        assertFalse("the clip's own file is gone", clip.writtenTo.exists());
    }

    @Test
    public void aClipWaitsForItsStill() throws IOException {
        File picture = jpeg("a.jpg");
        mMotionPhotos.begin(picture, mSource);
        FakeClip clip = new FakeClip(3000, false);
        mSource.requests.get(0).listener.onClip(clip);
        idle();
        assertEquals(0, mDelivered);

        assertTrue(mMotionPhotos.finish(picture, mDeliver));
        idle();

        assertEquals(1, mDelivered);
        assertMotionPhoto(picture, clip);
    }

    @Test
    public void aClipThatComesToNothingLeavesThePlainStill() throws IOException {
        File picture = jpeg("a.jpg");
        byte[] before = Files.readAllBytes(picture.toPath());
        mMotionPhotos.begin(picture, mSource);

        mSource.requests.get(0).listener.onClip(null);
        mMotionPhotos.finish(picture, mDeliver);
        idle();

        assertEquals(1, mDelivered);
        assertArrayEquals(before, Files.readAllBytes(picture.toPath()));
    }

    @Test
    public void aClipThatCannotBeWrittenLeavesThePlainStill() throws IOException {
        File picture = jpeg("a.jpg");
        byte[] before = Files.readAllBytes(picture.toPath());
        mMotionPhotos.begin(picture, mSource);
        mMotionPhotos.finish(picture, mDeliver);

        FakeClip clip = new FakeClip(3000, true);
        mSource.requests.get(0).listener.onClip(clip);
        idle();

        assertEquals("handed on all the same", 1, mDelivered);
        assertArrayEquals(before, Files.readAllBytes(picture.toPath()));
        assertFalse(clip.writtenTo.exists());
    }

    @Test
    public void aFailedPictureAbandonsTheOldestClip() throws IOException {
        File first = jpeg("first.jpg");
        File second = jpeg("second.jpg");
        mMotionPhotos.begin(first, mSource);
        mMotionPhotos.begin(second, mSource);

        mMotionPhotos.fail();

        assertTrue(mSource.requests.get(0).cancelled);
        assertFalse(mSource.requests.get(1).cancelled);
        assertFalse("the failed picture is no longer waited for",
                mMotionPhotos.finish(first, mDeliver));
        assertTrue(mMotionPhotos.finish(second, mDeliver));
        FakeClip clip = new FakeClip(2000, false);
        mSource.requests.get(1).listener.onClip(clip);
        idle();
        assertEquals(1, mDelivered);
        assertMotionPhoto(second, clip);
    }

    @Test
    public void aFailureAfterEveryStillIsInAbandonsNothing() throws IOException {
        File picture = jpeg("a.jpg");
        mMotionPhotos.begin(picture, mSource);
        mMotionPhotos.finish(picture, mDeliver);

        mMotionPhotos.fail();
        mSource.requests.get(0).listener.onClip(null);
        idle();

        assertFalse(mSource.requests.get(0).cancelled);
        assertEquals(1, mDelivered);
    }

    @Test
    public void aFileNoClipWasAskedForIsNotHeld() throws IOException {
        mMotionPhotos.begin(jpeg("a.jpg"), mSource);

        assertFalse(mMotionPhotos.finish(jpeg("b.jpg"), mDeliver));
    }

    private static void assertMotionPhoto(File picture, FakeClip clip) throws IOException {
        MediaTags.MotionPhoto motionPhoto = MediaTags.open(picture).getMotionPhoto();
        assertNotNull("the picture is a motion photo", motionPhoto);
        assertEquals(clip.mp4.length, motionPhoto.getVideoLength());
        assertEquals(picture.length() - clip.mp4.length, motionPhoto.getVideoOffset());
        assertEquals(clip.getPresentationTimestampUs(),
                motionPhoto.getPresentationTimestampUs());
    }

    @Test
    public void aStillWithoutAClipIsNotAMotionPhoto() throws IOException {
        assertNull(MediaTags.open(jpeg("plain.jpg")).getMotionPhoto());
    }

    /** The smallest file with a JPEG's segments: a table, a scan and the end. */
    private File jpeg(String name) throws IOException {
        File file = new File(folder.getRoot(), name);
        byte[] table = new byte[65];
        try (OutputStream output = new FileOutputStream(file)) {
            output.write(new byte[] {(byte) 0xFF, (byte) 0xD8});
            output.write(new byte[] {(byte) 0xFF, (byte) 0xDB, 0, 67});
            output.write(table);
            output.write(new byte[] {(byte) 0xFF, (byte) 0xDA, 0, 8, 1, 1, 0, 0, 63, 0});
            output.write(new byte[] {0x12, 0x34, (byte) 0xFF, (byte) 0xD9});
        }
        return file;
    }

    /** Bytes that start the way an MP4 does, which is all a reader checks before playing it. */
    private static byte[] fakeMp4(int length) {
        byte[] video = new byte[length];
        ByteBuffer.wrap(video).putInt(24).put("ftyp".getBytes(StandardCharsets.US_ASCII));
        for (int i = 8; i < length; i++) {
            video[i] = (byte) i;
        }
        return video;
    }
}
