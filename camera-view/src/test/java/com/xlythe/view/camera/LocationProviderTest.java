package com.xlythe.view.camera;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLocationManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(minSdk = 21)
public class LocationProviderTest {

    private Context context;
    private LocationManager locationManager;
    private ShadowLocationManager shadowLocationManager;

    @Before
    public void setup() {
        context = RuntimeEnvironment.application;
        shadowOf(context.getPackageManager()).setSystemFeature(PackageManager.FEATURE_LOCATION_GPS, true);
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        shadowLocationManager = shadowOf(locationManager);
    }

    @Test
    public void testGetGPSLocationCached() {
        Location mockLocation = new Location(LocationManager.GPS_PROVIDER);
        mockLocation.setLatitude(37.4220);
        mockLocation.setLongitude(-122.0841);
        mockLocation.setTime(System.currentTimeMillis());

        shadowLocationManager.setLastKnownLocation(LocationManager.GPS_PROVIDER, mockLocation);

        Location location = LocationProvider.getGPSLocation(context, 60000, 1000);
        assertNotNull(location);
        assertEquals(37.4220, location.getLatitude(), 0.0001);
        assertEquals(-122.0841, location.getLongitude(), 0.0001);
    }

    @Test
    public void testGetGPSLocationTimeout() {
        shadowLocationManager.setLastKnownLocation(LocationManager.GPS_PROVIDER, null);

        Location location = LocationProvider.getGPSLocation(context, 60000, 50);
        assertNull(location);
    }
}
