package com.xlythe.view.camera.ar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;

import com.google.ar.core.ArCoreApk;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/** Whether AR can run, answered without waiting and without failing where it cannot. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public final class ArSupportTest {
    private Context context;

    @Before
    public void setUp() {
        ArSupport.reset();
        context = RuntimeEnvironment.getApplication();
    }

    @After
    public void tearDown() {
        ArSupport.reset();
    }

    @Test
    public void theTestsIncludeArcore() {
        assertTrue(ArSupport.isLibraryPresent());
    }

    /** An app whose manifest does not declare AR cannot run it, whatever the device can do. */
    @Test
    public void anAppThatDoesNotDeclareArCannotRunIt() {
        assertEquals(ArSupport.UNSUPPORTED, ArSupport.availability(context));
        assertEquals("the answer is kept", ArSupport.UNSUPPORTED,
                ArSupport.availability(context));
    }

    /**
     * An app that declares AR is answered by ARCore, which here has no Play services to ask and
     * so cannot say yes.
     */
    @Test
    public void aDeclaringAppIsAnsweredByArcore() {
        declareAr();
        int availability = ArSupport.availability(context);
        assertTrue("never ready without Google Play Services for AR",
                availability == ArSupport.UNSUPPORTED || availability == ArSupport.UNKNOWN);
    }

    /**
     * A device ARCore would not start a session on is not offered AR again, whatever ARCore
     * answers about it, until the app starts afresh.
     */
    @Test
    public void aDeviceArcoreRefusedIsNotOfferedArAgain() {
        declareAr();
        assertEquals("still being asked, so worth trying", ArSupport.UNKNOWN,
                ArSupport.availability(context));

        ArSupport.onSessionRefused();
        assertEquals(ArSupport.UNSUPPORTED, ArSupport.availability(context));
        assertEquals("the answer is kept", ArSupport.UNSUPPORTED,
                ArSupport.availability(context));

        ArSupport.reset();
        assertEquals(ArSupport.UNKNOWN, ArSupport.availability(context));
    }

    /** Only installed and supported is ready; still being checked is worth trying. */
    @Test
    public void everyAnswerArcoreGivesMeansSomething() {
        assertEquals(ArSupport.READY,
                ArSupport.Installed.of(ArCoreApk.Availability.SUPPORTED_INSTALLED));
        assertEquals(ArSupport.UNKNOWN,
                ArSupport.Installed.of(ArCoreApk.Availability.UNKNOWN_CHECKING));
        assertEquals(ArSupport.UNKNOWN,
                ArSupport.Installed.of(ArCoreApk.Availability.UNKNOWN_TIMED_OUT));
        assertEquals(ArSupport.UNKNOWN,
                ArSupport.Installed.of(ArCoreApk.Availability.UNKNOWN_ERROR));
        assertEquals(ArSupport.UNSUPPORTED,
                ArSupport.Installed.of(ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED));
        assertEquals(ArSupport.UNSUPPORTED,
                ArSupport.Installed.of(ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD));
        assertEquals(ArSupport.UNSUPPORTED,
                ArSupport.Installed.of(ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE));
    }

    /** Puts the meta-data an app that enters AR declares into the test app's manifest. */
    private void declareAr() {
        PackageInfo info = Shadows.shadowOf(context.getPackageManager())
                .getInternalMutablePackageInfo(context.getPackageName());
        Bundle metaData = new Bundle();
        metaData.putString("com.google.ar.core", "optional");
        info.applicationInfo.metaData = metaData;
    }
}
