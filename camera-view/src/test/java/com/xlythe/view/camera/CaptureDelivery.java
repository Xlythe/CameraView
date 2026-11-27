package com.xlythe.view.camera;

import android.webkit.MimeTypeMap;

import org.robolectric.Shadows;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Finishes a capture the way a backend would, for tests outside this package that cannot reach
 * CameraView's package-private callbacks themselves.
 */
public final class CaptureDelivery {
    private CaptureDelivery() {}

    /**
     * Lets Robolectric's MediaStore take a DNG. Below API 31 Robolectric answers MIME lookups from
     * a table of its own, which has no DNG, and its MediaStore refuses a type it cannot map to an
     * extension. The platform's own table lists it.
     */
    public static void allowDngInMediaStore() {
        Shadows.shadowOf(MimeTypeMap.getSingleton())
                .addExtensionMimeTypeMapping("dng", "image/x-adobe-dng");
    }

    /**
     * A cache file named as takePicture() names one for this format, for a backend test to hand
     * its module in place of a file the caller chose.
     */
    public static File ownPictureFile(CameraView cameraView, CameraView.PictureFormat format) {
        return CameraView.newCaptureFile(
                cameraView.getContext(), CameraView.pictureExtension(format));
    }

    /** The directory takePicture() leaves its cache files in until they are filed. */
    public static File captureDirectory(CameraView cameraView) {
        return CameraView.captureDirectory(cameraView.getContext());
    }

    /**
     * Writes a RAW_JPEG picture named as takePicture() names one, with its DNG beside the JPEG,
     * and reports both finished the way a backend does.
     */
    public static File deliverOwnRawJpeg(CameraView cameraView, byte[] jpeg, byte[] dng)
            throws IOException {
        File picture = ownPictureFile(cameraView, CameraView.PictureFormat.RAW_JPEG);
        File raw = DngWriter.dngBeside(picture);
        write(picture, jpeg);
        write(raw, dng);
        cameraView.showImageConfirmation(picture, Arrays.asList(picture, raw));
        return picture;
    }

    private static void write(File file, byte[] contents) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(contents);
        }
    }

    /** Writes a video named as startRecording() names one, and reports it finished. */
    public static File deliverOwnVideo(CameraView cameraView, byte[] contents) throws IOException {
        File video = CameraView.newCaptureFile(cameraView.getContext(), ".mp4");
        try (FileOutputStream output = new FileOutputStream(video)) {
            output.write(contents);
        }
        cameraView.showVideoConfirmation(video);
        return video;
    }
}
