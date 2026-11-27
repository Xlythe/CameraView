package com.xlythe.sample.camera;

import static com.xlythe.sample.camera.MainActivity.TAG;

import android.Manifest;
import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import androidx.annotation.WorkerThread;

import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.xlythe.fragment.camera.CameraFragment;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.VideoStream;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The phone end of the Wear companion sample: the camera, driven from a watch.
 *
 * <p>The camera work is the same three calls the rest of the sample makes - open, stream, take a
 * picture. What is specific to a watch is the transport, and that is all this adds: the Data
 * Layer has no notion of a stream, so the preview is pumped down a channel by hand and the photo
 * is sent as a file.</p>
 *
 * <p>The preview is deliberately not the preview the phone shows itself. A watch link is a couple
 * of megabits at best, so the stream is asked for at 480p and a megabit with a key frame every
 * second, because a stream can only be joined at a key frame and the watch should not have to
 * wait seconds for a picture to appear. Those are the numbers in {@link WearProtocol}, passed
 * through CameraView's own stream parameters rather than enforced afterwards - re-encoding on the
 * phone would cost more than the link saves.</p>
 *
 * <p>The pump reads from the stream only when the watch has acknowledged enough of what it was
 * sent, and sends what it reads at once. A channel is lossless, so copying as fast as possible
 * would move the delay into the Data Layer, where nothing drops a late frame; pacing leaves it in
 * CameraView's lossy stream, which does.</p>
 */
public class WearFragment extends CameraFragment {
    /** How much to copy at a time. Small enough that stopping is noticed quickly. */
    private static final int CHUNK_BYTES = 8 * 1024;

    private CameraView mCameraView;
    private TextView mStatus;

    /** Copies the preview and reads the watch's acknowledgements, off the main thread. */
    @Nullable private ExecutorService mPump;

    /** The channel the preview is going down, if one is open. */
    @Nullable private ChannelClient.Channel mPreviewChannel;

    /** The live stream being copied, if there is one. */
    @Nullable private VideoStream mStream;

    /** Tells the copier to stop, which it checks between chunks. */
    private final AtomicBoolean mStreaming = new AtomicBoolean();

    /** Which watch asked, so a picture can be sent back to it. */
    @Nullable private String mWatchNodeId;

    private final MessageClient.OnMessageReceivedListener mMessageListener = this::onWatchMessage;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_wear, container, false);
        mCameraView = view.findViewById(R.id.camera);
        mStatus = view.findViewById(R.id.status);
        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        Wearable.getMessageClient(requireContext()).addListener(mMessageListener);
    }

    @Override
    public void onStop() {
        Wearable.getMessageClient(requireContext()).removeListener(mMessageListener);
        stopStreaming();
        super.onStop();
    }

    /**
     * Everything the watch can ask for.
     *
     * <p>Arrives on a background thread, and the camera has to be touched from the main one.</p>
     */
    private void onWatchMessage(@NonNull MessageEvent event) {
        mWatchNodeId = event.getSourceNodeId();
        switch (event.getPath()) {
            case WearProtocol.PATH_START_PREVIEW:
                runOnMain(this::startStreaming);
                break;
            case WearProtocol.PATH_STOP_PREVIEW:
                runOnMain(this::stopStreaming);
                break;
            case WearProtocol.PATH_TAKE_PICTURE:
                runOnMain(this::takePictureForWatch);
                break;
            default:
                Log.d(TAG, "Ignoring " + event.getPath());
                break;
        }
    }

    /**
     * Opens a channel to the watch and starts copying the camera into it.
     *
     * <p>The stream has to be created after the channel, because creating it starts the encoder:
     * frames produced before there is anywhere to put them are frames the watch will never see,
     * and with a one second key frame interval that delay is visible.</p>
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    private void startStreaming() {
        String watch = mWatchNodeId;
        if (watch == null || !mCameraView.isOpen() || mStreaming.get()) {
            return;
        }
        setStatus(R.string.wear_status_opening);
        ChannelClient channels = Wearable.getChannelClient(requireContext());
        channels.openChannel(watch, WearProtocol.PATH_PREVIEW)
                .addOnSuccessListener(requireActivity(), channel -> {
                    mPreviewChannel = channel;
                    channels.getOutputStream(channel).addOnSuccessListener(requireActivity(), out ->
                            channels.getInputStream(channel)
                                    .addOnSuccessListener(requireActivity(),
                                            acks -> pump(channel, out, acks))
                                    .addOnFailureListener(requireActivity(), error -> {
                                        closeQuietly(out);
                                        onChannelFailed(error);
                                    }))
                            .addOnFailureListener(requireActivity(), this::onChannelFailed);
                })
                .addOnFailureListener(requireActivity(), this::onChannelFailed);
    }

    private void onChannelFailed(@NonNull Exception error) {
        Log.w(TAG, "Could not stream to the watch", error);
        setStatus(R.string.wear_status_failed);
    }

    /**
     * Starts the stream, and the two threads that move it: one copying the preview down the
     * channel, and one reading the watch's acknowledgements back up it.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    private void pump(ChannelClient.Channel channel, OutputStream out, InputStream acks) {
        // stream() declares the microphone because a stream usually carries audio, and an
        // annotation cannot say "only when the parameters ask for it". These parameters do not,
        // so the one line that calls it is the whole scope of the exception.
        @SuppressLint("MissingPermission")
        VideoStream stream = mCameraView.stream(new VideoStream.Params.Builder()
                .setAudioEnabled(false)
                .setVideoEnabled(true)
                .setMaxSize(WearProtocol.PREVIEW_SIZE)
                .setBitRate(WearProtocol.PREVIEW_BIT_RATE)
                .setFrameRate(WearProtocol.PREVIEW_FRAME_RATE)
                .setIFrameInterval(WearProtocol.PREVIEW_KEY_FRAME_INTERVAL_SECONDS)
                .setIsLossy(true)
                .build());
        mStream = stream;
        mStreaming.set(true);
        setStatus(R.string.wear_status_streaming);

        InFlight inFlight = new InFlight(WearProtocol.PREVIEW_IN_FLIGHT_BYTES);
        ExecutorService pump = Executors.newFixedThreadPool(2);
        mPump = pump;
        pump.execute(() -> readAcknowledgements(acks, inFlight));
        pump.execute(() -> {
            InputStream in = stream.getVideoInputStream();
            byte[] chunk = new byte[CHUNK_BYTES];
            try {
                int room;
                while (mStreaming.get() && (room = inFlight.awaitRoom()) > 0) {
                    int read = in.read(chunk, 0, Math.min(room, chunk.length));
                    if (read < 0) {
                        break;
                    }
                    out.write(chunk, 0, read);
                    out.flush();
                    inFlight.sent(read);
                }
            } catch (IOException error) {
                // The watch going away closes the channel, which reads as an ordinary end.
                Log.d(TAG, "The preview stream ended", error);
            } catch (InterruptedException error) {
                // Stopping shuts the pump down, which interrupts a wait for the watch.
                Thread.currentThread().interrupt();
            } finally {
                inFlight.close();
                closeQuietly(out);
                closeQuietly(acks);
                Wearable.getChannelClient(requireContext()).close(channel);
                runOnMain(this::stopStreaming);
            }
        });
    }

    /** Hands the totals the watch reports to the copier, until the channel closes. */
    @WorkerThread
    private static void readAcknowledgements(InputStream acks, InFlight inFlight) {
        try (DataInputStream in = new DataInputStream(acks)) {
            while (true) {
                inFlight.acknowledged(in.readLong());
            }
        } catch (IOException error) {
            // The channel closing ends the stream, which is how this normally stops.
            Log.d(TAG, "The watch stopped acknowledging the preview", error);
        } finally {
            inFlight.close();
        }
    }

    private void stopStreaming() {
        if (!mStreaming.getAndSet(false) && mStream == null) {
            return;
        }
        VideoStream stream = mStream;
        mStream = null;
        if (stream != null) {
            closeQuietly(stream);
        }
        ChannelClient.Channel channel = mPreviewChannel;
        mPreviewChannel = null;
        if (channel != null) {
            Wearable.getChannelClient(requireContext()).close(channel);
        }
        ExecutorService pump = mPump;
        mPump = null;
        if (pump != null) {
            pump.shutdownNow();
        }
        if (isAdded()) {
            setStatus(R.string.wear_status_idle);
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private void takePictureForWatch() {
        if (!mCameraView.isOpen()) {
            return;
        }
        setStatus(R.string.wear_status_capturing);
        // Given a file, because the picture is scaled and sent to the watch rather than filed.
        mCameraView.takePicture(new File(requireContext().getCacheDir(),
                "for-watch-" + System.currentTimeMillis() + ".jpg"));
    }

    @Override
    public void onImageCaptured(File file) {
        sendToWatch(file);
    }

    /**
     * Sends a picture to the watch.
     *
     * <p>As a file rather than over the preview channel: a full-size JPEG is a few megabytes,
     * which at watch-link speeds takes long enough that it would stall the preview if the two
     * shared a channel.</p>
     */
    private void sendToWatch(File file) {
        String watch = mWatchNodeId;
        if (watch == null) {
            return;
        }
        // Scaled off the main thread: decoding and re-encoding a full-size JPEG is tens of
        // milliseconds at best, and this runs straight after a capture.
        Executors.newSingleThreadExecutor().execute(() -> {
            File scaled = scaleForWatch(file);
            runOnMain(() -> send(watch, scaled == null ? file : scaled));
        });
    }

    @MainThread
    private void send(@NonNull String watch, @NonNull File file) {
        ChannelClient channels = Wearable.getChannelClient(requireContext());
        channels.openChannel(watch, WearProtocol.PATH_PICTURE)
                .addOnSuccessListener(requireActivity(), channel ->
                        channels.sendFile(channel, Uri.fromFile(file))
                                .addOnFailureListener(requireActivity(), error ->
                                        Log.w(TAG, "Could not send the picture", error)))
                .addOnFailureListener(requireActivity(), error ->
                        Log.w(TAG, "Could not open a channel for the picture", error));
    }

    /**
     * A copy of the picture no larger than the watch can draw, or null if it could not be made.
     *
     * <p>Bounds are read first so the decode itself is done at a sample size, rather than
     * decoding a full-size bitmap and throwing most of it away - which on a large capture is
     * where the memory goes.</p>
     */
    @Nullable
    @WorkerThread
    private File scaleForWatch(@NonNull File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            if (longest <= 0) {
                return null;
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Integer.highestOneBit(
                    Math.max(1, longest / WearProtocol.PICTURE_MAX_EDGE));
            Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
            if (decoded == null) {
                return null;
            }

            File scaled = new File(requireContext().getCacheDir(), "to-watch.jpg");
            try (OutputStream out = new FileOutputStream(scaled)) {
                boolean written = decoded.compress(
                        Bitmap.CompressFormat.JPEG, WearProtocol.PICTURE_QUALITY, out);
                return written ? scaled : null;
            } finally {
                decoded.recycle();
            }
        } catch (IOException | RuntimeException | OutOfMemoryError error) {
            // The full-size picture still sends, just slowly, so this is never worth failing for.
            Log.w(TAG, "Could not scale the picture for the watch", error);
            return null;
        }
    }

    @Override
    public void onCameraOpened() {
        setStatus(mStreaming.get() ? R.string.wear_status_streaming : R.string.wear_status_idle);
    }

    @Override
    public void onCameraClosed() {
        stopStreaming();
    }

    @Override
    public void onFailure() {
        setStatus(R.string.wear_status_failed);
    }

    private void runOnMain(Runnable runnable) {
        View view = getView();
        if (view != null) {
            view.post(runnable);
        }
    }

    private void setStatus(int message) {
        if (mStatus != null) {
            mStatus.setText(message);
        }
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException | RuntimeException error) {
            Log.d(TAG, "Failed to close " + closeable, error);
        }
    }

    /**
     * The preview bytes sent that the watch has not yet said it read.
     *
     * <p>Shared by the copier, which waits on it, and the acknowledgement reader, which wakes it,
     * so every field is read and written under its monitor.</p>
     */
    private static final class InFlight {
        /** The most the phone sends before it waits for the watch. */
        private final int mLimit;

        /** Bytes written to the channel. */
        private long mSent;

        /** Bytes the watch has said it read. */
        private long mAcknowledged;

        /** Whether either end has stopped, so the copier should not wait for room again. */
        private boolean mClosed;

        InFlight(int limit) {
            mLimit = limit;
        }

        /** Waits until the watch has room for more, and returns how much; 0 once closed. */
        @WorkerThread
        synchronized int awaitRoom() throws InterruptedException {
            while (!mClosed && mSent - mAcknowledged >= mLimit) {
                wait();
            }
            return mClosed ? 0 : (int) (mLimit - (mSent - mAcknowledged));
        }

        @WorkerThread
        synchronized void sent(int bytes) {
            mSent += bytes;
        }

        @WorkerThread
        synchronized void acknowledged(long total) {
            mAcknowledged = total;
            notifyAll();
        }

        @WorkerThread
        synchronized void close() {
            mClosed = true;
            notifyAll();
        }
    }
}
