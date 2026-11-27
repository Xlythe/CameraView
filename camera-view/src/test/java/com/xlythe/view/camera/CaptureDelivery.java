package com.xlythe.view.camera;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Finishes a capture the way a backend would, for tests outside this package that cannot reach
 * CameraView's package-private callbacks themselves.
 */
public final class CaptureDelivery {
    private CaptureDelivery() {}

    /** Writes a video named as startRecording() names one, and reports it finished. */
    public static File deliverOwnVideo(CameraView cameraView, byte[] contents) throws IOException {
        File video = CameraView.newCaptureFile(cameraView.getContext(), true /* isVideo */);
        try (FileOutputStream output = new FileOutputStream(video)) {
            output.write(contents);
        }
        cameraView.showVideoConfirmation(video);
        return video;
    }
}
