package com.xlythe.view.camera.shadow;

import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.view.Surface;

import androidx.annotation.Nullable;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.annotation.Resetter;
import org.robolectric.shadows.ShadowImageReader;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Lets a test hand an {@link ImageReader} a frame.
 *
 * <p>Robolectric shadows the reader's native buffer plumbing, but a buffer only becomes available
 * by drawing to the reader's Surface, and under test the camera that would draw it produces
 * nothing. So {@code acquireLatestImage} always returns null and the listener never fires, which
 * leaves every still-capture path unreachable: the code that decodes a frame, writes it, attaches
 * EXIF and reports success or failure is never entered.</p>
 *
 * <p>{@link #offerImage} queues a frame and dispatches the listener the way the framework would,
 * on the handler the caller registered. Readers register themselves as they are given a listener,
 * so a test can reach one that production code created for itself.</p>
 */
@Implements(ImageReader.class)
public class ShadowInjectableImageReader extends ShadowImageReader {
    private static final List<ImageReader> sReaders = new ArrayList<>();

    @RealObject private ImageReader realReader;

    private final Deque<Image> mImages = new ArrayDeque<>();
    @Nullable private ImageReader.OnImageAvailableListener mListener;
    @Nullable private Handler mHandler;

    /** The most recently created reader that registered a listener, or null if there is none. */
    @Nullable
    public static ImageReader latestReader() {
        synchronized (sReaders) {
            return sReaders.isEmpty() ? null : sReaders.get(sReaders.size() - 1);
        }
    }

    /**
     * How many readers are open.
     *
     * <p>An ImageReader holds native buffers the garbage collector cannot reclaim, so leaking one
     * per camera session is how a camera app runs out of graphics memory. Counting them is the
     * only way a unit test can see that.</p>
     */
    public static int openReaderCount() {
        synchronized (sReaders) {
            return sReaders.size();
        }
    }

    /** Describes the open readers, for a test that has to say which one leaked. */
    public static String describeOpenReaders() {
        StringBuilder description = new StringBuilder();
        synchronized (sReaders) {
            for (ImageReader reader : sReaders) {
                description.append(reader.getWidth()).append('x').append(reader.getHeight())
                        .append(" format=").append(reader.getImageFormat()).append("; ");
            }
        }
        return description.toString();
    }

    /** Queues a frame on a reader and tells its listener, as the framework would. */
    public static void offerImage(ImageReader reader, Image image) {
        ShadowInjectableImageReader shadow = org.robolectric.shadow.api.Shadow.extract(reader);
        shadow.offer(image);
    }

    @Resetter
    public static void reset() {
        synchronized (sReaders) {
            sReaders.clear();
        }
    }

    @Implementation
    protected void setOnImageAvailableListener(
            ImageReader.OnImageAvailableListener listener, Handler handler) {
        mListener = listener;
        mHandler = handler;
        register();
    }

    /**
     * Registers a reader that has handed out its surface.
     *
     * <p>A reader is reachable from a test once something could be drawing to it, which is not
     * only when it has a listener: a caller that polls {@code acquireLatestImage} on a thread of
     * its own never registers one.</p>
     */
    @Implementation
    protected Surface nativeGetSurface() {
        register();
        return super.nativeGetSurface();
    }

    @Implementation
    protected void close() {
        synchronized (sReaders) {
            sReaders.remove(realReader);
        }
        super.close();
    }

    private void register() {
        synchronized (sReaders) {
            sReaders.remove(realReader);
            sReaders.add(realReader);
        }
    }

    @Implementation
    @Nullable
    protected Image acquireLatestImage() {
        // Matches the real contract: everything older than the newest frame is dropped.
        Image latest = mImages.pollLast();
        for (Image stale : mImages) {
            stale.close();
        }
        mImages.clear();
        return latest;
    }

    @Implementation
    @Nullable
    protected Image acquireNextImage() {
        return mImages.pollFirst();
    }

    private void offer(Image image) {
        mImages.add(image);
        ImageReader.OnImageAvailableListener listener = mListener;
        if (listener == null) {
            return;
        }
        Runnable delivery = () -> listener.onImageAvailable(realReader);
        if (mHandler != null) {
            mHandler.post(delivery);
        } else {
            delivery.run();
        }
    }
}
