package com.xlythe.view.camera.stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A live video stream through this device's real encoder, read over a throttled link, adapting.
 *
 * <p>The camera is replaced by a surface drawn with a busy panning scene at 30fps. The reader takes the stream out at a rate the test sets, the way an app's
 * transport does over a slow link. The link runs fast, then slower than the stream, then slower
 * than the floor, then fast again, and the test records what the stream did at each step.</p>
 */
@RunWith(AndroidJUnit4.class)
public class StreamBitRateDeviceTest {
    private static final String TAG = "StreamBitRateDeviceTest";

    /** The stream's ceiling: the Wear sample's bit rate. */
    private static final int CEILING = 1_000_000;

    /** What the synthetic camera draws at. */
    private static final int CAMERA_FPS = 30;

    /** And its full size. */
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;

    /** How often the test samples the stream. */
    private static final long SAMPLE_MILLIS = 500;

    @Test
    public void theBitRateFollowsAThrottledLink() throws Exception {
        SyntheticCamera camera = new SyntheticCamera();
        PipedInputStream pipe = new PipedInputStream(1024 * 1024);
        LossyPipedOutputStream lossy = new LossyPipedOutputStream(pipe, 256 * 1024);
        VideoRecorder recorder = new VideoRecorder(camera, lossy);
        recorder.setBitRate(CEILING);
        recorder.setFrameRate(CAMERA_FPS);
        recorder.setIFrameInterval(1);
        DeliveryMeter meter = new DeliveryMeter();
        recorder.setDeliveryMeter(meter);
        ThrottledReader reader = new ThrottledReader(meter.wrap(pipe));

        List<Sample> samples = new ArrayList<>();
        recorder.start();
        reader.start();
        try {
            // Fast, then slower than the stream, then slower than its floor, then fast again.
            run(recorder, camera, reader, samples, 3_000_000, 8_000);
            run(recorder, camera, reader, samples, 300_000, 12_000);
            run(recorder, camera, reader, samples, 50_000, 40_000);
            run(recorder, camera, reader, samples, 3_000_000, 80_000);
        } finally {
            reader.stop();
            recorder.stop();
        }

        StringBuilder csv = new StringBuilder(
                "time_ms,link_bps,target_bps,estimate_bps,read_bps,level,encoded_fps,width,height,dropped\n");
        for (Sample sample : samples) {
            report(String.format(Locale.US, "t%06d", sample.time), sample.toString());
            csv.append(String.format(Locale.US, "%d,%d,%d,%d,%.0f,%d,%.2f,%d,%d,%d%n",
                    sample.time, sample.link, sample.bitRate, sample.estimate,
                    sample.deliveredBps, sample.level, sample.encodedFps, sample.width,
                    sample.height, sample.dropped));
        }
        TestSignal.save("bitrate-sdk" + Build.VERSION.SDK_INT + ".csv",
                csv.toString().getBytes(StandardCharsets.UTF_8));
        BitRateController controller = recorder.getController();
        assertNotNull(controller);

        // Cut fast: under the slower link within a second and a half of it slowing.
        long cutAt = firstTime(samples, 8_000, s -> s.bitRate <= 300_000);
        report("cut", "took " + (cutAt - 8_000) + "ms to get under 300kbps");
        assertTrue("took " + (cutAt - 8_000) + "ms", cutAt - 8_000 <= 1_500);

        // Tracks the slower link from just under it.
        double tracked = average(samples, 12_000, 20_000, s -> s.bitRate);
        report("tracked", Math.round(tracked) + "bps on a 300kbps link");
        assertTrue("averaged " + tracked, tracked > 150_000 && tracked < 330_000);

        // Below the floor: frames before pixels where the encoder can drop frames.
        Sample firstStep = first(samples, 20_000, s -> s.level > 0);
        assertNotNull("never gave anything up below the floor", firstStep);
        report("first-step", firstStep.toString());
        if (Build.VERSION.SDK_INT >= VideoRecorder.MAX_FPS_SDK) {
            assertEquals("the size is kept at the first step",
                    WIDTH * HEIGHT, firstStep.width * firstStep.height);
            double before = average(samples, 2_000, 8_000, s -> s.encodedFps);
            double after = average(samples, firstStep.time + 1_000, Long.MAX_VALUE,
                    s -> s.level == 1 ? s.encodedFps : Double.NaN);
            report("fps", String.format(Locale.US, "%.1ffps at full quality, %.1ffps with half"
                    + " the frames", before, after));
            // Half the camera's 30fps, with slack for a second that caught a burst.
            assertTrue("fps went from " + before + " to " + after, after <= CAMERA_FPS / 2.0 + 3);
        }
        Sample smaller = first(samples, 20_000, s -> s.width * s.height < WIDTH * HEIGHT);
        assertNotNull("never lowered the resolution", smaller);
        report("smaller", smaller.toString());
        assertTrue("the resolution only drops after the frame rate",
                Build.VERSION.SDK_INT < VideoRecorder.MAX_FPS_SDK
                        || smaller.time > firstStep.time);

        // Raise slowly and restore everything.
        Sample last = samples.get(samples.size() - 1);
        report("end", last.toString());
        assertEquals("full quality restored", 0, last.level);
        assertEquals(WIDTH * HEIGHT, last.width * last.height);
        double fiveSecondsIn = average(samples, 60_000, 61_000, s -> s.bitRate);
        assertTrue("climbed slowly: " + fiveSecondsIn, fiveSecondsIn < CEILING * 0.6);
    }

    /** Sets the link's rate and samples the stream until the phase is over. */
    private static void run(VideoRecorder recorder, SyntheticCamera camera, ThrottledReader reader,
            List<Sample> samples, int linkBitsPerSecond, long millis) throws InterruptedException {
        reader.bitsPerSecond = linkBitsPerSecond;
        long end = SystemClock.elapsedRealtime() + millis;
        while (SystemClock.elapsedRealtime() < end) {
            Thread.sleep(SAMPLE_MILLIS);
            samples.add(reader.sample(recorder, camera));
        }
    }

    private interface Condition {
        boolean test(Sample sample);
    }

    private interface Value {
        double of(Sample sample);
    }

    private static Sample first(List<Sample> samples, long from, Condition test) {
        for (Sample sample : samples) {
            if (sample.time >= from && test.test(sample)) {
                return sample;
            }
        }
        return null;
    }

    private static long firstTime(List<Sample> samples, long from, Condition test) {
        Sample sample = first(samples, from, test);
        return sample == null ? Long.MAX_VALUE : sample.time;
    }

    private static double average(List<Sample> samples, long from, long to, Value value) {
        double sum = 0;
        int count = 0;
        for (Sample sample : samples) {
            double v = value.of(sample);
            if (sample.time >= from && sample.time < to && !Double.isNaN(v)) {
                sum += v;
                count++;
            }
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    /** One look at the stream. */
    private static final class Sample {
        long time;
        int link;
        int bitRate;
        int estimate;
        int level;
        double deliveredBps;
        double encodedFps;
        long dropped;
        int width;
        int height;

        @Override
        public String toString() {
            return String.format(Locale.US,
                    "t=%5.1fs link=%4dk target=%4dk estimate=%4dk read=%4.0fk level=%d "
                            + "encoded=%4.1ffps size=%dx%d dropped=%d",
                    time / 1000.0, link / 1000, bitRate / 1000, estimate / 1000,
                    deliveredBps / 1000, level, encodedFps, width, height, dropped);
        }
    }

    /**
     * The app's transport: reads the stream at a set rate, and counts the frames it read.
     */
    private static final class ThrottledReader implements Runnable {
        /** What it reads from. */
        private final InputStream mIn;

        /** The link's rate. */
        volatile int bitsPerSecond;

        /** Bytes read. */
        private final AtomicLong mRead = new AtomicLong();

        /** Frames read. */
        private final AtomicLong mFrames = new AtomicLong();

        /** When the test began. */
        private final long mStart = SystemClock.elapsedRealtime();

        /** The thread reading. */
        private Thread mThread;

        /** Whether to keep reading. */
        private volatile boolean mRunning = true;

        /** The previous sample's counts, for rates. */
        private long mLastRead;
        private long mLastFrames;
        private long mLastDropped;
        private long mLastTime = -1;

        ThrottledReader(InputStream in) {
            mIn = in;
        }

        void start() {
            mThread = new Thread(this, "ThrottledReader");
            mThread.start();
        }

        void stop() throws InterruptedException {
            mRunning = false;
            mThread.interrupt();
            mThread.join(2_000);
        }

        @Override
        public void run() {
            byte[] chunk = new byte[2048];
            ByteBuffer pending = ByteBuffer.allocate(2 * 1024 * 1024);
            double allowance = 0;
            long last = SystemClock.elapsedRealtime();
            try {
                while (mRunning) {
                    long now = SystemClock.elapsedRealtime();
                    allowance = Math.min(allowance + bitsPerSecond / 8.0 * (now - last) / 1000,
                            bitsPerSecond / 8.0 / 10);
                    last = now;
                    if (allowance < 1) {
                        Thread.sleep(5);
                        continue;
                    }
                    int read = mIn.read(chunk, 0, (int) Math.min(chunk.length, allowance));
                    if (read < 0) {
                        return;
                    }
                    allowance -= read;
                    mRead.addAndGet(read);
                    pending.put(chunk, 0, read);
                    countFrames(pending);
                }
            } catch (IOException | InterruptedException e) {
                // Stopping closes the pipe or interrupts the wait; either ends the reader.
            }
        }

        /** Counts the whole frames waiting in the buffer, and keeps the partial one. */
        private void countFrames(ByteBuffer pending) {
            pending.flip();
            while (pending.remaining() >= 4) {
                int length = pending.getInt(pending.position());
                if (pending.remaining() < 4 + length) {
                    break;
                }
                byte[] frame = new byte[length];
                pending.position(pending.position() + 4);
                pending.get(frame);
                if (VideoFrame.fromBytes(frame).getType() == VideoFrame.Type.DATA) {
                    mFrames.incrementAndGet();
                }
            }
            pending.compact();
        }

        Sample sample(VideoRecorder recorder, SyntheticCamera camera) {
            long now = SystemClock.elapsedRealtime();
            long read = mRead.get();
            long frames = mFrames.get();
            long dropped = recorder.getDroppedFrameCount();
            Sample sample = new Sample();
            sample.time = now - mStart;
            sample.link = bitsPerSecond;
            BitRateController controller = recorder.getController();
            if (controller != null) {
                sample.bitRate = controller.getBitRate();
                sample.estimate = controller.getEstimate();
                sample.level = controller.getLevelIndex();
            }
            if (mLastTime >= 0) {
                double seconds = (now - mLastTime) / 1000.0;
                sample.deliveredBps = (read - mLastRead) * 8 / seconds;
                sample.encodedFps = ((frames - mLastFrames) + (dropped - mLastDropped)) / seconds;
            }
            sample.dropped = dropped;
            sample.width = camera.width;
            sample.height = camera.height;
            mLastTime = now;
            mLastRead = read;
            mLastFrames = frames;
            mLastDropped = dropped;
            return sample;
        }
    }

    /**
     * Stands in for a camera: draws a panning scene of coloured cells, a few of them changing
     * every frame, into whatever surface the recorder hands it, at 30fps, at the largest size
     * within the recorder's cap, the way a backend picks one. Busy enough that the encoder needs
     * its whole bit rate, and not so random that no bit rate is enough, as pure noise would be.
     *
     * <p>Drawn with its own EGL context, as a camera backend's renderer does. A hardware canvas
     * would share the view system's render thread, which on the emulator crashes destroying a
     * window surface whose encoder has already gone.</p>
     */
    private static final class SyntheticCamera implements VideoRecorder.Canvas {
        /** Cells across the picture, which scroll a cell a frame like a panning camera. */
        private static final int CELLS_ACROSS = 40;

        /** The share of cells that change colour each frame, as a scene's own movement. */
        private static final float CHANGING = 0.05f;

        /** The thread drawing, while a surface is attached. */
        private volatile Thread mThread;

        /** Whether to keep drawing. */
        private volatile boolean mRunning;

        /** The size being drawn. */
        volatile int width = WIDTH;
        volatile int height = HEIGHT;

        @Override
        public void attachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
            Size max = surfaceProvider.getMaxSize();
            float scale = 1;
            if (max != null) {
                scale = Math.min(1, Math.min((float) max.getWidth() / WIDTH,
                        (float) max.getHeight() / HEIGHT));
            }
            width = Math.max(16, (int) (WIDTH * scale) & ~15);
            height = Math.max(16, (int) (HEIGHT * scale) & ~15);
            mRunning = true;
            Thread thread = new Thread(() -> draw(surfaceProvider), "SyntheticCamera");
            mThread = thread;
            thread.start();
        }

        private void draw(VideoRecorder.SurfaceProvider surfaceProvider) {
            Surface surface;
            try {
                surface = surfaceProvider.getSurface(width, height, 0, false);
            } catch (RuntimeException e) {
                Log.w(TAG, "No surface", e);
                return;
            }
            EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            EGL14.eglInitialize(display, version, 0, version, 1);
            int[] attributes = {
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGLExt.EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0);
            EGLContext context = EGL14.eglCreateContext(display, configs[0],
                    EGL14.EGL_NO_CONTEXT, new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                            EGL14.EGL_NONE}, 0);
            EGLSurface window = EGL14.eglCreateWindowSurface(display, configs[0], surface,
                    new int[] {EGL14.EGL_NONE}, 0);
            EGL14.eglMakeCurrent(display, window, window, context);
            Random random = new Random();
            int cell = Math.max(1, width / CELLS_ACROSS);
            int columns = width / cell + 2;
            int rows = height / cell + 1;
            // A texture of random cells, wider than the picture, that the picture pans across.
            float[][] texture = new float[rows * (columns + 512)][];
            for (int i = 0; i < texture.length; i++) {
                texture[i] = new float[] {random.nextFloat(), random.nextFloat(),
                        random.nextFloat()};
            }
            long frame = 0;
            long start = SystemClock.elapsedRealtime();
            try {
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
                while (mRunning) {
                    int pan = (int) (frame % 512);
                    for (int row = 0; row < rows; row++) {
                        for (int column = 0; column < columns; column++) {
                            float[] colour = texture[row * (columns + 512) + column + pan];
                            if (random.nextFloat() < CHANGING) {
                                colour[0] = random.nextFloat();
                                colour[1] = random.nextFloat();
                                colour[2] = random.nextFloat();
                            }
                            GLES20.glScissor(column * cell, row * cell, cell, cell);
                            GLES20.glClearColor(colour[0], colour[1], colour[2], 1);
                            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                        }
                    }
                    EGLExt.eglPresentationTimeANDROID(display, window,
                            SystemClock.elapsedRealtimeNanos());
                    if (!EGL14.eglSwapBuffers(display, window)) {
                        // The encoder behind the surface has gone.
                        return;
                    }
                    frame++;
                    long due = start + frame * 1000 / CAMERA_FPS;
                    long wait = due - SystemClock.elapsedRealtime();
                    if (wait > 0) {
                        SystemClock.sleep(wait);
                    }
                }
            } finally {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
                EGL14.eglDestroySurface(display, window);
                EGL14.eglDestroyContext(display, context);
                EGL14.eglReleaseThread();
            }
        }

        @Override
        public void detachSurface(VideoRecorder.SurfaceProvider surfaceProvider) {
            mRunning = false;
            Thread thread = mThread;
            if (thread != null) {
                try {
                    thread.join(2_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /** Logs a measurement and puts it in the instrumentation output, where a run keeps it. */
    private static void report(String key, String value) {
        Log.i(TAG, key + ": " + value);
        Bundle bundle = new Bundle();
        bundle.putString(TAG + "." + key, value);
        InstrumentationRegistry.getInstrumentation().sendStatus(0, bundle);
    }
}
