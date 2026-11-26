package com.xlythe.sample.camera.wear;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.IntDef;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.xlythe.view.camera.VideoStream;
import com.xlythe.view.camera.VideoView;

import java.io.File;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * The watch end of the companion sample: the phone's camera on your wrist, and a shutter button.
 *
 * <p>Nothing here opens a camera. The phone does that, and CameraView's own streaming encodes the
 * preview; all the watch does is hand the bytes coming off a Data Layer channel to a
 * {@link VideoView}, which is the same thing the phone sample does with a local pipe. That is why
 * none of this lives in the library: the only part that is specific to a watch is the transport.
 * </p>
 *
 * <p>Started by hand, from the watch's own launcher. Nothing on the phone brings it up and there
 * is no notification to drive it from: opening the camera on the phone does not reach the watch,
 * because a watch app that launched itself whenever a camera opened would be a worse sample than
 * one that waits to be asked. It is this screen that starts the conversation - on start it looks
 * for the phone by the capability the phone declares, and asks it to begin previewing.</p>
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "CameraViewWear";

    /** Declared by the phone app in wear.xml, and how the watch finds it. */
    private static final String CAPABILITY_PHONE_CAMERA = "camera_view_sample";

    /** Why a Data Layer channel closed, as the Wearable API reports it. */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            ChannelClient.ChannelCallback.CLOSE_REASON_NORMAL,
            ChannelClient.ChannelCallback.CLOSE_REASON_DISCONNECTED,
            ChannelClient.ChannelCallback.CLOSE_REASON_REMOTE_CLOSE,
            ChannelClient.ChannelCallback.CLOSE_REASON_LOCAL_CLOSE,
            ChannelClient.ChannelCallback.CLOSE_REASON_CONNECTION_TIMEOUT,
    })
    private @interface CloseReason {}

    /** Draws the phone's preview, fed from a channel rather than from a camera. */
    private VideoView mPreview;

    /** Covers the preview with the last picture taken, until it is tapped away. */
    private ImageView mPicture;

    /** Asks the phone for a picture; disabled while no phone is reachable. */
    private Button mShutter;

    /** The one line of text this screen has room for. */
    private TextView mStatus;

    /** The node running the phone half of this sample, or null while none is reachable. */
    @Nullable private Node mPhone;

    /** The preview currently playing, held so it can be closed when the screen stops. */
    @Nullable private VideoStream mStream;

    /**
     * Receives the channels the phone opens.
     *
     * <p>Registered without a Looper, so the Wearable API delivers these off the main
     * thread.</p>
     */
    private final ChannelClient.ChannelCallback mChannelCallback =
            new ChannelClient.ChannelCallback() {
                @Override
                @WorkerThread
                public void onChannelOpened(@NonNull ChannelClient.Channel channel) {
                    if (WearProtocol.PATH_PREVIEW.equals(channel.getPath())) {
                        showPreview(channel);
                    } else if (WearProtocol.PATH_PICTURE.equals(channel.getPath())) {
                        showPicture(channel);
                    }
                }

                @Override
                @WorkerThread
                public void onChannelClosed(@NonNull ChannelClient.Channel channel,
                        @CloseReason int closeReason, int appSpecificErrorCode) {
                    if (WearProtocol.PATH_PREVIEW.equals(channel.getPath())) {
                        runOnUiThread(() -> setStatus(R.string.status_preview_ended));
                    }
                }
            };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mPreview = findViewById(R.id.preview);
        mPicture = findViewById(R.id.picture);
        mShutter = findViewById(R.id.shutter);
        mStatus = findViewById(R.id.status);

        mShutter.setOnClickListener(view -> takePicture());
        mPicture.setOnClickListener(view -> mPicture.setVisibility(View.GONE));
    }

    @Override
    protected void onStart() {
        super.onStart();
        Wearable.getChannelClient(this).registerChannelCallback(mChannelCallback);
        findPhone();
    }

    @Override
    protected void onStop() {
        Wearable.getChannelClient(this).unregisterChannelCallback(mChannelCallback);
        tell(WearProtocol.PATH_STOP_PREVIEW);
        closeStream();
        super.onStop();
    }

    /**
     * Finds the phone by the capability it advertises rather than by picking a connected node.
     *
     * <p>A watch can be connected to more than one thing, and only the node running the phone
     * half of this sample can answer.</p>
     */
    @MainThread
    private void findPhone() {
        setStatus(R.string.status_looking_for_phone);
        Wearable.getCapabilityClient(this)
                .getCapability(CAPABILITY_PHONE_CAMERA, CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener(this, info -> {
                    Set<Node> nodes = info.getNodes();
                    mPhone = nodes.isEmpty() ? null : nodes.iterator().next();
                    if (mPhone == null) {
                        setStatus(R.string.status_no_phone);
                        mShutter.setEnabled(false);
                        return;
                    }
                    mShutter.setEnabled(true);
                    setStatus(R.string.status_asking_for_preview);
                    tell(WearProtocol.PATH_START_PREVIEW);
                })
                .addOnFailureListener(this, error -> {
                    Log.w(TAG, "Could not look for the phone", error);
                    setStatus(R.string.status_no_phone);
                });
    }

    @MainThread
    private void takePicture() {
        if (mPhone == null) {
            findPhone();
            return;
        }
        setStatus(R.string.status_capturing);
        tell(WearProtocol.PATH_TAKE_PICTURE);
    }

    /** Sends a one-shot message, which is all the watch ever needs to send. */
    @MainThread
    private void tell(@NonNull String path) {
        Node phone = mPhone;
        if (phone == null) {
            return;
        }
        Wearable.getMessageClient(this)
                .sendMessage(phone.getId(), path, new byte[0])
                .addOnFailureListener(error -> Log.w(TAG, "Could not send " + path, error));
    }

    /**
     * Plays the phone's preview.
     *
     * <p>The channel's input stream is H.264 exactly as CameraView's encoder produced it, which is
     * what VideoView.setStream expects, so there is nothing to decode here.</p>
     */
    @WorkerThread
    private void showPreview(@NonNull ChannelClient.Channel channel) {
        ChannelClient channels = Wearable.getChannelClient(this);
        channels.getInputStream(channel).addOnSuccessListener(this, inputStream -> {
            closeStream();
            mStream = new VideoStream.Builder().withVideoStream(inputStream).build();
            mPreview.setStream(mStream);
            mPreview.play();
            setStatus(R.string.status_streaming);
        }).addOnFailureListener(this, error -> {
            Log.w(TAG, "Could not read the preview", error);
            setStatus(R.string.status_preview_failed);
        });
    }

    /**
     * Shows a picture the phone just took.
     *
     * <p>Received to a file rather than read from the stream, because a full-size JPEG over a
     * watch link takes long enough that holding it in memory while it arrives is the wrong
     * trade.</p>
     */
    @WorkerThread
    private void showPicture(@NonNull ChannelClient.Channel channel) {
        File file = new File(getCacheDir(), "from-phone.jpg");
        Wearable.getChannelClient(this)
                .receiveFile(channel, Uri.fromFile(file), false /* append */)
                .addOnSuccessListener(this, ignored -> decode(file))
                .addOnFailureListener(this, error -> {
                    Log.w(TAG, "Could not receive the picture", error);
                    setStatus(R.string.status_capture_failed);
                });
    }

    /** Decodes off the main thread, then shows the result on it. */
    @MainThread
    private void decode(@NonNull File file) {
        Executors.newSingleThreadExecutor().execute(() -> {
            // Decoded as it arrives. The phone scales the picture to something a watch screen
            // can draw before it sends it, so there is nothing to sample down here - and the
            // sample size this used to guess at was wrong for any capture that was not a few
            // thousand pixels across.
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
            runOnUiThread(() -> {
                if (bitmap == null) {
                    setStatus(R.string.status_capture_failed);
                    return;
                }
                mPicture.setImageBitmap(bitmap);
                mPicture.setVisibility(View.VISIBLE);
                setStatus(R.string.status_streaming);
            });
        });
    }

    @MainThread
    private void closeStream() {
        VideoStream stream = mStream;
        mStream = null;
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not close the preview stream", error);
        }
    }

    @UiThread
    private void setStatus(@StringRes int message) {
        mStatus.setText(message);
    }
}
