package com.xlythe.view.camera.viewer;

import static com.xlythe.view.camera.ICameraModule.TAG;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.opengl.Matrix;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Display;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.OverScroller;

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;
import androidx.core.view.ViewCompat;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Shows a photo sphere from the inside: drag to look around, pinch to zoom, and fling to keep
 * turning. With {@link #setGyroscopeEnabled(boolean)} on, turning the phone looks around too.
 *
 * <p>The picture is equirectangular, and its photo sphere (GPano) metadata says how much of the
 * sphere it covers and which way it faces. A partial sphere, such as a panorama that covers only a band around the horizon, is
 * shown where it was taken and the rest is left black. A picture without the metadata is taken
 * to go all the way round. It opens looking the way the metadata says to, or at the middle of
 * the picture.</p>
 *
 * <h2>Memory</h2>
 * <p>The picture is uploaded to the GPU in tiles: a copy of at most 1 MP first, so it shows at
 * once, then up to 16 MP at full detail (64 MB of GPU memory), the tiles nearest the view first,
 * and the copy is let go once they are all in. A larger picture is shrunk to fit. While it loads,
 * the app holds at most one tile's decode at a time, under 6 MP (24 MB); once loaded it holds
 * none.</p>
 *
 * <p>It draws with OpenGL ES 2.0, which every Android device has.</p>
 */
public class PhotosphereView extends FrameLayout {
    /** The field of view across the view's shorter side, when the picture does not say. */
    @VisibleForTesting
    static final float DEFAULT_FOV = 70f;

    /** The narrowest the view may be zoomed to, in degrees across its shorter side. */
    @VisibleForTesting
    static final float MIN_FOV = 20f;

    /** The widest. */
    @VisibleForTesting
    static final float MAX_FOV = 100f;

    /** Posts the worker's results back to the main thread. */
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /** What the sphere is drawn on. */
    private final GlTextureView mSurface;

    /** Draws the sphere on the GL thread. */
    private final SphereRenderer mRenderer;

    /** Drags, flings and taps. */
    private final GestureDetector mGestures;

    /** Pinches. */
    private final ScaleGestureDetector mPinch;

    /** Carries a fling on after the finger lifts. */
    private final OverScroller mScroller;

    /** Turns the view a step further each frame while a fling lasts. */
    private final Runnable mFlingStep = new Runnable() {
        @Override
        public void run() {
            stepFling();
        }
    };

    /** The phone's rotation sensor, or null if it has none. */
    @Nullable
    private final Sensor mRotationSensor;

    /** Hears the rotation sensor. */
    private final SensorEventListener mSensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            onRotationSensed(event.values);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    /** Decodes off the main thread: one thread, which goes away when there is nothing to do. */
    private Executor mExecutor = new ThreadPoolExecutor(
            0, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());

    /** Opens pictures. */
    @NonNull
    private ImageSources.Opener mOpener = ImageSources::open;

    /** Hears whether the picture showed. */
    @Nullable
    private ImageLoadListener mListener;

    /** The picture asked for. */
    @Nullable
    private ImageLocation mLocation;

    /** Counts the pictures asked for, so a late answer about an earlier one is ignored. */
    private int mGeneration;

    /** Whether the current picture has been reported, one way or the other. */
    private boolean mReported;

    /** The upload of the current picture, while it runs. */
    @Nullable
    private Job mJob;

    /** The GL context textures go into, once one is ready. */
    private int mContext;

    /** The largest texture that context takes. */
    private int mMaxTextureSize;

    /** Whether a context is ready. */
    private boolean mContextReady;

    /**
     * Whether the view is attached. Kept here because a view still counts as attached while its
     * own onDetachedFromWindow runs.
     */
    private boolean mAttached;

    /** Where the picture sits on the sphere, once it is open. */
    @Nullable
    private GPano mGPano;

    /** Turns the picture's own directions into the world's, once it is open. */
    private float[] mPose = SphereMath.rotationZ(0);

    /** Whether the view has been pointed where the picture says to open. */
    private boolean mPlaced;

    /** The heading looked at, while the rotation sensor is not steering. */
    private double mHeading;

    /** The pitch looked at, likewise. */
    private double mPitch;

    /** The field of view across the view's shorter side, in degrees. */
    private double mFov = DEFAULT_FOV;

    /** Whether the rotation sensor steers the view. */
    private boolean mGyroscopeEnabled;

    /** The phone's latest rotation, turned to the screen, or null before the first reading. */
    @Nullable
    private float[] mSensorRotation;

    /** How far a drag has turned the view from where the phone points. */
    private double mYawOffset;

    /** How far a drag has tipped it. */
    private double mPitchOffset;

    /** Where the fling was at its last step. */
    private int mFlingX;
    private int mFlingY;

    /** Where the view looks, for the worker to upload the nearest tiles first. */
    private volatile double mLookHeading;
    private volatile double mLookPitch;

    public PhotosphereView(@NonNull Context context) {
        this(context, null);
    }

    public PhotosphereView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PhotosphereView(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mRenderer = new SphereRenderer(new SphereRenderer.Callback() {
            @Override
            public void onContextReady(final int glContext, final int maxTextureSize) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        onGlContextReady(glContext, maxTextureSize);
                    }
                });
            }
        });
        mSurface = new GlTextureView(context, mRenderer);
        addView(mSurface, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        mScroller = new OverScroller(context);
        mGestures = new GestureDetector(context, new GestureListener());
        mPinch = new ScaleGestureDetector(context, new PinchListener());
        SensorManager sensors = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        mRotationSensor = sensors == null ? null : rotationSensor(sensors);
    }

    /**
     * The sensor to follow: the game rotation vector, from the gyroscope and accelerometer, where
     * there is one; otherwise the rotation vector, which leans on the compass and drifts less but
     * jitters more.
     */
    @Nullable
    private static Sensor rotationSensor(@NonNull SensorManager sensors) {
        Sensor sensor = null;
        if (Build.VERSION.SDK_INT >= 18) {
            sensor = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        }
        return sensor != null ? sensor : sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
    }

    /** Shows a photo sphere from a file, or nothing for null. */
    @MainThread
    public void setFile(@Nullable File file) {
        load(file == null ? null : ImageLocation.of(file));
    }

    /** Shows a photo sphere from a Uri, such as one the camera filed in the gallery. */
    @MainThread
    public void setUri(@Nullable Uri uri) {
        load(uri == null ? null : ImageLocation.of(uri));
    }

    /** Hears whether each picture could be shown. */
    @MainThread
    public void setImageLoadListener(@Nullable ImageLoadListener listener) {
        mListener = listener;
    }

    /**
     * Whether turning the phone looks around the sphere, as if through a window onto it. A drag
     * still turns the view on top of that. Off by default; nothing happens on a phone without a
     * rotation sensor, which {@link #isGyroscopeAvailable()} tells.
     */
    @MainThread
    public void setGyroscopeEnabled(boolean enabled) {
        if (enabled == mGyroscopeEnabled) {
            return;
        }
        if (!enabled) {
            // Carry on looking where the phone pointed, rather than jumping back.
            float[] rotation = rotation();
            mHeading = SphereMath.headingOf(rotation);
            mPitch = clampPitch(SphereMath.pitchOf(rotation));
        }
        mGyroscopeEnabled = enabled;
        mSensorRotation = null;
        updateSensor();
        updateMatrix();
    }

    @MainThread
    public boolean isGyroscopeEnabled() {
        return mGyroscopeEnabled;
    }

    /** Whether the phone has a sensor for {@link #setGyroscopeEnabled(boolean)} to follow. */
    @AnyThread
    public boolean isGyroscopeAvailable() {
        return mRotationSensor != null;
    }

    @VisibleForTesting
    void setExecutor(@NonNull Executor executor) {
        mExecutor = executor;
    }

    @VisibleForTesting
    void setOpener(@NonNull ImageSources.Opener opener) {
        mOpener = opener;
    }

    @VisibleForTesting
    @NonNull
    SphereRenderer getRenderer() {
        return mRenderer;
    }

    /** The heading in the middle of the view, clockwise from north. */
    @VisibleForTesting
    double getHeading() {
        return SphereMath.headingOf(rotation());
    }

    /** The pitch in the middle of the view. */
    @VisibleForTesting
    double getPitch() {
        return SphereMath.pitchOf(rotation());
    }

    /** The field of view across the view's shorter side. */
    @VisibleForTesting
    double getFov() {
        return mFov;
    }

    /** Points the view, as a drag would. */
    @VisibleForTesting
    void lookAt(double heading, double pitch) {
        mHeading = heading;
        mPitch = clampPitch(pitch);
        updateMatrix();
    }

    @MainThread
    private void load(@Nullable ImageLocation location) {
        if (location == null ? mLocation == null : location.equals(mLocation)) {
            return;
        }
        cancelJob();
        mRenderer.clear();
        mGeneration++;
        mLocation = location;
        mGPano = null;
        mPose = SphereMath.rotationZ(0);
        mPlaced = false;
        mReported = false;
        startJob();
        mSurface.requestRender();
    }

    @MainThread
    private void onGlContextReady(int context, int maxTextureSize) {
        mContext = context;
        mMaxTextureSize = maxTextureSize;
        mContextReady = true;
        cancelJob();
        startJob();
        updateMatrix();
    }

    @MainThread
    private void startJob() {
        if (mLocation == null || !mContextReady || !mAttached) {
            return;
        }
        mJob = new Job(mGeneration, mContext, mMaxTextureSize, mLocation,
                getContext().getApplicationContext(), mOpener);
        mExecutor.execute(mJob);
    }

    @MainThread
    private void cancelJob() {
        if (mJob != null) {
            mJob.cancel();
            mJob = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mAttached = true;
        // The view makes a new GL context once it has a surface, and the picture is uploaded to
        // it.
        updateSensor();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mAttached = false;
        // The surface goes on detach, and the GL context with it.
        cancelJob();
        mContextReady = false;
        mScroller.forceFinished(true);
        updateSensor();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateSensor();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        updateMatrix();
    }

    /** Listens to the rotation sensor only while it steers a view someone can see. */
    @MainThread
    private void updateSensor() {
        SensorManager sensors =
                (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
        if (sensors == null || mRotationSensor == null) {
            return;
        }
        sensors.unregisterListener(mSensorListener);
        if (mGyroscopeEnabled && mAttached && getWindowVisibility() == View.VISIBLE) {
            sensors.registerListener(mSensorListener, mRotationSensor,
                    SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @MainThread
    private void onRotationSensed(@NonNull float[] values) {
        if (!mGyroscopeEnabled) {
            return;
        }
        // Some releases refuse more than the four values the matrix is made from.
        float[] vector = new float[Math.min(4, values.length)];
        System.arraycopy(values, 0, vector, 0, vector.length);
        float[] device = new float[9];
        SensorManager.getRotationMatrixFromVector(device, vector);
        float[] screen = new float[9];
        int[] axes = screenAxes(displayRotation());
        SensorManager.remapCoordinateSystem(device, axes[0], axes[1], screen);
        if (mSensorRotation == null) {
            // Line the phone up with where the view looks now, so turning it on does not jump.
            mYawOffset = mHeading - SphereMath.headingOf(screen);
            mPitchOffset = mPitch - SphereMath.pitchOf(screen);
        }
        mSensorRotation = screen;
        updateMatrix();
    }

    /** The screen's rotation from the phone's natural orientation. */
    @MainThread
    private int displayRotation() {
        Display display = ViewCompat.getDisplay(this);
        return display == null ? Surface.ROTATION_0 : display.getRotation();
    }

    /** The phone's axes that lie along the screen's x and y, for a screen rotation. */
    @AnyThread
    @NonNull
    static int[] screenAxes(int displayRotation) {
        switch (displayRotation) {
            case Surface.ROTATION_90:
                return new int[] {SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X};
            case Surface.ROTATION_180:
                return new int[] {SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y};
            case Surface.ROTATION_270:
                return new int[] {SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X};
            default:
                return new int[] {SensorManager.AXIS_X, SensorManager.AXIS_Y};
        }
    }

    /** Where the phone points, as the rotation from the screen's axes to the world's. */
    @MainThread
    @NonNull
    private float[] rotation() {
        if (mGyroscopeEnabled && mSensorRotation != null) {
            return SphereMath.multiply(SphereMath.multiply(SphereMath.rotationZ(-mYawOffset),
                    mSensorRotation), SphereMath.rotationX(mPitchOffset));
        }
        return SphereMath.lookRotation(mHeading, mPitch);
    }

    /** Hands the renderer the matrix for where the view looks, and asks for a frame. */
    @MainThread
    private void updateMatrix() {
        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) {
            return;
        }
        float[] rotation = rotation();
        mLookHeading = SphereMath.headingOf(rotation);
        mLookPitch = SphereMath.pitchOf(rotation);
        float[] projection = new float[16];
        float[] view = new float[16];
        float[] pose = new float[16];
        float[] viewPose = new float[16];
        float[] mvp = new float[16];
        SphereMath.projection(mFov, width, height, projection);
        SphereMath.view(rotation, view);
        SphereMath.toGl(mPose, pose);
        Matrix.multiplyMM(viewPose, 0, view, 0, pose, 0);
        Matrix.multiplyMM(mvp, 0, projection, 0, viewPose, 0);
        mRenderer.setMatrix(mvp);
        mSurface.requestRender();
    }

    /** Points the view where the picture says to open, once it is open. */
    @MainThread
    private void onSphereOpened(int generation, @NonNull GPano gpano) {
        if (generation != mGeneration) {
            return;
        }
        mGPano = gpano;
        mPose = SphereMath.pose(gpano);
        if (!mPlaced) {
            mPlaced = true;
            // The middle of the picture, in the world.
            float[] middle = new float[3];
            SphereMath.direction(gpano.headingAt(0.5), gpano.pitchAt(0.5), middle, 0);
            float[] world = transform(mPose, middle);
            mHeading = Float.isNaN(gpano.initialHeadingDegrees)
                    ? Math.toDegrees(Math.atan2(world[0], world[1]))
                    : gpano.initialHeadingDegrees;
            mPitch = clampPitch(Float.isNaN(gpano.initialPitchDegrees)
                    ? Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, world[2]))))
                    : gpano.initialPitchDegrees);
            if (!Float.isNaN(gpano.initialHorizontalFovDegrees)) {
                mFov = clampFov(SphereMath.shortSideFov(gpano.initialHorizontalFovDegrees,
                        Math.max(1, getWidth()), Math.max(1, getHeight())));
            }
            mSensorRotation = null;
        }
        updateMatrix();
    }

    @MainThread
    private void onSphereShowing(int generation) {
        if (generation != mGeneration || mReported) {
            return;
        }
        mReported = true;
        if (mListener != null) {
            mListener.onImageLoaded();
        }
    }

    @MainThread
    private void onSphereFailed(int generation) {
        if (generation != mGeneration || mReported) {
            return;
        }
        mReported = true;
        if (mListener != null) {
            mListener.onImageFailed();
        }
    }

    @NonNull
    private static float[] transform(@NonNull float[] rotation, @NonNull float[] vector) {
        float[] result = new float[3];
        for (int row = 0; row < 3; row++) {
            result[row] = rotation[row * 3] * vector[0] + rotation[row * 3 + 1] * vector[1]
                    + rotation[row * 3 + 2] * vector[2];
        }
        return result;
    }

    private static double clampPitch(double pitch) {
        return Math.max(-90, Math.min(90, pitch));
    }

    private static double clampFov(double fov) {
        return Math.max(MIN_FOV, Math.min(MAX_FOV, fov));
    }

    /** Turns the view as a finger moved over it, so what was under the finger stays there. */
    @MainThread
    private void turn(float dx, float dy) {
        double degreesPerPixel = SphereMath.horizontalFov(mFov, getWidth(), getHeight())
                / Math.max(1, getWidth());
        if (mGyroscopeEnabled && mSensorRotation != null) {
            mYawOffset -= dx * degreesPerPixel;
            mPitchOffset = clampPitch(mPitchOffset + dy * degreesPerPixel);
        } else {
            mHeading = SphereMath.wrapDegrees(mHeading - dx * degreesPerPixel);
            mPitch = clampPitch(mPitch + dy * degreesPerPixel);
        }
        updateMatrix();
    }

    @MainThread
    private void stepFling() {
        if (!mScroller.computeScrollOffset()) {
            return;
        }
        int x = mScroller.getCurrX();
        int y = mScroller.getCurrY();
        turn(x - mFlingX, y - mFlingY);
        mFlingX = x;
        mFlingY = y;
        ViewCompat.postOnAnimation(this, mFlingStep);
    }

    /** A single tap, which also reaches accessibility services as a click. */
    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mScroller.forceFinished(true);
            // Every drag turns the view, so none belongs to a scrolling parent.
            ViewParent parent = getParent();
            if (parent != null) {
                parent.requestDisallowInterceptTouchEvent(true);
            }
        }
        boolean handled = mPinch.onTouchEvent(event);
        handled |= mGestures.onTouchEvent(event);
        return handled || super.onTouchEvent(event);
    }

    /** Drags, flings and taps. */
    private final class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(@NonNull MotionEvent event) {
            return true;
        }

        @Override
        public boolean onSingleTapConfirmed(@NonNull MotionEvent event) {
            // A tap is the view's click, for an app that shows and hides its own controls.
            return performClick();
        }

        @Override
        public boolean onScroll(@Nullable MotionEvent down, @NonNull MotionEvent event,
                float distanceX, float distanceY) {
            if (mPinch.isInProgress()) {
                return false;
            }
            turn(-distanceX, -distanceY);
            return true;
        }

        @Override
        public boolean onFling(@Nullable MotionEvent down, @NonNull MotionEvent event,
                float velocityX, float velocityY) {
            mFlingX = 0;
            mFlingY = 0;
            mScroller.fling(0, 0, Math.round(velocityX), Math.round(velocityY),
                    Integer.MIN_VALUE / 2, Integer.MAX_VALUE / 2,
                    Integer.MIN_VALUE / 2, Integer.MAX_VALUE / 2);
            ViewCompat.postOnAnimation(PhotosphereView.this, mFlingStep);
            return true;
        }
    }

    /** Pinches, which narrow or widen the field of view. */
    private final class PinchListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(@NonNull ScaleGestureDetector detector) {
            mFov = clampFov(mFov / detector.getScaleFactor());
            updateMatrix();
            return true;
        }
    }

    /**
     * Uploads a picture to the current GL context: open it, plan its tiles, then decode and hand
     * over the low-resolution copy and each tile in turn, never more than one at a time.
     */
    private final class Job implements Runnable {
        /** Which picture this is for. */
        final int generation;

        /** Which GL context the textures go into. */
        final int glContext;

        /** The largest texture that context takes. */
        final int maxTextureSize;

        /** Where the picture is. */
        @NonNull
        final ImageLocation location;

        /** For opening it. */
        @NonNull
        final Context context;

        /** Opens it. */
        @NonNull
        final ImageSources.Opener opener;

        /** Lets one texture be decoded at a time: taken to decode, given back once uploaded. */
        final Semaphore turn = new Semaphore(1);

        /** Whether the upload has been called off. */
        volatile boolean cancelled;

        /** Gives back the turn once a texture is uploaded or dropped. */
        final Runnable done = new Runnable() {
            @Override
            public void run() {
                turn.release();
            }
        };

        Job(int generation, int glContext, int maxTextureSize, @NonNull ImageLocation location,
                @NonNull Context context, @NonNull ImageSources.Opener opener) {
            this.generation = generation;
            this.glContext = glContext;
            this.maxTextureSize = maxTextureSize;
            this.location = location;
            this.context = context;
            this.opener = opener;
        }

        @AnyThread
        void cancel() {
            cancelled = true;
            // Wakes a worker waiting for its turn, which then sees it has been called off.
            turn.release();
        }

        @WorkerThread
        @Override
        public void run() {
            if (cancelled) {
                return;
            }
            ImageSources.Opened opened;
            try {
                opened = opener.open(context, location);
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                Log.w(TAG, "Unable to open " + location, e);
                postFailed();
                return;
            }
            try {
                upload(opened.source, opened.gpano);
            } catch (RuntimeException | OutOfMemoryError e) {
                Log.w(TAG, "Unable to show " + location, e);
                postFailed();
            } finally {
                opened.source.close();
            }
        }

        @WorkerThread
        private void upload(@NonNull RegionSource source, @Nullable GPano metadata) {
            int width = source.getWidth();
            int height = source.getHeight();
            final GPano gpano = metadata != null ? metadata : GPano.assumed(width, height);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    onSphereOpened(generation, gpano);
                }
            });
            SphereTiling plan = SphereTiling.plan(width, height, maxTextureSize,
                    gpano.isFullCircle());

            if (!awaitTurn()) {
                return;
            }
            Bitmap base = source.decode(new Rect(0, 0, width, height), plan.baseSampleSize);
            if (base == null) {
                turn.release();
                postFailed();
                return;
            }
            if (!hand(SphereMesh.patch(gpano, 0, 0, 1, 1, 0, 0, 1, 1), base, true)) {
                return;
            }
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    onSphereShowing(generation);
                }
            });

            for (SphereTiling.Tile tile : nearestFirst(plan, gpano)) {
                if (!awaitTurn()) {
                    return;
                }
                Bitmap texture = decode(source, plan, tile);
                if (texture == null) {
                    turn.release();
                    continue;
                }
                float textureWidth = tile.textureWidth();
                float textureHeight = tile.textureHeight();
                SphereMesh mesh = SphereMesh.patch(gpano,
                        (double) tile.inside.left / plan.textureWidth,
                        (double) tile.inside.top / plan.textureHeight,
                        (double) tile.inside.right / plan.textureWidth,
                        (double) tile.inside.bottom / plan.textureHeight,
                        tile.insideLeft() / textureWidth,
                        tile.insideTop() / textureHeight,
                        (tile.insideLeft() + tile.inside.width()) / textureWidth,
                        (tile.insideTop() + tile.inside.height()) / textureHeight);
                if (!hand(mesh, texture, false)) {
                    return;
                }
            }
            if (awaitTurn()) {
                turn.release();
                mRenderer.dropBase();
                mSurface.requestRender();
            }
        }

        /** Waits for the last texture to be uploaded. False if called off meanwhile. */
        @WorkerThread
        private boolean awaitTurn() {
            try {
                turn.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (cancelled) {
                turn.release();
                return false;
            }
            return true;
        }

        /** Hands a texture to the renderer. False, and the bitmap recycled, if called off. */
        @WorkerThread
        private boolean hand(@NonNull SphereMesh mesh, @NonNull Bitmap bitmap, boolean base) {
            if (cancelled) {
                bitmap.recycle();
                turn.release();
                return false;
            }
            mRenderer.submit(glContext, mesh, bitmap, base, done);
            mSurface.requestRender();
            return true;
        }

        @WorkerThread
        private void postFailed() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    onSphereFailed(generation);
                }
            });
        }

        /** The tiles, those nearest where the view looks first. */
        @WorkerThread
        @NonNull
        private List<SphereTiling.Tile> nearestFirst(@NonNull final SphereTiling plan,
                @NonNull final GPano gpano) {
            final float[] pose = SphereMath.pose(gpano);
            final float[] look = new float[3];
            SphereMath.direction(mLookHeading, mLookPitch, look, 0);
            List<SphereTiling.Tile> tiles = new ArrayList<>(plan.tiles);
            Collections.sort(tiles, new Comparator<SphereTiling.Tile>() {
                @Override
                public int compare(SphereTiling.Tile a, SphereTiling.Tile b) {
                    // Nearest has the largest dot product with where the view looks.
                    return Double.compare(closeness(b), closeness(a));
                }

                private double closeness(@NonNull SphereTiling.Tile tile) {
                    float[] middle = new float[3];
                    SphereMath.direction(
                            gpano.headingAt(tile.inside.exactCenterX() / plan.textureWidth),
                            gpano.pitchAt(tile.inside.exactCenterY() / plan.textureHeight),
                            middle, 0);
                    float[] world = transform(pose, middle);
                    return world[0] * look[0] + world[1] * look[1] + world[2] * look[2];
                }
            });
            return tiles;
        }

        /**
         * Decodes a tile's texture: its inside and borders from the picture, and for a sphere
         * that goes all the way round, the borders that come from its other end.
         */
        @WorkerThread
        @Nullable
        private Bitmap decode(@NonNull RegionSource source, @NonNull SphereTiling plan,
                @NonNull SphereTiling.Tile tile) {
            Bitmap texture;
            try {
                texture = Bitmap.createBitmap(tile.textureWidth(), tile.textureHeight(),
                        Bitmap.Config.ARGB_8888);
            } catch (OutOfMemoryError e) {
                Log.w(TAG, "Out of memory for a photo sphere tile", e);
                return null;
            }
            Canvas canvas = new Canvas(texture);
            int offset = tile.wrapsLeft ? 1 : 0;
            draw(canvas, source, plan, tile.outside.left, tile.outside.top, tile.outside.right,
                    tile.outside.bottom, offset - tile.outside.left, -tile.outside.top);
            if (tile.wrapsLeft) {
                // The picture's last column, just left of its first.
                draw(canvas, source, plan, plan.textureWidth - 1, tile.outside.top,
                        plan.textureWidth, tile.outside.bottom, 1 - plan.textureWidth,
                        -tile.outside.top);
            }
            if (tile.wrapsRight) {
                // The picture's first column, just right of its last.
                draw(canvas, source, plan, 0, tile.outside.top, 1, tile.outside.bottom,
                        texture.getWidth() - 1, -tile.outside.top);
            }
            return texture;
        }

        /**
         * Draws a range of the picture's texels into a texture.
         *
         * @param dx where texel 0 across lands in the texture
         * @param dy where texel 0 down lands
         */
        @WorkerThread
        private void draw(@NonNull Canvas canvas, @NonNull RegionSource source,
                @NonNull SphereTiling plan, int left, int top, int right, int bottom, int dx,
                int dy) {
            Rect region = plan.source(left, top, right, bottom);
            if (region.isEmpty()) {
                return;
            }
            Bitmap decoded = source.decode(region, plan.sampleSize());
            if (decoded == null) {
                return;
            }
            try {
                // The decode covers whole stored pixels, a little more than the texels asked
                // for; placed by where those pixels fall and cut to the texels.
                RectF destination = new RectF(
                        (float) (region.left * plan.scaleX) + dx,
                        (float) (region.top * plan.scaleY) + dy,
                        (float) (region.right * plan.scaleX) + dx,
                        (float) (region.bottom * plan.scaleY) + dy);
                int saved = canvas.save();
                canvas.clipRect(left + dx, top + dy, right + dx, bottom + dy);
                canvas.drawBitmap(decoded, null, destination,
                        new Paint(Paint.FILTER_BITMAP_FLAG));
                canvas.restoreToCount(saved);
            } finally {
                decoded.recycle();
            }
        }
    }
}
