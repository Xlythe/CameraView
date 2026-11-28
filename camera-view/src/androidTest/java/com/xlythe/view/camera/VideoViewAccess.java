package com.xlythe.view.camera;

import androidx.annotation.NonNull;

/** Reaches VideoView's test hooks from the viewer package's device tests. */
public final class VideoViewAccess {
    private VideoViewAccess() {}

    /** Whether the video is showing on the HDR surface. */
    public static boolean isShowingHdr(@NonNull VideoView view) {
        return view.isShowingHdr();
    }
}
