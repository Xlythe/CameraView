package com.xlythe.view.camera.ar;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.exceptions.FatalException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;

/**
 * Whether this device can do AR, and getting it installed if it can but has not.
 *
 * <p>ARCore is not part of Android. It arrives as a separate application from the Play Store,
 * updates on its own schedule, and is absent on devices Google has not certified - so every
 * answer here is about the device in front of you rather than about an API level. An app that
 * assumes otherwise crashes on the first phone that has never had it.</p>
 *
 * <p>Checking is also not instant. The answer comes from ARCore itself, which has to be asked
 * over IPC, so the first call reports only that it is still asking. {@link #awaitCheck} does
 * the waiting; {@link #check} is the one-shot behind it and will say {@link State#UNKNOWN} on
 * a cold start, which is why nothing should treat a single call as final.</p>
 *
 * <h2>What the app has to declare</h2>
 *
 * <p>ARCore refuses to answer at all unless the application manifest says whether AR is needed:
 *
 * <pre>{@code
 * <meta-data android:name="com.google.ar.core" android:value="optional" />
 * }</pre>
 *
 * <p>{@code optional} for an app that works without AR, {@code required} for one that does not -
 * and {@code required} has the Play Store hide the app from every device that cannot do AR,
 * which is why this library does not declare it. That is the decision of the app, the same as
 * the camera permission, so the library manifest leaves it alone and this class reports the
 * omission rather than letting it look like a device fault.</p>
 */
@RequiresApi(24)
public final class ArAvailability {
    private static final String TAG = "ArAvailability";

    /** The manifest entry ARCore insists the application declares. */
    private static final String AR_CORE_META_DATA = "com.google.ar.core";

    /** How long {@link #awaitCheck} will wait for ARCore to answer, in milliseconds. */
    private static final long CHECK_TIMEOUT_MS = 2000;

    /** How often {@link #awaitCheck} asks again while the answer is still being worked out. */
    private static final long CHECK_INTERVAL_MS = 100;

    /** Whether the manifest declares AR, which cannot change while the process is alive. */
    private static volatile Boolean sDeclared;

    private ArAvailability() {}

    /** What the device can do, as far as anyone knows yet. */
    public enum State {
        /** ARCore is installed and this device is supported. */
        READY,
        /** Supported, but ARCore is missing or too old. {@link #requestInstall} fixes it. */
        NEEDS_INSTALL,
        /** AR cannot be used here, and nothing done at runtime will change that. */
        UNSUPPORTED,
        /** No answer yet, or the attempt to get one failed. Ask again shortly. */
        UNKNOWN,
    }

    /**
     * What the device can do, as of this instant.
     *
     * <p>Returns {@link State#UNKNOWN} rather than blocking while ARCore works the answer out,
     * which is what the first call on a cold start gets. Callers that want a settled answer
     * should use {@link #awaitCheck} or ask again a moment later; callers on a frame callback
     * that cannot afford either should take UNKNOWN as "not yet" and carry on.</p>
     *
     * <p>Never throws. A device without Play services at all answers {@link State#UNSUPPORTED},
     * which is the truthful answer and the one a caller can act on.</p>
     */
    @NonNull
    public static State check(@NonNull Context context) {
        if (!isDeclared(context)) {
            return State.UNSUPPORTED;
        }
        try {
            return toState(ArCoreApk.getInstance().checkAvailability(context));
        } catch (RuntimeException | LinkageError error) {
            // The client reaches for Play services as it is built, so a device with none fails
            // here rather than returning anything. A camera library may not crash for that.
            Log.w(TAG, "Cannot tell whether this device supports AR", error);
            return State.UNSUPPORTED;
        }
    }

    /**
     * What the device can do, waiting up to two seconds for ARCore to make up its mind.
     *
     * <p>Blocks, so not for the main thread. It exists because the useful question - can this
     * device do AR - has an answer that arrives over IPC, and a caller deciding once at startup
     * whether to offer an AR mode needs the answer rather than "still asking".</p>
     *
     * @return the settled state, or {@link State#UNKNOWN} if ARCore never answered
     */
    @NonNull
    public static State awaitCheck(@NonNull Context context) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Polling here would block the thread the answer has to come back on, so the wait
            // would time out every time. Say so rather than returning a wrong answer.
            throw new IllegalStateException("awaitCheck blocks and cannot run on the main thread");
        }
        long deadline = System.currentTimeMillis() + CHECK_TIMEOUT_MS;
        State state = check(context);
        while (state == State.UNKNOWN && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(CHECK_INTERVAL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return State.UNKNOWN;
            }
            state = check(context);
        }
        return state;
    }

    /**
     * What the device can do, reported on the main thread once ARCore has decided.
     *
     * <p>The form to prefer in an application: nothing blocks, and the listener is called once
     * with a settled state. A state of {@link State#UNKNOWN} means ARCore could not be asked at
     * all, which is worth retrying later rather than treating as a no.</p>
     */
    public static void checkAsync(@NonNull Context context, @NonNull Listener listener) {
        Handler handler = new Handler(Looper.getMainLooper());
        Thread thread = new Thread(() -> {
            State state = awaitCheck(context);
            handler.post(() -> listener.onAvailability(state));
        }, "ArAvailability");
        thread.setDaemon(true);
        thread.start();
    }

    /** Told what the device can do, on the main thread. */
    public interface Listener {
        /** @param state what the device can do, settled unless ARCore never replied */
        void onAvailability(@NonNull State state);
    }

    /**
     * Whether AR can be used right now without asking the user to install anything.
     *
     * <p>Waits for a settled answer, so it must not be called from the main thread. A device
     * that merely has not answered yet counts as a no, which is the only safe reading: there is
     * nothing to bind a session to until ARCore says there is.</p>
     */
    public static boolean isSupported(@NonNull Context context) {
        return awaitCheck(context) == State.READY;
    }

    /**
     * Asks the user to install or update ARCore.
     *
     * <p>Shows the ARCore install dialog and may take the user to the Play Store, so the
     * activity has to expect to be paused and resumed. Call it again on resume: the second call
     * is what reports whether they went through with it.</p>
     *
     * @param userAlreadyAsked false the first time, true on the call after a resume
     * @return true when the install flow was started and the caller should wait for resume
     */
    public static boolean requestInstall(@NonNull Activity activity, boolean userAlreadyAsked) {
        if (!isDeclared(activity)) {
            return false;
        }
        try {
            return ArCoreApk.getInstance().requestInstall(activity, !userAlreadyAsked)
                    == ArCoreApk.InstallStatus.INSTALL_REQUESTED;
        } catch (UnavailableUserDeclinedInstallationException declined) {
            // A refusal is an answer, not a failure.
            Log.i(TAG, "The user declined to install ARCore");
            return false;
        } catch (UnavailableDeviceNotCompatibleException | FatalException error) {
            Log.w(TAG, "ARCore cannot be installed on this device", error);
            return false;
        } catch (RuntimeException | LinkageError error) {
            Log.w(TAG, "Unable to ask for ARCore", error);
            return false;
        }
    }

    /**
     * Whether the application manifest declares AR, complaining once if it does not.
     *
     * <p>Checked here because ARCore does not report the omission: it logs a FatalException of
     * its own and hands back UNKNOWN_ERROR, which is indistinguishable from a device that could
     * not be reached - so a missing line of XML presents as an intermittent device fault, and
     * a caller that waits for a settled answer waits the whole timeout to be told nothing.
     * Reading the manifest here turns that into one message naming what to add.</p>
     */
    private static boolean isDeclared(@NonNull Context context) {
        Boolean declared = sDeclared;
        if (declared != null) {
            return declared;
        }
        declared = readDeclaration(context);
        if (!declared) {
            Log.e(TAG, "AR is unavailable because the application manifest does not declare it."
                    + " Add <meta-data android:name=\"" + AR_CORE_META_DATA + "\""
                    + " android:value=\"optional\" /> inside <application>, or \"required\" if"
                    + " the app cannot work without AR.");
        }
        sDeclared = declared;
        return declared;
    }

    private static boolean readDeclaration(@NonNull Context context) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(
                    context.getPackageName(), PackageManager.GET_META_DATA);
            return info.metaData != null && info.metaData.containsKey(AR_CORE_META_DATA);
        } catch (PackageManager.NameNotFoundException | RuntimeException error) {
            Log.w(TAG, "Unable to read the manifest to see whether AR is declared", error);
            return false;
        }
    }

    /**
     * Reads the answer, which does not divide the way the helper methods suggest.
     *
     * <p>{@code isSupported()} is true for a supported device whose ARCore is missing or out of
     * date as well as for one that is ready, so it cannot stand in for "AR works here"; and
     * {@code isTransient()} covers only the one state that means "still asking", not the two
     * that mean the asking failed. Naming every state is the only way to get both right.</p>
     */
    @NonNull
    private static State toState(@NonNull ArCoreApk.Availability availability) {
        switch (availability) {
            case SUPPORTED_INSTALLED:
                return State.READY;
            case SUPPORTED_NOT_INSTALLED:
            case SUPPORTED_APK_TOO_OLD:
                return State.NEEDS_INSTALL;
            case UNSUPPORTED_DEVICE_NOT_CAPABLE:
                return State.UNSUPPORTED;
            case UNKNOWN_CHECKING:
            case UNKNOWN_TIMED_OUT:
            case UNKNOWN_ERROR:
            default:
                // Not a no. These are reported when ARCore has not finished asking or could not
                // be reached, and a device that answers one of them now may answer READY next.
                Log.d(TAG, "ARCore has not decided yet: " + availability);
                return State.UNKNOWN;
        }
    }
}
