package com.xlythe.view.camera;

import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.RestrictTo;
import androidx.core.content.ContextCompat;

@RestrictTo(RestrictTo.Scope.LIBRARY)
public class PermissionChecker {
    /**
     * Returns true if all given permissions are available
     */
    public static boolean hasPermissions(Context context, String... permissions) {
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
}
