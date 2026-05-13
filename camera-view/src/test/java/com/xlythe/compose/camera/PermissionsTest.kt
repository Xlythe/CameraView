package com.xlythe.compose.camera

import android.Manifest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(minSdk = 21)
class PermissionsTest {

    @Test
    fun testPermissionsList() {
        val required = Permissions.REQUIRED_PERMISSIONS.toList()
        assertTrue(required.contains(Manifest.permission.CAMERA))
        assertTrue(required.contains(Manifest.permission.RECORD_AUDIO))

        val optional = Permissions.OPTIONAL_PERMISSIONS.toList()
        assertTrue(optional.isNotEmpty())
    }
}
