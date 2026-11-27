package com.xlythe.view.camera.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

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
 * rather than by throwing, so what is asserted is that frames arrive with their projection, and
 * what is logged under {@value #TAG} is everything a phone that cannot be moved can show: how
 * tracking went, whether depth arrived, and what the frames were drawn with.</p>
 *
 * <p>Skips where AR cannot run, which is a real configuration: no ARCore, or a phone Google does
 * not support for it.</p>
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
        }

        @Override
        public void onRendererStopped() {
            GLES20.glDeleteProgram(mProgram);
        }

        @Override
        public boolean onDrawFrame(@NonNull GlFrame frame) {
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
            boolean hasProjection = frame.getProjectionMatrix(p, 0.05f, 100f);
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
            int depth = frame.getDepthTextureName();
            if (depth != 0) {
                withDepth.incrementAndGet();
                firstDepthAt.compareAndSet(0, SystemClock.uptimeMillis());
            }
            return true;
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

        private static int compile(int type, @NonNull String source) {
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
            if (frame.getProjectionMatrix(p, 0.05f, 100f) && frame.getViewMatrix(v)) {
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
            assertTrue("AR would not start", entered.get());
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
            synchronized (probe.projection) {
                Log.i(TAG, "projection " + java.util.Arrays.toString(probe.projection));
            }
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
            synchronized (probe.projection) {
                Log.i(TAG, "calibrated projection "
                        + java.util.Arrays.toString(probe.projection));
            }
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
            assertTrue("tracking was never reported", !tracking.isEmpty());
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
