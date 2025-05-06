package com.xlythe.compose.camera

import android.Manifest
import android.os.Build

/**
 * Defines the required and optional permissions for the camera component
 * based on the Android SDK version.
 */
object Permissions {
    val REQUIRED_PERMISSIONS: Array<String>
    val OPTIONAL_PERMISSIONS: Array<String>

    init {
        when {
            // Android 14+ (API 34+)
            Build.VERSION.SDK_INT >= 34 -> {
                REQUIRED_PERMISSIONS = arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                )
                OPTIONAL_PERMISSIONS = arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.VIBRATE,
                )
            }
            // Android 13 (API 33)
            Build.VERSION.SDK_INT == 33 -> {
                REQUIRED_PERMISSIONS = arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                )
                OPTIONAL_PERMISSIONS = arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.VIBRATE,
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
                )
            }
            // Android 4.4+ (API 19+) up to Android 12L (API 32)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT -> {
                REQUIRED_PERMISSIONS = arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                )
                OPTIONAL_PERMISSIONS = arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.VIBRATE
                )
            }
            // Below Android 4.4 (API < 19)
            else -> {
                REQUIRED_PERMISSIONS = arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE // Required before KitKat
                )
                OPTIONAL_PERMISSIONS = arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.VIBRATE
                )
            }
        }
    }
}
