package com.xlythe.view.camera;

import android.Manifest;
import android.app.Application;
import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(minSdk = 21)
public class PermissionCheckerTest {

    private Application application;
    private ShadowApplication shadowApplication;

    @Before
    public void setup() {
        application = RuntimeEnvironment.application;
        shadowApplication = shadowOf(application);
    }

    @Test
    public void testHasPermissionsGranted() {
        shadowApplication.grantPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);

        assertTrue(PermissionChecker.hasPermissions(application, Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO));
    }

    @Test
    public void testHasPermissionsDenied() {
        shadowApplication.denyPermissions(Manifest.permission.CAMERA);
        shadowApplication.grantPermissions(Manifest.permission.RECORD_AUDIO);

        assertFalse(PermissionChecker.hasPermissions(application, Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO));
    }
}
