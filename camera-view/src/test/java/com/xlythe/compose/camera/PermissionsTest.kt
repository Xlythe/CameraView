package com.xlythe.compose.camera

import android.Manifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which permissions the Compose camera needs, per platform.
 *
 * <p>Asking for a permission the platform no longer has is a dialog that never appears and a
 * feature that silently does not work; asking for one it has not introduced yet is a manifest
 * error. Both are decided by version arithmetic that nothing checked - so these pin the two
 * boundaries where the list actually changes.</p>
 */
@RunWith(RobolectricTestRunner::class)
class PermissionsTest {
    /** The camera and the microphone are needed on every platform. */
    @Test
    @Config(sdk = [37])
    fun theCameraAndMicrophoneAreAlwaysRequired() {
        assertTrue(Permissions.REQUIRED_PERMISSIONS.contains(Manifest.permission.CAMERA))
        assertTrue(Permissions.REQUIRED_PERMISSIONS.contains(Manifest.permission.RECORD_AUDIO))
    }

    /**
     * From API 34 the media-read permissions are gone from the list.
     *
     * <p>They were how an app read back what it had just saved, and the photo picker replaced
     * them; asking now gets a dialog the user cannot grant.</p>
     */
    @Test
    @Config(sdk = [37])
    fun aboveApi33TheMediaPermissionsAreNotAskedFor() {
        val optional = Permissions.OPTIONAL_PERMISSIONS.toList()

        assertFalse(optional.contains(Manifest.permission.READ_MEDIA_IMAGES))
        assertFalse(optional.contains(Manifest.permission.READ_MEDIA_VIDEO))
        assertFalse("and external storage has been gone far longer",
                optional.contains(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        assertTrue(optional.contains(Manifest.permission.ACCESS_FINE_LOCATION))
    }

    /** On API 33 exactly, the media-read permissions are the way to read saved media. */
    @Test
    @Config(sdk = [33])
    fun onApi33TheMediaPermissionsAreAskedFor() {
        val optional = Permissions.OPTIONAL_PERMISSIONS.toList()

        assertTrue(optional.contains(Manifest.permission.READ_MEDIA_IMAGES))
        assertTrue(optional.contains(Manifest.permission.READ_MEDIA_VIDEO))
        assertFalse("external storage was already gone by then",
                optional.contains(Manifest.permission.WRITE_EXTERNAL_STORAGE))
    }

    /** Below API 33, writing to external storage is how a file is saved at all. */
    @Test
    @Config(sdk = [32])
    fun belowApi33ExternalStorageIsAskedFor() {
        val optional = Permissions.OPTIONAL_PERMISSIONS.toList()

        assertTrue(optional.contains(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        assertFalse("the media permissions did not exist yet",
                optional.contains(Manifest.permission.READ_MEDIA_IMAGES))
        assertTrue(optional.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertTrue(optional.contains(Manifest.permission.VIBRATE))
    }

    /** Nothing optional is also required, or a caller cannot tell which it may skip. */
    @Test
    @Config(sdk = [37])
    fun theTwoListsDoNotOverlap() {
        val required = Permissions.REQUIRED_PERMISSIONS.toSet()
        val optional = Permissions.OPTIONAL_PERMISSIONS.toSet()

        assertTrue("overlap would make 'optional' meaningless",
                required.intersect(optional).isEmpty())
    }
}
