package com.xlythe.view.camera.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import com.xlythe.view.camera.ArAnchor;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.gl.FrameRenderer;
import com.xlythe.view.camera.gl.GlFrame;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Whether AR starts on a real phone, hands each frame what it knows, and gives the camera back.
 *
 * <p>The parts of AR that cannot be reasoned about off a device. A shared-camera session either
 * configures or it does not, and when it does not it fails by drawing nothing or tracking nothing
 * rather than by throwing, so the first test asserts that frames arrive with a camera's
 * projection, and logs under {@value #TAG} how tracking went, whether depth arrived, and what the
 * frames were drawn with. The second asserts what tracking promises: that it starts, that the matrices describe a
 * camera, that an anchor stays where it was put, and that measured depth hides what is behind
 * the scene.</p>
 *
 * <p>Skips where AR cannot run, which is a real configuration: no ARCore, a phone Google does not
 * support for it, or one whose camera ARCore will not use. Tracking needs light, something to see
 * and a little movement, so the second test skips, saying which, where the phone cannot track. On
 * an emulator whose virtual scene camera it is told how to move, see {@link EmulatorScene}, the
 * second test walks the camera through the room itself and so never skips for want of movement.</p>
 */
@RunWith(AndroidJUnit4.class)
public final class ArDeviceTest {
    /** The tag every result is logged under. */
    private static final String TAG = "ArDeviceTest";

    /** How long the camera is given to open, or to reopen around a change. */
    private static final long OPEN_TIMEOUT_SECONDS = 30;

    /** How long tracking is watched for. A still phone may never leave INITIALIZING. */
    private static final long WATCH_MILLIS = 20_000;

    /** How long ARCore is given to say whether it runs here, which it answers asynchronously. */
    private static final long SUPPORT_TIMEOUT_MILLIS = 5_000;

    /** How long a picture is given to arrive. */
    private static final long PICTURE_TIMEOUT_SECONDS = 15;

    /** How long AR is given to start tracking, which needs the phone to move a little. */
    private static final long TRACKING_TIMEOUT_MILLIS = 30_000;

    /** How long a surface is looked for under the middle of the view once AR tracks. */
    private static final long ANCHOR_TIMEOUT_MILLIS = 20_000;

    /** How long an anchor is watched for drift. */
    private static final long DRIFT_WATCH_MILLIS = 10_000;

    /** How long the first depth measurement, or a check against it, is waited for. */
    private static final long DEPTH_TIMEOUT_MILLIS = 10_000;

    /** The near clipping plane every projection is asked for, in metres. */
    private static final float NEAR = 0.05f;

    /** The far clipping plane every projection is asked for, in metres. */
    private static final float FAR = 100f;

    /**
     * How far an anchor may move in the world while it is watched. ARCore refines it as it learns
     * the room, by a centimetre or two; a lost or misplaced anchor moves much further.
     */
    private static final float MAX_ANCHOR_DRIFT_METRES = 0.05f;

    /**
     * How far from the middle of the frame, in normalized device coordinates, an anchor placed
     * under the middle of the view may land, allowing for the phone moving between the two.
     */
    private static final float MAX_ANCHOR_OFFSET = 0.15f;

    /** How much of a frame has to carry a distance for depth to count as measured. */
    private static final float MIN_DEPTH_COVERAGE = 0.25f;

    /**
     * How much more, over the middle pixel's three channels, a light in front of the scene
     * brightens it than one behind the scene, which depth hides.
     */
    private static final int MIN_OCCLUSION_CONTRAST = 60;

    /** How much a light behind the scene may still brighten the middle pixel: rounding. */
    private static final int MAX_HIDDEN_LIGHT = 6;

    @Rule
    public final GrantPermissionRule permissions =
            GrantPermissionRule.grant(Manifest.permission.CAMERA);

    @Before
    public void needsACamera() {
        DeviceState.assumeATestableCamera();
    }

    @After
    public void restoreTheDefault() {
        CameraView.setDefaultBackend(CameraView.Backend.AUTO);
    }

    /**
     * Keeps the emulator's camera moving as a hand-held phone does: drifting side to side and a
     * little forward at a speed that changes gently, and turning a few degrees, while looking down
     * at the room, so AR sees it from more than one place and finds the floor.
     *
     * <p>The camera is steered by its speed, not by where it should be next: a new place every
     * step starts and stops it each time, jolts AR loses its footing on, where a gently changing
     * speed is the steady motion it tracks.</p>
     */
    private static final class Walk implements AutoCloseable {
        /** How often the camera is given a new speed and a new way to face. */
        private static final long STEP_MILLIS = 250;

        /** The camera's top speed across the room, in metres a second. */
        private static final double SPEED = 0.15;

        /** How fast the speed swings from side to side, in radians a second: about 11 s a sway. */
        private static final double SWAY_RADIANS_PER_SECOND = 0.55;

        /** How far the camera looks down, in degrees. */
        private static final float PITCH_DEGREES = -25f;

        /**
         * How many steps in a row the emulator may refuse before the walk gives up. The guest's
         * network drops the odd connection to the host, which costs one step, not the walk.
         */
        private static final int MAX_FAILED_STEPS = 5;

        /** The camera being walked. */
        private final EmulatorScene mScene;

        /** Moves the camera until closed. */
        private final Thread mThread;

        /** Why the camera stopped moving early, or null while it moves. */
        private volatile IOException mFailure;

        /** Whether the walk has been asked to stop. */
        private volatile boolean mStopped;

        /** Starts from where the camera began, facing the way the walk looks. */
        Walk(@NonNull EmulatorScene scene) throws IOException {
            mScene = scene;
            scene.moveTo(0f, 0f, 0f);
            scene.turnTo(PITCH_DEGREES, 0f, 0f);
            mThread = new Thread(this::walk, "ArDeviceTestWalk");
            mThread.start();
        }

        private void walk() {
            long start = SystemClock.uptimeMillis();
            int failedSteps = 0;
            while (!mStopped) {
                double seconds = (SystemClock.uptimeMillis() - start) / 1000.0;
                double sway = SWAY_RADIANS_PER_SECOND * seconds;
                try {
                    mScene.moveAt((float) (SPEED * Math.cos(sway)), 0f,
                            (float) (-0.3 * SPEED * Math.sin(sway)));
                    mScene.turnTo((float) (PITCH_DEGREES + 3 * Math.sin(0.33 * seconds)),
                            (float) (8 * Math.sin(0.23 * seconds)), 0f);
                    failedSteps = 0;
                } catch (IOException error) {
                    if (++failedSteps >= MAX_FAILED_STEPS) {
                        mFailure = error;
                        return;
                    }
                }
                try {
                    Thread.sleep(STEP_MILLIS);
                } catch (InterruptedException error) {
                    return;
                }
            }
        }

        /** Where the camera is, in metres. */
        @NonNull
        float[] getPosition() throws IOException {
            return mScene.getPosition();
        }

        /** Fails if the camera stopped moving because the emulator would not move it. */
        void assertMoving() {
            IOException failure = mFailure;
            if (failure != null) {
                throw new AssertionError("the emulator stopped moving its camera", failure);
            }
        }

        /** Stops the camera where it is, so it does not drift on through the room. */
        @Override
        public void close() {
            mStopped = true;
            mThread.interrupt();
            try {
                mThread.join();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            try {
                mScene.moveAt(0f, 0f, 0f);
            } catch (IOException error) {
                Log.w(TAG, "the emulator's camera may still be moving", error);
            }
            mScene.close();
        }
    }

    /** Counts what each frame carried, and stands a light a metre and a half ahead once tracked. */
    private static final class Probe implements FrameRenderer {
        /** Frames drawn into any output. */
        final AtomicInteger frames = new AtomicInteger();
        /** Frames that knew their projection. */
        final AtomicInteger projected = new AtomicInteger();
        /** Frames that knew where the camera was. */
        final AtomicInteger viewed = new AtomicInteger();
        /** Frames that carried depth. */
        final AtomicInteger withDepth = new AtomicInteger();
        /** When the first frame with depth arrived, on the uptime clock, or 0. */
        final AtomicLong firstDepthAt = new AtomicLong();
        /** The last projection seen, for the log. */
        final float[] projection = new float[16];
        /** The last view seen, for the log. */
        final float[] view = new float[16];
        /** Asks the next frame with depth to read the depth back, from the test thread. */
        final AtomicBoolean readDepth = new AtomicBoolean();
        /** What the last read said: {coverage from 0 to 1, median metres, metres at the middle}. */
        volatile float[] depthRead;
        /**
         * Where the occlusion check is: 0 idle, 1 standing a light in front of the scene at the
         * middle of the frame, 2 standing one behind it, 3 done. Started by the test, advanced by
         * the frames.
         */
        final AtomicInteger occlusionStage = new AtomicInteger();
        /**
         * How much the light in front and the light behind brightened the middle pixel, its three
         * channels summed, and how far away the scene there is in metres. Written by the frames
         * before each stage is advanced, so read once the check is done.
         */
        final float[] occlusion = new float[3];
        /** The program that reads the depth back. */
        private int mDepthProgram;
        /** A square covering the viewport, for reading the depth back. */
        private final FloatBuffer mSquare = ByteBuffer.allocateDirect(8 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().put(new float[] {
                        -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f});
        /** Where the light stands, fixed by the first tracked frame. */
        private float[] mLight;
        /** The light's program. */
        private int mProgram;
        /** The quad. */
        private final FloatBuffer mQuad = ByteBuffer.allocateDirect(8 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().put(new float[] {
                        -1f, 0f, 1f, 0f, -1f, 1f, 1f, 1f});

        @Override
        public void onRendererStarted() {
            mProgram = PillarProgram.link();
            mDepthProgram = DepthProgram.link();
        }

        @Override
        public void onRendererStopped() {
            GLES20.glDeleteProgram(mProgram);
            GLES20.glDeleteProgram(mDepthProgram);
        }

        @Override
        public boolean onDrawFrame(@NonNull GlFrame frame) {
            int depthTexture = frame.getDepthTextureName();
            int stage = occlusionStage.get();
            if (depthTexture != 0 && (readDepth.get() || stage == 1)) {
                // Read before the camera frame, which then draws over the corner it used.
                depthRead = DepthProgram.read(mDepthProgram, mSquare, frame, depthTexture);
                readDepth.set(false);
            }
            frame.drawCameraFrame();
            if (frames.incrementAndGet() % 60 == 0) {
                ByteBuffer pixel = ByteBuffer.allocateDirect(4);
                GLES20.glReadPixels(frame.getWidth() / 2, frame.getHeight() / 2, 1, 1,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixel);
                Log.i(TAG, "the middle of frame " + frames.get() + " drawn "
                        + frame.getWidth() + "x" + frame.getHeight() + " from texture "
                        + frame.getTextureName() + " is " + (pixel.get(0) & 0xff) + ","
                        + (pixel.get(1) & 0xff) + "," + (pixel.get(2) & 0xff)
                        + " matrix " + java.util.Arrays.toString(frame.getTextureMatrix()));
            }
            float[] p = new float[16];
            float[] v = new float[16];
            boolean hasProjection = frame.getProjectionMatrix(p, NEAR, FAR);
            boolean hasView = frame.getViewMatrix(v);
            if (hasProjection) {
                projected.incrementAndGet();
                synchronized (projection) {
                    System.arraycopy(p, 0, projection, 0, 16);
                }
            }
            if (hasView) {
                viewed.incrementAndGet();
                synchronized (view) {
                    System.arraycopy(v, 0, view, 0, 16);
                }
            }
            if (depthTexture != 0) {
                withDepth.incrementAndGet();
                firstDepthAt.compareAndSet(0, SystemClock.uptimeMillis());
            }
            if ((stage == 1 || stage == 2) && depthTexture != 0 && hasProjection && hasView) {
                checkOcclusion(frame, stage, p, v);
            }
            return true;
        }

        /**
         * One step of the occlusion check: a light stood at the middle of the frame, in front of
         * the scene there on the first and behind it on the second, and how much it brightened
         * the middle pixel. Measured within one frame, so the camera's own noise does not count.
         */
        private void checkOcclusion(@NonNull GlFrame frame, int stage, @NonNull float[] p,
                @NonNull float[] v) {
            float scene;
            if (stage == 1) {
                float[] read = depthRead;
                scene = read == null ? 0f : read[2];
                if (scene <= 0f) {
                    // Nothing measured at the middle in this frame.
                    return;
                }
                occlusion[2] = scene;
            } else {
                scene = occlusion[2];
            }
            float[] middle = pointAtMiddle(p, v, stage == 1 ? scene / 2f : scene * 2f + 0.5f);
            int before = middleBrightness(frame);
            // Stood a metre below the point, so the pillar runs up through the middle of the frame.
            PillarProgram.draw(mProgram, mQuad, frame, p, v,
                    new float[] {middle[0], middle[1] - 1f, middle[2]});
            occlusion[stage - 1] = middleBrightness(frame) - before;
            occlusionStage.set(stage + 1);
        }

        /** Stands the light on the next tracked frame, a metre and a half ahead of the camera. */
        void drawLight(@NonNull GlFrame frame, @NonNull float[] p, @NonNull float[] v) {
            if (mLight == null) {
                // The camera looks down -z of its own space, which is the view's third row.
                float[] position = cameraPosition(v);
                mLight = new float[] {
                        position[0] - 1.5f * v[2],
                        position[1] - 1.5f * v[6] - 1f,
                        position[2] - 1.5f * v[10]};
            }
            PillarProgram.draw(mProgram, mQuad, frame, p, v, mLight);
        }
    }

    /** Where the camera stands in the world, from a view matrix. */
    @NonNull
    static float[] cameraPosition(@NonNull float[] v) {
        return new float[] {
                -(v[0] * v[12] + v[1] * v[13] + v[2] * v[14]),
                -(v[4] * v[12] + v[5] * v[13] + v[6] * v[14]),
                -(v[8] * v[12] + v[9] * v[13] + v[10] * v[14])};
    }

    /** The middle pixel of what has been drawn so far, its three channels summed. */
    private static int middleBrightness(@NonNull GlFrame frame) {
        ByteBuffer pixel = ByteBuffer.allocateDirect(4);
        GLES20.glReadPixels(frame.getWidth() / 2, frame.getHeight() / 2, 1, 1,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixel);
        return (pixel.get(0) & 0xff) + (pixel.get(1) & 0xff) + (pixel.get(2) & 0xff);
    }

    /**
     * The point in the world drawn at the middle of the frame, a distance along the camera's view
     * direction away, as depth measures it.
     */
    @NonNull
    static float[] pointAtMiddle(@NonNull float[] p, @NonNull float[] v, float distance) {
        // The middle of the frame in clip space at that depth. Its w is the distance, since the
        // projection's last row takes -z.
        float[] clip = {0f, 0f, p[10] * -distance + p[14], distance};
        float[] inverseProjection = new float[16];
        float[] inverseView = new float[16];
        assertTrue("the projection cannot be inverted", Matrix.invertM(inverseProjection, 0, p, 0));
        assertTrue("the view cannot be inverted", Matrix.invertM(inverseView, 0, v, 0));
        float[] eye = new float[4];
        Matrix.multiplyMV(eye, 0, inverseProjection, 0, clip, 0);
        float[] world = new float[4];
        Matrix.multiplyMV(world, 0, inverseView, 0, eye, 0);
        return new float[] {world[0] / world[3], world[1] / world[3], world[2] / world[3]};
    }

    /** Where a point in the world lands in the frame: {x, y} in normalized device coordinates. */
    @NonNull
    static float[] project(@NonNull float[] p, @NonNull float[] v, @NonNull float[] point) {
        float[] viewProjection = new float[16];
        Matrix.multiplyMM(viewProjection, 0, p, 0, v, 0);
        float[] clip = new float[4];
        Matrix.multiplyMV(clip, 0, viewProjection, 0,
                new float[] {point[0], point[1], point[2], 1f}, 0);
        return new float[] {clip[0] / clip[3], clip[1] / clip[3]};
    }

    /**
     * Fails unless a projection is a camera's: perspective, between the clipping planes it was
     * asked for, with an angle of view a lens could have and its optical centre near the middle.
     * The frame may be turned a quarter from the camera's space, so the focal lengths sit either
     * on the diagonal or across it.
     */
    static void assertCameraProjection(@NonNull float[] p, float near, float far) {
        String described = java.util.Arrays.toString(p);
        float tolerance = 1e-3f;
        boolean straight = Math.abs(p[1]) < tolerance && Math.abs(p[4]) < tolerance;
        boolean turned = Math.abs(p[0]) < tolerance && Math.abs(p[5]) < tolerance;
        assertTrue("the focal lengths are neither straight nor a quarter turn: " + described,
                straight != turned);
        float across = straight ? Math.abs(p[0]) : Math.abs(p[4]);
        float up = straight ? Math.abs(p[5]) : Math.abs(p[1]);
        // From about 20 to 130 degrees, telephoto to ultra-wide.
        assertTrue("an angle of view no lens has: " + described,
                across > 0.45f && across < 5.7f && up > 0.45f && up < 5.7f);
        assertTrue("the optical centre is far from the middle: " + described,
                Math.abs(p[8]) < 0.25f && Math.abs(p[9]) < 0.25f);
        assertEquals("not a perspective projection: " + described, -1f, p[11], tolerance);
        for (int i : new int[] {2, 3, 6, 7, 12, 13, 15}) {
            assertEquals("not a perspective projection: " + described, 0f, p[i], tolerance);
        }
        assertEquals("not clipped where asked: " + described,
                -(far + near) / (far - near), p[10], tolerance);
        assertEquals("not clipped where asked: " + described,
                -2f * far * near / (far - near), p[14], tolerance);
    }

    /** Fails unless a matrix only turns and moves, as every pose and view does. */
    static void assertRigid(@NonNull String what, @NonNull float[] m) {
        String described = what + " " + java.util.Arrays.toString(m);
        float tolerance = 1e-3f;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                float dot = m[i * 4] * m[j * 4] + m[i * 4 + 1] * m[j * 4 + 1]
                        + m[i * 4 + 2] * m[j * 4 + 2];
                assertEquals("does not keep lengths and angles: " + described,
                        i == j ? 1f : 0f, dot, tolerance);
            }
        }
        float determinant = m[0] * (m[5] * m[10] - m[9] * m[6])
                - m[4] * (m[1] * m[10] - m[9] * m[2])
                + m[8] * (m[1] * m[6] - m[5] * m[2]);
        assertEquals("mirrors: " + described, 1f, determinant, tolerance);
        for (int i : new int[] {3, 7, 11}) {
            assertEquals("not a pose: " + described, 0f, m[i], tolerance);
        }
        assertEquals("not a pose: " + described, 1f, m[15], tolerance);
    }

    /** The distance between two points. */
    private static float distance(@NonNull float[] a, @NonNull float[] b) {
        float x = a[0] - b[0];
        float y = a[1] - b[1];
        float z = a[2] - b[2];
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    /**
     * Reads the depth back, sampled across the frame the way {@link PillarProgram} samples it, by
     * drawing it small into a corner of the output and reading that corner.
     */
    private static final class DepthProgram {
        /** How many samples across and up the frame are read. */
        private static final int SIZE = 32;

        /** Covers the viewport, carrying where in the frame each corner is. */
        private static final String VERTEX =
                "attribute vec2 aPosition;\n"
                        + "varying vec2 vFrame;\n"
                        + "void main() {\n"
                        + "  vFrame = aPosition * 0.5 + 0.5;\n"
                        + "  gl_Position = vec4(aPosition, 0.0, 1.0);\n"
                        + "}\n";

        /** Writes the depth's two bytes, low then high, as red and green. */
        private static final String FRAGMENT =
                "precision highp float;\n"
                        + "uniform sampler2D uDepth;\n"
                        + "uniform mat4 uTexMatrix;\n"
                        + "varying vec2 vFrame;\n"
                        + "void main() {\n"
                        + "  vec2 camera = (uTexMatrix * vec4(vFrame, 0.0, 1.0)).xy;\n"
                        + "  gl_FragColor = vec4(texture2D(uDepth, camera).ra, 0.0, 1.0);\n"
                        + "}\n";

        private DepthProgram() {}

        static int link() {
            int program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, PillarProgram.compile(GLES20.GL_VERTEX_SHADER, VERTEX));
            GLES20.glAttachShader(program,
                    PillarProgram.compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT));
            GLES20.glLinkProgram(program);
            return program;
        }

        /**
         * Returns {how much of the frame has a distance, from 0 to 1, the median distance in
         * metres, the distance at the middle in metres or 0 where it is unknown}.
         */
        @NonNull
        static float[] read(int program, @NonNull FloatBuffer square, @NonNull GlFrame frame,
                int depth) {
            int[] viewport = new int[4];
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0);
            GLES20.glViewport(0, 0, SIZE, SIZE);
            GLES20.glUseProgram(program);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1,
                    false, frame.getTextureMatrix(), 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depth);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uDepth"), 0);
            int position = GLES20.glGetAttribLocation(program, "aPosition");
            square.position(0);
            GLES20.glEnableVertexAttribArray(position);
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, square);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(position);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            GLES20.glUseProgram(0);
            ByteBuffer pixels = ByteBuffer.allocateDirect(SIZE * SIZE * 4);
            GLES20.glReadPixels(0, 0, SIZE, SIZE, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                    pixels);
            GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);

            int[] millimetres = new int[SIZE * SIZE];
            int measured = 0;
            for (int i = 0; i < SIZE * SIZE; i++) {
                int value = millimetresAt(pixels, i);
                if (value != 0) {
                    millimetres[measured++] = value;
                }
            }
            java.util.Arrays.sort(millimetres, 0, measured);
            return new float[] {
                    measured / (float) (SIZE * SIZE),
                    measured == 0 ? 0f : millimetres[measured / 2] / 1000f,
                    millimetresAt(pixels, SIZE / 2 * SIZE + SIZE / 2) / 1000f};
        }

        /** The distance read back into one pixel, in millimetres. */
        private static int millimetresAt(@NonNull ByteBuffer pixels, int index) {
            return (pixels.get(index * 4) & 0xff) | ((pixels.get(index * 4 + 1) & 0xff) << 8);
        }
    }

    /** Draws a glowing quad facing the camera, hidden where the scene is nearer. */
    private static final class PillarProgram {
        private static final String VERTEX =
                "uniform mat4 uProjection;\n"
                        + "uniform mat4 uView;\n"
                        + "uniform vec3 uBase;\n"
                        + "uniform vec3 uAcross;\n"
                        + "attribute vec2 aCorner;\n"
                        + "varying vec2 vCorner;\n"
                        + "varying vec4 vClip;\n"
                        + "varying float vDistance;\n"
                        + "void main() {\n"
                        + "  vec3 world = uBase + uAcross * aCorner.x + vec3(0.0, 3.0 * aCorner.y, 0.0);\n"
                        + "  vec4 eye = uView * vec4(world, 1.0);\n"
                        + "  vDistance = -eye.z;\n"
                        + "  vClip = uProjection * eye;\n"
                        + "  vCorner = aCorner;\n"
                        + "  gl_Position = vClip;\n"
                        + "}\n";
        private static final String FRAGMENT =
                "precision highp float;\n"
                        + "uniform sampler2D uDepth;\n"
                        + "uniform mat4 uTexMatrix;\n"
                        + "uniform float uHasDepth;\n"
                        + "varying vec2 vCorner;\n"
                        + "varying vec4 vClip;\n"
                        + "varying float vDistance;\n"
                        + "void main() {\n"
                        + "  float light = pow(1.0 - abs(vCorner.x), 2.0) * (1.0 - vCorner.y);\n"
                        + "  if (uHasDepth > 0.5) {\n"
                        + "    vec2 frame = vClip.xy / vClip.w * 0.5 + 0.5;\n"
                        + "    vec2 camera = (uTexMatrix * vec4(frame, 0.0, 1.0)).xy;\n"
                        + "    float scene = dot(texture2D(uDepth, camera).ra, vec2(255.0, 65280.0)) / 1000.0;\n"
                        + "    if (scene > 0.0) light *= clamp((scene - vDistance) / 0.1 + 1.0, 0.0, 1.0);\n"
                        + "  }\n"
                        + "  gl_FragColor = vec4(vec3(1.0, 0.86, 0.5) * light, light);\n"
                        + "}\n";

        static int link() {
            int program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, VERTEX));
            GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT));
            GLES20.glLinkProgram(program);
            return program;
        }

        static int compile(int type, @NonNull String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            return shader;
        }

        static void draw(int program, @NonNull FloatBuffer quad, @NonNull GlFrame frame,
                @NonNull float[] p, @NonNull float[] v, @NonNull float[] base) {
            float[] camera = cameraPosition(v);
            float towardX = camera[0] - base[0];
            float towardZ = camera[2] - base[2];
            float length = (float) Math.max(1e-3, Math.hypot(towardX, towardZ));
            GLES20.glUseProgram(program);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uProjection"), 1,
                    false, p, 0);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uView"), 1, false,
                    v, 0);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1,
                    false, frame.getTextureMatrix(), 0);
            GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uBase"), base[0], base[1],
                    base[2]);
            GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uAcross"),
                    towardZ / length * 0.15f, 0f, -towardX / length * 0.15f);
            int depth = frame.getDepthTextureName();
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uHasDepth"),
                    depth == 0 ? 0f : 1f);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depth);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uDepth"), 0);
            int corner = GLES20.glGetAttribLocation(program, "aCorner");
            quad.position(0);
            GLES20.glEnableVertexAttribArray(corner);
            GLES20.glVertexAttribPointer(corner, 2, GLES20.GL_FLOAT, false, 0, quad);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(corner);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glUseProgram(0);
        }
    }

    /** A probe that also draws the light, for the picture. */
    private static final class LitProbe implements FrameRenderer {
        private final Probe mProbe;

        LitProbe(@NonNull Probe probe) {
            mProbe = probe;
        }

        @Override
        public void onRendererStarted() {
            mProbe.onRendererStarted();
        }

        @Override
        public void onRendererStopped() {
            mProbe.onRendererStopped();
        }

        @Override
        public boolean onDrawFrame(@NonNull GlFrame frame) {
            mProbe.onDrawFrame(frame);
            float[] p = new float[16];
            float[] v = new float[16];
            if (frame.getProjectionMatrix(p, NEAR, FAR) && frame.getViewMatrix(v)) {
                mProbe.drawLight(frame, p, v);
            }
            return true;
        }
    }

    /**
     * AR opens the camera, every frame knows its projection, tracking is reported, a picture is
     * drawn from the frames, and leaving AR gives the camera back still projecting from the lens's
     * own calibration.
     */
    @Test
    public void arHandsEveryFrameItsGeometryAndGivesTheCameraBack() throws Exception {
        Probe probe = new Probe();
        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            AtomicReference<CameraView> held = new AtomicReference<>();
            AtomicReference<CountDownLatch> opened =
                    new AtomicReference<>(new CountDownLatch(1));
            scenario.onActivity(activity -> {
                CameraView camera = activity.getCameraView();
                held.set(camera);
                camera.setFrameRenderer(new LitProbe(probe));
                camera.setOnCameraStateChangedListener(
                        new CameraView.OnCameraStateChangedListener() {
                            @Override public void onCameraOpened() {
                                opened.get().countDown();
                            }

                            @Override public void onCameraClosed() {}
                        });
                camera.open();
            });
            assertTrue("the camera did not open " + DeviceState.whyTheCameraMightNotOpen(),
                    opened.get().await(OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            CameraView camera = held.get();
            String backend = onMain(scenario, () -> camera.getBackend().name());

            long deadline = SystemClock.uptimeMillis() + SUPPORT_TIMEOUT_MILLIS;
            boolean supported = onMain(scenario, camera::isArSupported);
            while (!supported && SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(250);
                supported = onMain(scenario, camera::isArSupported);
            }
            Log.i(TAG, "AR supported: " + supported + " (camera opened on " + backend + ")");
            Assume.assumeTrue("AR cannot run on this phone", supported);

            List<String> tracking = Collections.synchronizedList(new ArrayList<>());
            long[] start = new long[1];
            opened.set(new CountDownLatch(1));
            AtomicBoolean entered = new AtomicBoolean();
            scenario.onActivity(activity -> {
                start[0] = SystemClock.uptimeMillis();
                entered.set(camera.enterAr(state -> {
                    String entry = (SystemClock.uptimeMillis() - start[0]) + "ms " + state;
                    tracking.add(entry);
                    Log.i(TAG, "tracking " + entry);
                }));
            });
            assumeArStarted(scenario, camera, entered.get());
            assertTrue("the camera did not reopen for AR",
                    opened.get().await(OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            int framesBefore = probe.frames.get();
            Log.i(TAG, "reopened for AR after " + (SystemClock.uptimeMillis() - start[0])
                    + "ms; depth supported " + onMain(scenario, camera::isArDepthSupported));

            Thread.sleep(WATCH_MILLIS);
            int frames = probe.frames.get() - framesBefore;
            Log.i(TAG, "over " + WATCH_MILLIS + "ms of AR: " + frames + " frames drawn, "
                    + probe.projected.get() + " projected, " + probe.viewed.get()
                    + " tracked, " + probe.withDepth.get() + " with depth"
                    + (probe.firstDepthAt.get() == 0 ? ""
                            : ", first depth at "
                                    + (probe.firstDepthAt.get() - start[0]) + "ms"));
            float[] arProjection = snapshot(probe.projection);
            Log.i(TAG, "projection " + java.util.Arrays.toString(arProjection));
            synchronized (probe.view) {
                Log.i(TAG, "view " + java.util.Arrays.toString(probe.view));
            }
            Log.i(TAG, "tracking went " + tracking);
            int projectedInAr = probe.projected.get();

            Object anchor = onMain(scenario,
                    () -> camera.addArAnchor(camera.getWidth() / 2f, camera.getHeight() / 2f));
            Log.i(TAG, "an anchor at the middle of the view: " + anchor);

            File picture = takePicture(scenario, camera);
            assertNotNull("an AR picture was not written", picture);
            Bitmap decoded = BitmapFactory.decodeFile(picture.getAbsolutePath());
            assertNotNull("the AR picture is not an image", decoded);
            Log.i(TAG, "an AR picture is " + decoded.getWidth() + "x" + decoded.getHeight()
                    + ", mean brightness " + meanBrightness(decoded));
            keep(picture, "ar-picture.jpg");

            opened.set(new CountDownLatch(1));
            scenario.onActivity(activity -> camera.exitAr());
            assertTrue("the camera did not reopen after AR",
                    opened.get().await(OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals("leaving AR gives back the camera that was there",
                    backend, onMain(scenario, () -> camera.getBackend().name()));
            int projectedBefore = probe.projected.get();
            int viewedBefore = probe.viewed.get();
            Thread.sleep(3_000);
            Log.i(TAG, "after AR: " + (probe.projected.get() - projectedBefore)
                    + " frames projected from the lens's calibration, "
                    + (probe.viewed.get() - viewedBefore) + " placed relative to the phone");
            float[] calibratedProjection = snapshot(probe.projection);
            float[] calibratedView = snapshot(probe.view);
            Log.i(TAG, "calibrated projection " + java.util.Arrays.toString(calibratedProjection));
            Log.i(TAG, "view from the phone " + java.util.Arrays.toString(calibratedView));
            File plain = takePicture(scenario, camera);
            if (plain != null) {
                Bitmap plainBitmap = BitmapFactory.decodeFile(plain.getAbsolutePath());
                if (plainBitmap != null) {
                    Log.i(TAG, "a picture after AR is " + plainBitmap.getWidth() + "x"
                            + plainBitmap.getHeight() + ", mean brightness "
                            + meanBrightness(plainBitmap));
                }
                keep(plain, "after-ar-picture.jpg");
            }
            scenario.onActivity(activity -> {
                camera.setFrameRenderer(null);
                camera.close();
            });
            assertTrue("AR drew no frames", frames > 0);
            assertTrue("no AR frame knew its projection", projectedInAr > 0);
            assertCameraProjection(arProjection, NEAR, FAR);
            assertTrue("tracking was never reported", !tracking.isEmpty());
            assertTrue("no frame after AR knew its projection",
                    probe.projected.get() > projectedBefore);
            assertCameraProjection(calibratedProjection, NEAR, FAR);
            if (probe.viewed.get() > viewedBefore) {
                assertRigid("the view from the phone", calibratedView);
            }
        }
    }

    /**
     * AR starts tracking, a tracked frame's matrices describe a camera in the room, an anchor
     * placed under the middle of the view lands there and stays put, and where depth is measured
     * it covers the frame and hides a light standing behind the scene.
     */
    @Test
    public void arTracksTheRoomAndKeepsWhatIsPlacedInIt() throws Exception {
        Probe probe = new Probe();
        try (ActivityScenario<CameraTestActivity> scenario =
                     ActivityScenario.launch(CameraTestActivity.class)) {
            AtomicReference<CameraView> held = new AtomicReference<>();
            AtomicReference<CountDownLatch> opened =
                    new AtomicReference<>(new CountDownLatch(1));
            scenario.onActivity(activity -> {
                CameraView camera = activity.getCameraView();
                held.set(camera);
                camera.setFrameRenderer(probe);
                camera.setOnCameraStateChangedListener(
                        new CameraView.OnCameraStateChangedListener() {
                            @Override public void onCameraOpened() {
                                opened.get().countDown();
                            }

                            @Override public void onCameraClosed() {}
                        });
                camera.open();
            });
            assertTrue("the camera did not open " + DeviceState.whyTheCameraMightNotOpen(),
                    opened.get().await(OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            CameraView camera = held.get();
            try {
                checkTracking(scenario, camera, opened, probe);
            } finally {
                scenario.onActivity(activity -> {
                    camera.exitAr();
                    camera.setFrameRenderer(null);
                    camera.close();
                });
            }
        }
    }

    private void checkTracking(@NonNull ActivityScenario<CameraTestActivity> scenario,
            @NonNull CameraView camera, @NonNull AtomicReference<CountDownLatch> opened,
            @NonNull Probe probe) throws Exception {
        long deadline = SystemClock.uptimeMillis() + SUPPORT_TIMEOUT_MILLIS;
        boolean supported = onMain(scenario, camera::isArSupported);
        while (!supported && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(250);
            supported = onMain(scenario, camera::isArSupported);
        }
        Assume.assumeTrue("AR cannot run on this phone", supported);

        List<String> tracking = Collections.synchronizedList(new ArrayList<>());
        AtomicLong trackedAfter = new AtomicLong();
        long[] start = new long[1];
        AtomicBoolean entered = new AtomicBoolean();
        opened.set(new CountDownLatch(1));
        scenario.onActivity(activity -> {
            start[0] = SystemClock.uptimeMillis();
            entered.set(camera.enterAr(state -> {
                long after = SystemClock.uptimeMillis() - start[0];
                tracking.add(after + "ms " + state);
                if (state == CameraView.ArTracking.TRACKING) {
                    trackedAfter.compareAndSet(0, after);
                }
            }));
        });
        assumeArStarted(scenario, camera, entered.get());
        assertTrue("the camera did not reopen for AR",
                opened.get().await(OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        EmulatorScene scene = EmulatorScene.fromArguments();
        Log.i(TAG, scene == null ? "nothing moves the camera but whoever holds the phone"
                : "walking the emulator's camera through its room");
        try (Walk walk = scene == null ? null : new Walk(scene)) {
            checkTrackedFrames(scenario, camera, probe, tracking, trackedAfter, start[0], walk);
        }
    }

    private void checkTrackedFrames(@NonNull ActivityScenario<CameraTestActivity> scenario,
            @NonNull CameraView camera, @NonNull Probe probe, @NonNull List<String> tracking,
            @NonNull AtomicLong trackedAfter, long start, @Nullable Walk walk) throws Exception {
        int framesAtStart = probe.frames.get();

        // Tracking, which needs light, something to see and the phone moving a little.
        long deadline = SystemClock.uptimeMillis() + TRACKING_TIMEOUT_MILLIS;
        while (trackedAfter.get() == 0 && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(100);
        }
        if (walk != null) {
            walk.assertMoving();
        }
        Log.i(TAG, "tracking went " + tracking);
        Assume.assumeTrue("AR did not track within " + TRACKING_TIMEOUT_MILLIS + "ms, ending "
                        + onMain(scenario, camera::getArTracking) + ": it needs light, something"
                        + " to see and the phone moving a little",
                trackedAfter.get() != 0);
        Log.i(TAG, "tracking after " + trackedAfter.get() + "ms");

        // The matrices of a tracked frame.
        int viewed = probe.viewed.get();
        waitFor(() -> probe.viewed.get() > viewed, 2_000);
        float[] p = snapshot(probe.projection);
        float[] v = snapshot(probe.view);
        Log.i(TAG, "tracked projection " + java.util.Arrays.toString(p));
        Log.i(TAG, "tracked view " + java.util.Arrays.toString(v));
        assertCameraProjection(p, NEAR, FAR);
        assertRigid("the view", v);

        // An anchor under the middle of the view.
        ArAnchor anchor = null;
        deadline = SystemClock.uptimeMillis() + ANCHOR_TIMEOUT_MILLIS;
        while (anchor == null && SystemClock.uptimeMillis() < deadline) {
            anchor = onMain(scenario,
                    () -> camera.addArAnchor(camera.getWidth() / 2f, camera.getHeight() / 2f));
            if (anchor == null) {
                Thread.sleep(500);
            }
        }
        Assume.assumeTrue("AR found no surface under the middle of the view within "
                + ANCHOR_TIMEOUT_MILLIS + "ms", anchor != null);
        float[] model = new float[16];
        assertTrue("a new anchor is not tracked", anchor.getModelMatrix(model));
        assertRigid("the anchor", model);
        float[] placed = {model[12], model[13], model[14]};
        p = snapshot(probe.projection);
        v = snapshot(probe.view);
        float[] landed = project(p, v, placed);
        Log.i(TAG, "an anchor placed under the middle of the view, "
                + distance(placed, cameraPosition(v)) + "m away, lands at "
                + java.util.Arrays.toString(landed));
        assertTrue("an anchor placed under the middle of the view lands at "
                        + java.util.Arrays.toString(landed),
                Math.abs(landed[0]) < MAX_ANCHOR_OFFSET && Math.abs(landed[1]) < MAX_ANCHOR_OFFSET);

        // The anchor stays put in the room, however the camera moves.
        float[] cameraAtStart = cameraPosition(v);
        float[] sceneAtStart = walk == null ? null : walk.getPosition();
        float sceneTravel = 0f;
        float drift = 0f;
        float travel = 0f;
        int samples = 0;
        int trackedSamples = 0;
        long watchEnd = SystemClock.uptimeMillis() + DRIFT_WATCH_MILLIS;
        while (SystemClock.uptimeMillis() < watchEnd) {
            Thread.sleep(100);
            samples++;
            if (anchor.getModelMatrix(model)) {
                trackedSamples++;
                drift = Math.max(drift,
                        distance(placed, new float[] {model[12], model[13], model[14]}));
            }
            travel = Math.max(travel, distance(cameraAtStart, cameraPosition(snapshot(probe.view))));
            if (walk != null) {
                try {
                    sceneTravel = Math.max(sceneTravel,
                            distance(sceneAtStart, walk.getPosition()));
                } catch (IOException error) {
                    // A dropped connection to the host costs this sample only.
                }
            }
        }
        long elapsed = SystemClock.uptimeMillis() - start;
        if (walk != null) {
            walk.assertMoving();
            Log.i(TAG, "the emulator moved its camera up to " + sceneTravel * 100f
                    + "cm from where the anchor was placed");
        }
        Log.i(TAG, "over " + DRIFT_WATCH_MILLIS + "ms the anchor moved " + drift * 100f
                + "cm, tracked in " + trackedSamples + " of " + samples + " samples, while the"
                + " camera moved " + travel * 100f + "cm; "
                + (probe.frames.get() - framesAtStart) * 1000f / elapsed + " fps in AR");
        assertTrue("the anchor was lost: tracked in " + trackedSamples + " of " + samples
                + " samples", trackedSamples * 2 > samples);
        assertTrue("the anchor moved " + drift * 100f + "cm in the room",
                drift < MAX_ANCHOR_DRIFT_METRES);

        // Depth, where it is measured.
        boolean depthSupported = onMain(scenario, camera::isArDepthSupported);
        Log.i(TAG, "depth supported " + depthSupported + ", in " + probe.withDepth.get()
                + " frames so far");
        if (!depthSupported) {
            assertEquals("frames carried depth that is not measured here", 0,
                    probe.withDepth.get());
        } else {
            waitFor(() -> probe.withDepth.get() > 0, DEPTH_TIMEOUT_MILLIS);
            Assume.assumeTrue("no depth was measured within " + DEPTH_TIMEOUT_MILLIS
                    + "ms: it is measured from the phone moving", probe.withDepth.get() > 0);
            probe.depthRead = null;
            probe.readDepth.set(true);
            waitFor(() -> probe.depthRead != null, DEPTH_TIMEOUT_MILLIS);
            float[] read = probe.depthRead;
            assertNotNull("the depth was never read back", read);
            Log.i(TAG, "depth covers " + read[0] * 100f + "% of the frame, median " + read[1]
                    + "m, " + read[2] + "m at the middle");
            assertTrue("depth covers only " + read[0] * 100f + "% of the frame",
                    read[0] >= MIN_DEPTH_COVERAGE);
            assertTrue("the median depth is " + read[1] + "m",
                    read[1] > 0.1f && read[1] < 20f);

            probe.occlusionStage.set(1);
            waitFor(() -> probe.occlusionStage.get() == 3, DEPTH_TIMEOUT_MILLIS);
            Assume.assumeTrue("nothing was measured at the middle of the frame",
                    probe.occlusionStage.get() == 3);
            float front = probe.occlusion[0];
            float behind = probe.occlusion[1];
            Log.i(TAG, "with the scene " + probe.occlusion[2] + "m away at the middle, a light"
                    + " in front of it brightens the middle by " + front + " and one behind it by "
                    + behind);
            assertTrue("a light behind the scene shows through it, by " + behind,
                    behind <= MAX_HIDDEN_LIGHT);
            assertTrue("a light in front of the scene barely shows: " + front + " against "
                    + behind + " behind", front - behind >= MIN_OCCLUSION_CONTRAST);
        }

        anchor.detach();
        assertFalse("a detached anchor is still tracked", anchor.isTracking());
        assertFalse("a detached anchor still has a place", anchor.getModelMatrix(model));
    }

    /**
     * Skips where AR said it could run but would not start, having found then that ARCore will
     * not use this phone's camera; fails where it would not start for any other reason.
     */
    private static void assumeArStarted(@NonNull ActivityScenario<CameraTestActivity> scenario,
            @NonNull CameraView camera, boolean entered) {
        if (!entered) {
            Assume.assumeTrue("ARCore will not use this phone's camera",
                    onMain(scenario, camera::isArSupported));
        }
        assertTrue("AR would not start", entered);
    }

    /** A copy of a matrix a frame writes, taken under its lock. */
    @NonNull
    private static float[] snapshot(@NonNull float[] matrix) {
        synchronized (matrix) {
            return matrix.clone();
        }
    }

    /** Something waited for. */
    private interface Condition {
        boolean isMet();
    }

    /** Waits until a condition holds or a while passes, whichever is first. */
    private static void waitFor(@NonNull Condition condition, long timeoutMillis)
            throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + timeoutMillis;
        while (!condition.isMet() && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    /** The mean of every hundredth pixel's channels, from 0 to 255, to tell a dark room. */
    private static int meanBrightness(@NonNull Bitmap bitmap) {
        long sum = 0;
        int count = 0;
        for (int y = 0; y < bitmap.getHeight(); y += 10) {
            for (int x = 0; x < bitmap.getWidth(); x += 10) {
                int pixel = bitmap.getPixel(x, y);
                sum += ((pixel >> 16) & 0xff) + ((pixel >> 8) & 0xff) + (pixel & 0xff);
                count += 3;
            }
        }
        return count == 0 ? 0 : (int) (sum / count);
    }

    /** Takes a picture and waits for it. */
    private static File takePicture(@NonNull ActivityScenario<CameraTestActivity> scenario,
            @NonNull CameraView camera) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<File> captured = new AtomicReference<>();
        File file = new File(ApplicationProvider.getApplicationContext().getCacheDir(),
                "ar-" + SystemClock.uptimeMillis() + ".jpg");
        scenario.onActivity(activity -> {
            camera.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
                @Override public void onImageConfirmation() {}

                @Override public void onImageCaptured(File saved) {
                    captured.set(saved);
                    finished.countDown();
                }

                @Override public void onFailure() {
                    finished.countDown();
                }
            });
            camera.takePicture(file);
        });
        assertTrue("the AR picture never reported",
                finished.await(PICTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        return captured.get();
    }

    /** Copies a file where adb can pull it from before the package is removed. */
    private static void keep(@NonNull File file, @NonNull String name) throws Exception {
        File directory = ApplicationProvider.getApplicationContext().getExternalFilesDir(null);
        if (directory == null) {
            return;
        }
        try (InputStream in = new FileInputStream(file);
                OutputStream out = new FileOutputStream(new File(directory, name))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    private interface MainCall<T> {
        T call();
    }

    private static <T> T onMain(@NonNull ActivityScenario<CameraTestActivity> scenario,
            @NonNull MainCall<T> call) {
        AtomicReference<T> result = new AtomicReference<>();
        scenario.onActivity(activity -> result.set(call.call()));
        return result.get();
    }
}
