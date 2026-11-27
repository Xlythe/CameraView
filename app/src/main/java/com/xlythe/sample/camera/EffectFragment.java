package com.xlythe.sample.camera;

import static com.xlythe.sample.camera.MainActivity.TAG;

import android.net.Uri;
import android.opengl.GLES20;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xlythe.fragment.camera.CameraFragment;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.gl.FrameRenderer;
import com.xlythe.view.camera.gl.GlFrame;

import java.util.concurrent.TimeUnit;

/**
 * EffectFragment demonstrates drawing on camera frames as they pass through GL.
 *
 * <p>The effect itself is the dullest one that proves anything: a band swept down the frame. What
 * is worth looking at is where the band ends up. It is drawn into the same output the camera frame
 * was drawn into, so it is in the picture and the recording this fragment saves as well as on the
 * screen - which is what a View laid over the preview could never do, and the reason the frames go
 * through GL at all.</p>
 *
 * <p>Everything about how it looks is here rather than in the library. CameraView hands over the
 * texture, the transform and the timestamp and has no opinion on what is drawn with them.</p>
 */
public class EffectFragment extends CameraFragment {
    /** The camera the band is drawn over. */
    private CameraView mCameraView;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_effect, container, false);
        mCameraView = view.findViewById(R.id.camera);
        // Set before the camera opens, so the first configuration already carries the effect
        // rather than the camera being configured and then configured again.
        mCameraView.setFrameRenderer(new BandRenderer());
        return view;
    }

    /**
     * Says whether the band is being drawn. A camera that cannot draw frames keeps the renderer
     * and shows its own frames, so without this the band would just be missing.
     */
    @Override
    public void onCameraOpened() {
        report(mCameraView.isFrameEffectSupported()
                ? "Frames are going through GL - the band is in the saved file too"
                : "This camera cannot draw on its frames");
    }

    @Override
    public void onImageSaved(@NonNull Uri uri) {
        report("Picture saved to " + uri);
    }

    @Override
    public void onVideoSaved(@NonNull Uri uri) {
        report("Video saved to " + uri);
    }

    @Override
    public void onFailure() {
        report("Failure");
    }

    private void report(String message) {
        Log.d(TAG, message);
        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
    }

    /** Paints a band across every frame, under the app's own colours. */
    private static final class BandRenderer implements FrameRenderer {
        /** How long the band takes to travel the frame. */
        private static final long SWEEP_NANOS = TimeUnit.SECONDS.toNanos(3);

        /** The band's height, as a fraction of the frame's. */
        private static final int BAND_FRACTION = 12;

        @Override
        public boolean onDrawFrame(@NonNull GlFrame frame) {
            frame.drawCameraFrame();

            int bandHeight = Math.max(frame.getHeight() / BAND_FRACTION, 1);
            // Driven by the camera's own timestamp rather than by a frame counter, so the band
            // moves at the same rate in the recording as it did on the screen however many frames
            // the renderer managed. Timestamps are monotonic, so this stays positive.
            long phase = frame.getTimestampNanos() % SWEEP_NANOS;
            int bottom = (int) ((frame.getHeight() - bandHeight) * phase / SWEEP_NANOS);

            // Scissor and clear rather than a shader of this app's own. The thing being shown is
            // that a frame passes through GL on its way out, not how to write a fragment shader,
            // and this needs nothing created or released.
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
            GLES20.glScissor(0, bottom, frame.getWidth(), bandHeight);
            GLES20.glClearColor(1f, 0.42f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);

            return true;
        }
    }
}
