package com.xlythe.fragment.camera;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.ToggleButton;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.R;

/**
 * Layouts for the fragment tests, built in code rather than inflated.
 *
 * <p>CameraFragment binds to Views by id, and a library module's test cannot inflate a layout
 * from the app that would normally supply one - so the layout is assembled here with the same ids
 * the fragment looks for. Building it in one place means a test that is missing a View is a
 * mistake in one file rather than in each of them.</p>
 */
final class Layouts {
    private Layouts() {}

    /** Every View CameraFragment knows how to bind, so no optional path is skipped. */
    static View full(Context context) {
        FrameLayout root = new FrameLayout(context);

        FrameLayout holder = new FrameLayout(context);
        holder.setId(R.id.layout_camera);
        CameraView camera = new CameraView(context);
        camera.setId(R.id.camera);
        holder.addView(camera);
        Button capture = new Button(context);
        capture.setId(R.id.capture);
        holder.addView(capture);
        root.addView(holder);

        FrameLayout permissions = new FrameLayout(context);
        permissions.setId(R.id.layout_permissions);
        root.addView(permissions);
        Button request = new Button(context);
        request.setId(R.id.request_permissions);
        permissions.addView(request);

        Button confirm = new Button(context);
        confirm.setId(R.id.confirm);
        root.addView(confirm);
        Button cancel = new Button(context);
        cancel.setId(R.id.cancel);
        root.addView(cancel);
        ToggleButton toggle = new ToggleButton(context);
        toggle.setId(R.id.toggle);
        root.addView(toggle);
        ProgressBar progress = new ProgressBar(context);
        progress.setId(R.id.progress);
        root.addView(progress);
        TextView duration = new TextView(context);
        duration.setId(R.id.duration);
        root.addView(duration);

        return root;
    }
}
