package com.xlythe.view.camera;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.SystemClock;

import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowLocationManager;
import org.robolectric.shadows.ShadowLooper;

/**
 * Gives Robolectric's LocationManager the providers, permissions and fixes a phone would have.
 *
 * <p>The shadow hands a listener an update only once it is the listener's interval and distance
 * on from the last one, as a device does, so the fixes made here are stamped on the clock since
 * boot and tests space them out on it.</p>
 */
public final class FakeLocation {
    /** Where the fake device is, unless a test moves it. */
    public static final double LATITUDE = 37.4220;

    /** See {@link #LATITUDE}. */
    public static final double LONGITUDE = -122.0841;

    private FakeLocation() {}

    /** Turns on a GPS receiver and the network provider, as every phone has. */
    public static void addProviders(Context context) {
        Shadows.shadowOf(context.getPackageManager())
                .setSystemFeature(PackageManager.FEATURE_LOCATION_GPS, true);
        ShadowLocationManager locationManager = shadowLocationManager(context);
        locationManager.setLocationEnabled(true);
        locationManager.setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        locationManager.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
    }

    /** Grants precise location, which a device grants together with approximate. */
    public static void grantFine(Context context) {
        Shadows.shadowOf((Application) context.getApplicationContext()).grantPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    /** Grants approximate location alone. */
    public static void grantCoarse(Context context) {
        Shadows.shadowOf((Application) context.getApplicationContext()).grantPermissions(
                Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    /** Withdraws both location permissions. */
    public static void deny(Context context) {
        Shadows.shadowOf((Application) context.getApplicationContext()).denyPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    /** A fix taken now. */
    public static Location fix(String provider, double latitude, double longitude,
            float accuracyMeters) {
        Location location = new Location(provider);
        location.setLatitude(latitude);
        location.setLongitude(longitude);
        location.setAccuracy(accuracyMeters);
        location.setTime(System.currentTimeMillis());
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        return location;
    }

    /** Hands a fix to everyone listening to its provider, and runs their callbacks. */
    public static void deliver(Context context, Location location) {
        shadowLocationManager(context).simulateLocation(location);
        ShadowLooper.shadowMainLooper().idle();
    }

    /** Sets what the system already knows from a provider, before anyone has listened. */
    public static void setLastKnown(Context context, String provider, Location location) {
        shadowLocationManager(context).setLastKnownLocation(provider, location);
    }

    /** How many listeners are registered with a provider, for a single update or for many. */
    public static int listeners(Context context, String provider) {
        return shadowLocationManager(context).getLocationUpdateListeners(provider).size();
    }

    /** How many listeners are registered with GPS and the network together. */
    public static int listeners(Context context) {
        return listeners(context, LocationManager.GPS_PROVIDER)
                + listeners(context, LocationManager.NETWORK_PROVIDER);
    }

    private static ShadowLocationManager shadowLocationManager(Context context) {
        return Shadows.shadowOf(
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE));
    }
}
