package com.xlythe.view.camera.v2;

import android.hardware.camera2.DngCreator;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.view.Surface;

import com.xlythe.view.camera.RawCapturePairer;

import java.io.IOException;
import java.io.OutputStream;

import androidx.annotation.AnyThread;
import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * An {@link ImageReader} that stays open until every frame it handed out has been closed.
 *
 * <p>Closing an ImageReader frees the buffers behind the frames it handed out, whether or not
 * anything is still reading them. A session rebuild closes its readers while a picture taken a
 * moment earlier may still be on its way to disk, and DngCreator reading a freed buffer takes the
 * process down rather than throwing. So {@link #close} only stops new frames, and the reader is
 * released by whichever comes last: the close, or the last frame being closed.</p>
 */
final class SafeCloseImageReader {
    /** Told when a frame is ready to acquire. */
    interface OnImageAvailableListener {
        void onImageAvailable(@NonNull SafeCloseImageReader reader);
    }

    /** The reader whose buffers the frames point into. Released only once nothing points in. */
    private final ImageReader mReader;

    /** How many frames are out and not yet closed. The reader cannot be released before zero. */
    @GuardedBy("this")
    private int mOutstandingFrames;

    /** Whether {@link #close} has been called, after which no frame is handed out. */
    @GuardedBy("this")
    private boolean mClosed;

    SafeCloseImageReader(int width, int height, int format, int maxImages) {
        mReader = ImageReader.newInstance(width, height, format, maxImages);
    }

    @NonNull
    Surface getSurface() {
        return mReader.getSurface();
    }

    void setOnImageAvailableListener(
            @NonNull OnImageAvailableListener listener, @Nullable Handler handler) {
        mReader.setOnImageAvailableListener(reader -> listener.onImageAvailable(this), handler);
    }

    /** The oldest frame waiting, or null if there is none or the reader is closed. */
    @AnyThread
    @Nullable
    synchronized Frame acquireNextImage() {
        return mClosed ? null : track(mReader.acquireNextImage());
    }

    /** The newest frame waiting, dropping any older ones, or null as above. */
    @AnyThread
    @Nullable
    synchronized Frame acquireLatestImage() {
        return mClosed ? null : track(mReader.acquireLatestImage());
    }

    /** Stops handing out frames, and releases the reader once the last one is closed. */
    @AnyThread
    synchronized void close() {
        if (mClosed) {
            return;
        }
        mClosed = true;
        mReader.setOnImageAvailableListener(null, null);
        releaseIfUnused();
    }

    @GuardedBy("this")
    @Nullable
    private Frame track(@Nullable Image image) {
        if (image == null) {
            return null;
        }
        mOutstandingFrames++;
        return new Frame(image);
    }

    private synchronized void onFrameClosed() {
        mOutstandingFrames--;
        releaseIfUnused();
    }

    @GuardedBy("this")
    private void releaseIfUnused() {
        if (mClosed && mOutstandingFrames == 0) {
            mReader.close();
        }
    }

    /**
     * A frame from this reader. Closing it gives the buffer back, and may release the reader.
     *
     * <p>Closed on whatever thread finished with it, which for a picture is the one that wrote it
     * to disk.</p>
     */
    final class Frame implements RawCapturePairer.Frame {
        /** The frame itself, valid until {@link #close}. */
        private final Image mImage;

        /** Whether this frame was closed, so a second close does not count twice. */
        @GuardedBy("SafeCloseImageReader.this")
        private boolean mClosed;

        private Frame(@NonNull Image image) {
            mImage = image;
        }

        /** The frame itself, valid until {@link #close}. */
        @NonNull
        Image getImage() {
            return mImage;
        }

        @Override
        public long getTimestamp() {
            return mImage.getTimestamp();
        }

        @Override
        public void writeTo(@NonNull DngCreator creator, @NonNull OutputStream output)
                throws IOException {
            creator.writeImage(output, mImage);
        }

        @AnyThread
        @Override
        public void close() {
            synchronized (SafeCloseImageReader.this) {
                if (mClosed) {
                    return;
                }
                mClosed = true;
                mImage.close();
                onFrameClosed();
            }
        }
    }
}
