package com.xlythe.view.camera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.xlythe.view.camera.ICameraModule.TAG;

/**
 * Follows the device's location while the camera is open, so that a capture can be tagged
 * without waiting for a fix.
 *
 * <p>A fix asked for when the shutter is pressed either holds the capture until the radio answers
 * or, with a short timeout, comes back with whatever the system already had. Listening from the
 * moment the camera opens gives the radio the time the user spends framing the shot instead.</p>
 *
 * <p>We never ask for a permission. We listen only if the app already holds one, and a capture is
 * only tagged while it still does. A permission granted while the camera is open is picked up by
 * {@link #refresh}.</p>
 */
final class LocationTracker {
    /**
     * How old a fix may be and still describe where a capture was taken.
     *
     * <p>Two hours, because what is wanted is the place rather than the moment, and a fix from
     * earlier in the same outing names the place correctly.</p>
     */
    private static final long STALE_LOCATION_NANOS = TimeUnit.HOURS.toNanos(2);

    /**
     * How often we ask for a fix while the camera is open.
     *
     * <p>A capture is placed to the street it was taken on, and a fix this fresh does that as well
     * as one taken at the shutter. Next to the camera that is running, the radio costs little.</p>
     */
    private static final long UPDATE_INTERVAL_MILLIS = TimeUnit.SECONDS.toMillis(5);

    /** How far the device has to move for a new fix. Closer than this is the same street. */
    private static final float UPDATE_DISTANCE_METERS = 10;

    /** How much newer a fix has to be to replace ours however inaccurate it is. */
    private static final long SIGNIFICANTLY_NEWER_NANOS = TimeUnit.MINUTES.toNanos(2);

    /** How much less accurate a newer fix from the same provider may be and still replace ours. */
    private static final float ACCURACY_TOLERANCE_METERS = 200;

    /** What we listen to. GPS needs the fine permission, and the network either one. */
    private static final String[] PROVIDERS = {
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
    };

    /** The application rather than an activity, since the system holds our listener. */
    private final Context mContext;

    /** Null only on a device with no location service at all, where we never track anything. */
    @Nullable private final LocationManager mLocationManager;

    /** Registered with every provider we can use, and called on the main thread. */
    private final LocationListener mListener = new LocationListener() {
        @MainThread
        @Override
        public void onLocationChanged(@NonNull Location location) {
            // An update already queued when stop() unregistered us can still be delivered.
            if (mStarted) {
                offer(location);
            }
        }

        // Abstract before API 30, so they need implementing even though we ignore them.
        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}

        @Override
        public void onProviderEnabled(@NonNull String provider) {}

        @Override
        public void onProviderDisabled(@NonNull String provider) {}
    };

    /**
     * The best fix since the camera opened. Written on the main thread, and read from whichever
     * thread a capture finishes on.
     */
    @Nullable private volatile Location mBestLocation;

    /** Whether the camera is open, so that we follow whatever we have the permission for. */
    private boolean mStarted;

    /** The providers we are registered with. Main thread only. */
    private final List<String> mFollowed = new ArrayList<>(PROVIDERS.length);

    LocationTracker(@NonNull Context context) {
        Context application = context.getApplicationContext();
        mContext = application == null ? context : application;
        mLocationManager = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
    }

    /** Starts listening to every provider the app already holds the permission for. */
    @MainThread
    void start() {
        if (mLocationManager == null) {
            return;
        }
        mStarted = true;
        follow();
    }

    /**
     * Listens to any provider the app has been granted the permission for since we started, such
     * as GPS once approximate location becomes precise. Does nothing while stopped.
     */
    @MainThread
    void refresh() {
        if (mStarted) {
            follow();
        }
    }

    /** Stops listening, and forgets what we heard. */
    @MainThread
    void stop() {
        if (!mStarted) {
            return;
        }
        mStarted = false;
        if (!mFollowed.isEmpty()) {
            removeUpdates();
            mFollowed.clear();
        }
        mBestLocation = null;
    }

    /** Registers with each usable provider we are not yet registered with. */
    @MainThread
    private void follow() {
        for (String provider : getUsableProviders()) {
            if (mFollowed.contains(provider)) {
                continue;
            }
            offer(getLastKnownLocation(provider));
            if (requestUpdates(provider)) {
                mFollowed.add(provider);
            }
        }
    }

    /**
     * Where to tag a capture, or null when that is not known or not ours to read.
     *
     * <p>The best fix we have heard since starting, or the best the system already has when we
     * have heard nothing, as straight after a permission is granted. Either way it is no more than
     * two hours old, and it comes from a provider the app still has the permission for. Never
     * waits for a fix.</p>
     */
    @AnyThread
    @Nullable
    Location getLocation() {
        if (mLocationManager == null) {
            return null;
        }
        Location best = mBestLocation;
        if (best == null || isStale(best) || !canUse(best.getProvider())) {
            best = null;
            for (String provider : getUsableProviders()) {
                Location known = getLastKnownLocation(provider);
                if (known != null && !isStale(known) && isBetter(known, best)) {
                    best = known;
                }
            }
        }
        // A copy, because the caller hands it on to code that may change it.
        return best == null ? null : new Location(best);
    }

    @MainThread
    private void offer(@Nullable Location candidate) {
        if (candidate != null && isBetter(candidate, mBestLocation)) {
            mBestLocation = candidate;
        }
    }

    /**
     * Whether a fix should replace the one we hold.
     *
     * <p>A much newer fix always does, because the device may have moved since ours. Otherwise the
     * more accurate one wins, and a newer one from the same provider wins unless it is far less
     * accurate, since that is the same radio following the device.</p>
     */
    private static boolean isBetter(@NonNull Location candidate, @Nullable Location current) {
        if (current == null) {
            return true;
        }
        long newerByNanos = candidate.getElapsedRealtimeNanos() - current.getElapsedRealtimeNanos();
        if (newerByNanos > SIGNIFICANTLY_NEWER_NANOS) {
            return true;
        }
        if (newerByNanos < -SIGNIFICANTLY_NEWER_NANOS) {
            return false;
        }
        float lessAccurateByMeters = getAccuracy(candidate) - getAccuracy(current);
        if (lessAccurateByMeters < 0) {
            return true;
        }
        if (newerByNanos <= 0) {
            return false;
        }
        return lessAccurateByMeters == 0
                || (lessAccurateByMeters <= ACCURACY_TOLERANCE_METERS
                        && TextUtils.equals(candidate.getProvider(), current.getProvider()));
    }

    /** The radius of a fix's uncertainty. A fix that does not say is the least certain of all. */
    private static float getAccuracy(@NonNull Location location) {
        return location.hasAccuracy() ? location.getAccuracy() : Float.MAX_VALUE;
    }

    /**
     * Whether a fix is too old to describe where a capture was taken.
     *
     * <p>Measured on the clock since boot, because the wall clock can be set backwards.</p>
     */
    private static boolean isStale(@NonNull Location location) {
        return SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos()
                > STALE_LOCATION_NANOS;
    }

    /** The providers this device has that we hold the permission for. */
    @AnyThread
    @NonNull
    private List<String> getUsableProviders() {
        List<String> usable = new ArrayList<>(PROVIDERS.length);
        List<String> available = mLocationManager.getAllProviders();
        for (String provider : PROVIDERS) {
            if (available.contains(provider) && canUse(provider)) {
                usable.add(provider);
            }
        }
        return usable;
    }

    /** Whether we hold the permission a provider needs: fine for GPS, and either for the rest. */
    @AnyThread
    private boolean canUse(@Nullable String provider) {
        if (PermissionChecker.hasPermissions(mContext, Manifest.permission.ACCESS_FINE_LOCATION)) {
            return true;
        }
        return !LocationManager.GPS_PROVIDER.equals(provider)
                && PermissionChecker.hasPermissions(
                        mContext, Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    /**
     * Asks a provider to keep us told, on the main thread, and returns whether it agreed.
     *
     * <p>Suppressed because we check the permission ourselves, through {@link #canUse}, and lint
     * cannot follow that check. Without the permission we do nothing.</p>
     */
    @SuppressLint("MissingPermission")
    @MainThread
    private boolean requestUpdates(@NonNull String provider) {
        if (!canUse(provider)) {
            return false;
        }
        try {
            mLocationManager.requestLocationUpdates(provider, UPDATE_INTERVAL_MILLIS,
                    UPDATE_DISTANCE_METERS, mListener, Looper.getMainLooper());
            return true;
        } catch (SecurityException e) {
            // The permission can be withdrawn between the check and the call.
            Log.w(TAG, "Lost the permission to follow " + provider, e);
            return false;
        }
    }

    /**
     * Unregisters us from every provider.
     *
     * <p>Suppressed because API 22 and below want a location permission to unregister as well,
     * and lint cannot see that we hold it: we only registered with it, through {@link #canUse},
     * and those versions grant it at install, so it cannot have gone since.</p>
     */
    @SuppressLint("MissingPermission")
    @MainThread
    private void removeUpdates() {
        mLocationManager.removeUpdates(mListener);
    }

    /**
     * The fix a provider already has, without waiting for a new one.
     *
     * <p>Suppressed because we check the permission ourselves, through {@link #canUse}, and lint
     * cannot follow that check. Without the permission we return null.</p>
     */
    @SuppressLint("MissingPermission")
    @AnyThread
    @Nullable
    private Location getLastKnownLocation(@NonNull String provider) {
        if (!canUse(provider)) {
            return null;
        }
        try {
            return mLocationManager.getLastKnownLocation(provider);
        } catch (SecurityException e) {
            // The permission can be withdrawn between the check and the call.
            Log.w(TAG, "Lost the permission to read " + provider, e);
            return null;
        }
    }
}
