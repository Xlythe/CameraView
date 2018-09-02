package com.xlythe.view.camera;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import androidx.annotation.WorkerThread;

public class LocationProvider {
    @Nullable
    @WorkerThread
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    public static Location getGPSLocation(Context context, long locationCacheAllowance, long queryTimeoutMillis) {
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)) {
            return null;
        }

        LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (location == null || location.getTime() < System.currentTimeMillis() - locationCacheAllowance) {
            LocationCallback locationCallback = new LocationCallback();
            locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, locationCallback, Looper.getMainLooper());
            return locationCallback.await(queryTimeoutMillis, TimeUnit.MILLISECONDS);
        }
        return location;
    }

    private static class LocationCallback implements LocationListener {
        private final CountDownLatch latch = new CountDownLatch(1);
        private Location location;

        Location await(long timeout, TimeUnit unit) {
            try {
                latch.await(timeout, unit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return location;
        }

        @Override
        public void onLocationChanged(Location location) {
            this.location = location;
            latch.countDown();
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}

        @Override
        public void onProviderEnabled(String provider) {}

        @Override
        public void onProviderDisabled(String provider) {}
    }
}
