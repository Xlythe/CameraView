package com.xlythe.view.camera;

import static com.xlythe.view.camera.FakeLocation.LATITUDE;
import static com.xlythe.view.camera.FakeLocation.LONGITUDE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.time.Duration;
import java.util.Arrays;

/**
 * What a capture is tagged with: which fixes the tracker listens for, which one it keeps, and
 * when it has nothing to say.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class LocationTrackerTest {
    /** About a hundred metres of latitude, which is a street away. */
    private static final double A_STREET = 0.001;

    private Context context;
    private LocationTracker tracker;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        FakeLocation.addProviders(context);
        tracker = new LocationTracker(context);
    }

    @After
    public void tearDown() {
        tracker.stop();
    }

    @Test
    public void nothingIsFollowedOrTaggedWithoutAPermission() {
        FakeLocation.setLastKnown(context, LocationManager.GPS_PROVIDER,
                FakeLocation.fix(LocationManager.GPS_PROVIDER, LATITUDE, LONGITUDE, 5));

        tracker.start();

        assertEquals("nothing may listen", 0, FakeLocation.listeners(context));
        assertNull("and nothing may be tagged", tracker.getLocation());
    }

    @Test
    public void thePrecisePermissionFollowsGpsAndTheNetwork() {
        FakeLocation.grantFine(context);

        tracker.start();

        assertEquals(1, FakeLocation.listeners(context, LocationManager.GPS_PROVIDER));
        assertEquals(1, FakeLocation.listeners(context, LocationManager.NETWORK_PROVIDER));
    }

    /** GPS needs the precise permission, so an app with only the approximate one never sees it. */
    @Test
    public void theApproximatePermissionFollowsTheNetworkAlone() {
        FakeLocation.grantCoarse(context);
        FakeLocation.setLastKnown(context, LocationManager.GPS_PROVIDER,
                FakeLocation.fix(LocationManager.GPS_PROVIDER, LATITUDE, LONGITUDE, 5));

        tracker.start();

        assertEquals(0, FakeLocation.listeners(context, LocationManager.GPS_PROVIDER));
        assertEquals(1, FakeLocation.listeners(context, LocationManager.NETWORK_PROVIDER));
        assertNull("a GPS fix is not ours to read", tracker.getLocation());

        FakeLocation.deliver(context, FakeLocation.fix(
                LocationManager.NETWORK_PROVIDER, LATITUDE + A_STREET, LONGITUDE, 100));

        assertEquals(LATITUDE + A_STREET, tracker.getLocation().getLatitude(), 1e-6);
    }

    @Test
    public void startingTwiceListensOnceAndStoppingStopsListening() {
        FakeLocation.grantFine(context);

        tracker.start();
        tracker.start();
        assertEquals(2, FakeLocation.listeners(context));

        tracker.stop();
        assertEquals(0, FakeLocation.listeners(context));

        tracker.start();
        assertEquals("and it can start again", 2, FakeLocation.listeners(context));
    }

    /** A capture straight after opening still has somewhere to be from. */
    @Test
    public void itStartsFromWhatTheSystemAlreadyKnows() {
        FakeLocation.grantFine(context);
        FakeLocation.setLastKnown(context, LocationManager.NETWORK_PROVIDER,
                FakeLocation.fix(LocationManager.NETWORK_PROVIDER, LATITUDE, LONGITUDE, 100));

        tracker.start();
        FakeLocation.setLastKnown(context, LocationManager.NETWORK_PROVIDER, null);

        Location location = tracker.getLocation();
        assertNotNull("the fix it started from is its own now", location);
        assertEquals(LATITUDE, location.getLatitude(), 1e-6);
    }

    @Test
    public void aMoreAccurateFixWins() {
        FakeLocation.grantFine(context);
        tracker.start();

        FakeLocation.deliver(context, FakeLocation.fix(
                LocationManager.NETWORK_PROVIDER, LATITUDE, LONGITUDE, 500));
        FakeLocation.deliver(context, FakeLocation.fix(
                LocationManager.GPS_PROVIDER, LATITUDE + A_STREET, LONGITUDE, 5));
        assertEquals(LATITUDE + A_STREET, tracker.getLocation().getLatitude(), 1e-6);

        ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(10));
        FakeLocation.deliver(context, FakeLocation.fix(
                LocationManager.NETWORK_PROVIDER, LATITUDE + 2 * A_STREET, LONGITUDE, 500));
        assertEquals("a newer but far vaguer fix from elsewhere does not",
                LATITUDE + A_STREET, tracker.getLocation().getLatitude(), 1e-6);
    }

    /** The same receiver reporting a little less certainly is still the latest word. */
    @Test
    public void aNewerFixFromTheSameProviderWinsThoughALittleLessAccurate() {
        FakeLocation.grantFine(context);
        tracker.start();

        FakeLocation.deliver(context, FakeLocation.fix(
                LocationManager.GPS_PROVIDER, LATITUDE, LONGITUDE, 5));
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(10));
        FakeLocation.deliver(context, FakeLocation.fix(
                LocationManager.GPS_PROVIDER, LATITUDE + A_STREET, LONGITUDE, 20));

        assertEquals(LATITUDE + A_STREET, tracker.getLocation().getLatitude(), 1e-6);
    }

    @Test
    public void aMuchNewerFixWinsHoweverVague() {
        FakeLocation.grantFine(context);
        tracker.start();

        FakeLocation.deliver(context, FakeLocation.fix(
                LocationManager.GPS_PROVIDER, LATITUDE, LONGITUDE, 5));
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMinutes(3));
        FakeLocation.deliver(context, FakeLocation.fix(
                LocationManager.NETWORK_PROVIDER, LATITUDE + A_STREET, LONGITUDE, 1000));

        assertEquals(LATITUDE + A_STREET, tracker.getLocation().getLatitude(), 1e-6);
    }

    @Test
    public void anOlderFixDoesNotWin() {
        FakeLocation.grantFine(context);
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMinutes(5));
        tracker.start();
        Location older = FakeLocation.fix(
                LocationManager.NETWORK_PROVIDER, LATITUDE + A_STREET, LONGITUDE, 1);
        Location slightlyOlder = FakeLocation.fix(
                LocationManager.NETWORK_PROVIDER, LATITUDE + 2 * A_STREET, LONGITUDE, 50);
        older.setElapsedRealtimeNanos(older.getElapsedRealtimeNanos()
                - Duration.ofMinutes(3).toNanos());
        slightlyOlder.setElapsedRealtimeNanos(slightlyOlder.getElapsedRealtimeNanos()
                - Duration.ofSeconds(30).toNanos());

        FakeLocation.deliver(context, FakeLocation.fix(
                LocationManager.GPS_PROVIDER, LATITUDE, LONGITUDE, 50));
        FakeLocation.deliver(context, older);
        assertEquals("not even a far more accurate one",
                LATITUDE, tracker.getLocation().getLatitude(), 1e-6);

        FakeLocation.deliver(context, slightlyOlder);
        assertEquals("nor a slightly older one that is no more accurate",
                LATITUDE, tracker.getLocation().getLatitude(), 1e-6);
    }

    /** Older than two hours, a fix may name a different town. */
    @Test
    public void aStaleFixIsNotUsed() {
        FakeLocation.grantFine(context);
        tracker.start();
        FakeLocation.deliver(context, FakeLocation.fix(
                LocationManager.GPS_PROVIDER, LATITUDE, LONGITUDE, 5));
        assertNotNull(tracker.getLocation());

        ShadowLooper.shadowMainLooper().idleFor(Duration.ofHours(2).plusMinutes(1));

        assertNull(tracker.getLocation());
    }

    @Test
    public void aWithdrawnPermissionStopsTagging() {
        FakeLocation.grantFine(context);
        tracker.start();
        FakeLocation.deliver(context, FakeLocation.fix(
                LocationManager.GPS_PROVIDER, LATITUDE, LONGITUDE, 5));

        FakeLocation.deny(context);

        assertNull(tracker.getLocation());
    }

    /** Which is how an app that asks for the permission only once the camera is up still tags. */
    @Test
    public void aPermissionGrantedAfterStartingStillTagsFromWhatIsKnown() {
        tracker.start();
        FakeLocation.setLastKnown(context, LocationManager.GPS_PROVIDER,
                FakeLocation.fix(LocationManager.GPS_PROVIDER, LATITUDE, LONGITUDE, 5));

        FakeLocation.grantFine(context);

        Location location = tracker.getLocation();
        assertNotNull(location);
        assertEquals(LATITUDE, location.getLatitude(), 1e-6);
    }

    /** The permission can go between our check and the call that needs it. */
    @Test
    public void aPermissionLostMidCallIsSurvived() {
        FakeLocation.grantFine(context);
        LocationManager refusing = mock(LocationManager.class);
        when(refusing.getAllProviders()).thenReturn(Arrays.asList(
                LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER));
        when(refusing.getLastKnownLocation(anyString())).thenThrow(new SecurityException());
        doThrow(new SecurityException()).when(refusing).requestLocationUpdates(anyString(),
                anyLong(), anyFloat(), any(LocationListener.class), any(Looper.class));
        Context application = RuntimeEnvironment.getApplication();
        LocationTracker refused = new LocationTracker(new ContextWrapper(application) {
            @Override
            public Context getApplicationContext() {
                return this;
            }

            @Override
            public Object getSystemService(String name) {
                return Context.LOCATION_SERVICE.equals(name)
                        ? refusing : super.getSystemService(name);
            }
        });

        refused.start();

        assertNull(refused.getLocation());
        refused.stop();
    }
}
