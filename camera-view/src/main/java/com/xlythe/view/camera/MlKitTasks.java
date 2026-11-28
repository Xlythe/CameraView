package com.xlythe.view.camera;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Waits on the ML Kit models the library runs over camera frames. */
final class MlKitTasks {
    /** Log tag shared with CameraView. */
    private static final String TAG = CameraView.class.getSimpleName();

    private MlKitTasks() {}

    /**
     * Waits for ML Kit to finish with a frame, however long it takes and whatever interrupts the
     * wait.
     *
     * <p>A {@link FrameStream} frame and its reader are closed as soon as the frame's analyzer
     * returns, while ML Kit reads the frame's buffer until its task completes. Returning early
     * frees that buffer under it: the read fails with "Image is already closed", or crashes the
     * process from native code. So an interrupt, which is how a stopping stream asks the frame
     * thread to finish, is held until the task is done and then restored, and a slow task is
     * logged and waited out.</p>
     *
     * @param what names the model in the log, as in "a barcode scan"
     * @throws ExecutionException if the task failed
     * @throws CancellationException if the task was cancelled
     */
    @WorkerThread
    static <T> T awaitFrameRead(@NonNull Task<T> task, long slowMillis, @NonNull String what)
            throws ExecutionException {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return Tasks.await(task, slowMillis, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (TimeoutException e) {
                    Log.w(TAG, what + " has taken over " + slowMillis
                            + " ms; still waiting for it");
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
