package com.xlythe.view.camera.ar;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;

import com.google.ar.core.ArCoreApk;
import com.xlythe.view.camera.ICameraModule;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Whether AR can run on this device, in this app, without anything touching ARCore's classes
 * until it is known they are there.
 *
 * <p>Three things have to hold, and each fails on its own. The app has to include
 * {@code com.google.ar:core}, which this library only compiles against. Its manifest has to
 * declare the {@code com.google.ar.core} meta-data, without which ARCore refuses to answer and
 * reports an error indistinguishable from a device it cannot reach. And the device has to be one
 * ARCore supports, with Google Play Services for AR installed.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class ArSupport {
    /** Log tag shared with the camera modules. */
    private static final String TAG = ICameraModule.TAG;

    /** A class every version of ARCore's client library has. */
    private static final String ARCORE_CLASS = "com.google.ar.core.Session";

    /** The meta-data ARCore insists the application declares. */
    private static final String MANIFEST_KEY = "com.google.ar.core";

    /** AR runs here, now. */
    public static final int READY = 0;
    /** ARCore has not finished asking whether it runs here. Worth trying, and asking again. */
    public static final int UNKNOWN = 1;
    /** AR cannot run here, and nothing this app does at runtime changes that. */
    public static final int UNSUPPORTED = 2;

    /** What {@link #availability} reports. */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({READY, UNKNOWN, UNSUPPORTED})
    public @interface Availability {}

    /** Whether ARCore's client library is in the app, which cannot change while it runs. */
    private static volatile Boolean sLibraryPresent;

    /** Whether the manifest declares AR, which cannot change while the app runs either. */
    private static volatile Boolean sDeclared;

    private ArSupport() {}

    /**
     * Whether AR can run here, as far as is known without waiting.
     *
     * <p>Never blocks, and never throws for a device or an app that cannot do AR.</p>
     */
    @AnyThread
    @Availability
    public static int availability(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < 24 || !isLibraryPresent() || !isDeclared(context)) {
            return UNSUPPORTED;
        }
        return Installed.availability(context);
    }

    /** Whether ARCore's client library is in the app. */
    @AnyThread
    public static boolean isLibraryPresent() {
        Boolean present = sLibraryPresent;
        if (present == null) {
            try {
                Class.forName(ARCORE_CLASS, false, ArSupport.class.getClassLoader());
                present = true;
            } catch (ClassNotFoundException | LinkageError error) {
                Log.i(TAG, "AR is unavailable because the app does not include com.google.ar:core");
                present = false;
            }
            sLibraryPresent = present;
        }
        return present;
    }

    /** Forgets what was learned about the app, for a test that changes it. */
    @VisibleForTesting
    static void reset() {
        sLibraryPresent = null;
        sDeclared = null;
    }

    /**
     * Whether the application manifest declares AR, saying once what to add if it does not.
     *
     * <p>Read here because ARCore does not report the omission: it logs an exception of its own
     * and answers as if the device could not be reached.</p>
     */
    private static boolean isDeclared(@NonNull Context context) {
        Boolean declared = sDeclared;
        if (declared != null) {
            return declared;
        }
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(
                    context.getPackageName(), PackageManager.GET_META_DATA);
            declared = info.metaData != null && info.metaData.containsKey(MANIFEST_KEY);
        } catch (PackageManager.NameNotFoundException | RuntimeException error) {
            Log.w(TAG, "Unable to read the manifest to see whether it declares AR", error);
            declared = false;
        }
        if (!declared) {
            Log.w(TAG, "AR is unavailable because the application manifest does not declare it."
                    + " Add <meta-data android:name=\"" + MANIFEST_KEY + "\""
                    + " android:value=\"optional\" /> inside <application>.");
        }
        sDeclared = declared;
        return declared;
    }

    /** What touches ARCore, loaded only once its classes are known to be there. */
    @RequiresApi(24)
    @VisibleForTesting
    static final class Installed {
        private Installed() {}

        @Availability
        static int availability(@NonNull Context context) {
            ArCoreApk.Availability availability;
            try {
                availability = ArCoreApk.getInstance().checkAvailability(context);
            } catch (RuntimeException | LinkageError error) {
                // The client reaches for Play services as it asks, so a device without them fails
                // here rather than answering.
                Log.w(TAG, "Unable to ask ARCore whether it runs here", error);
                return UNSUPPORTED;
            }
            return of(availability);
        }

        /**
         * What ARCore's answer comes to. Missing or out of date counts as unsupported, since
         * installing Google Play Services for AR is the app's to offer, and an answer still being
         * worked out as unknown, which is worth trying and asking again.
         */
        @VisibleForTesting
        @Availability
        static int of(@NonNull ArCoreApk.Availability availability) {
            switch (availability) {
                case SUPPORTED_INSTALLED:
                    return READY;
                case UNKNOWN_CHECKING:
                case UNKNOWN_TIMED_OUT:
                case UNKNOWN_ERROR:
                    return UNKNOWN;
                case SUPPORTED_NOT_INSTALLED:
                case SUPPORTED_APK_TOO_OLD:
                case UNSUPPORTED_DEVICE_NOT_CAPABLE:
                default:
                    return UNSUPPORTED;
            }
        }
    }
}
