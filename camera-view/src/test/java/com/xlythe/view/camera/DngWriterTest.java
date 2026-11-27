package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.media.FakeImage;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.exifinterface.media.ExifInterface;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowCameraCharacteristics;
import org.robolectric.shadows.ShadowCaptureResult;
import org.robolectric.shadows.StreamConfigurationMapBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * The parts of writing a DNG that do not need a sensor: which way up it is, which RAW size it can
 * be written from, where it goes beside a JPEG, and what a write that fails leaves behind.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class DngWriterTest {
    private static final Size SENSOR = new Size(4000, 3000);
    private static final Size PIXEL_ARRAY = new Size(4032, 3024);
    private static final Size REMOSAIC = new Size(8000, 6000);

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    /**
     * The DNG's orientation is the one a JPEG beside it reaches through Exif's rotate and then
     * flip, so the two files of a RAW_JPEG capture agree about which way is up.
     */
    @Test
    public void theOrientationMatchesWhatExifDoesToTheJpeg() throws IOException {
        for (int degrees : new int[] {0, 90, 180, 270}) {
            for (boolean mirrored : new boolean[] {false, true}) {
                File file = folder.newFile();
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9});
                }
                Exif exif = new Exif(file);
                exif.rotate(degrees);
                if (mirrored) {
                    exif.flipHorizontally();
                }
                exif.save();

                // An unturned JPEG keeps no orientation at all, which reads as normal; a DNG has
                // to name one.
                int expected = new ExifInterface(file.getAbsolutePath()).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (expected == ExifInterface.ORIENTATION_UNDEFINED) {
                    expected = ExifInterface.ORIENTATION_NORMAL;
                }
                assertEquals(degrees + " degrees, mirrored " + mirrored,
                        expected, DngWriter.exifOrientation(degrees, mirrored));
            }
        }
    }

    /** Every angle answers one of the eight, which is all DngCreator accepts. */
    @Test
    public void anAngleOutsideOneTurnStillHasAnOrientation() {
        assertEquals(ExifInterface.ORIENTATION_NORMAL, DngWriter.exifOrientation(360, false));
        assertEquals(ExifInterface.ORIENTATION_ROTATE_270, DngWriter.exifOrientation(-90, false));
        assertEquals("no orientation expresses 45 degrees", ExifInterface.ORIENTATION_NORMAL,
                DngWriter.exifOrientation(45, false));
        assertEquals(ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
                DngWriter.exifOrientation(45, true));
    }

    @Test
    public void theDngGoesBesideTheJpegUnderItsName() {
        File folder = new File("/pictures");
        assertEquals(new File(folder, "IMG_1.dng"),
                DngWriter.dngBeside(new File(folder, "IMG_1.jpg")));
        assertEquals(new File(folder, "IMG_1.dng"),
                DngWriter.dngBeside(new File(folder, "IMG_1")));
        assertEquals("a name with no stem keeps all of it", new File(folder, ".jpg.dng"),
                DngWriter.dngBeside(new File(folder, ".jpg")));
        assertEquals("and a JPEG already called .dng is not overwritten",
                new File(folder, "IMG_1.dng.dng"),
                DngWriter.dngBeside(new File(folder, "IMG_1.dng")));
    }

    /** Not the largest RAW output, but the one the sensor's geometry describes. */
    @Test
    public void theSensorSizeIsPreferredOverALargerOutput() {
        CameraCharacteristics characteristics =
                characteristics(SENSOR, PIXEL_ARRAY, REMOSAIC, PIXEL_ARRAY, SENSOR);

        assertEquals(SENSOR, DngWriter.sensorSize(characteristics));
    }

    /** The pixel array is the other size DngCreator accepts. */
    @Test
    public void thePixelArrayIsTheFallback() {
        CameraCharacteristics characteristics =
                characteristics(SENSOR, PIXEL_ARRAY, REMOSAIC, PIXEL_ARRAY);

        assertEquals(PIXEL_ARRAY, DngWriter.sensorSize(characteristics));
    }

    /** A camera whose RAW outputs are all crops or remosaics cannot be written as a DNG. */
    @Test
    public void noSensorSizedOutputIsNoSize() {
        assertNull(DngWriter.sensorSize(characteristics(SENSOR, PIXEL_ARRAY, REMOSAIC)));
        assertNull("nor is a camera with no RAW outputs at all",
                DngWriter.sensorSize(characteristics(SENSOR, PIXEL_ARRAY)));
        assertNull(DngWriter.sensorSize(null));
        assertNull("nor one with no configuration map",
                DngWriter.sensorSize(ShadowCameraCharacteristics.newCameraCharacteristics()));
    }

    /**
     * A write DngCreator refuses fails, closes the frame, and leaves no file behind.
     *
     * <p>Characteristics with nothing in them are a camera DngCreator cannot describe, which is
     * the same failure as a sensor it disagrees with.</p>
     */
    @Test
    public void aRefusedWriteClosesTheFrameAndLeavesNoFile() throws IOException {
        File file = new File(folder.getRoot(), "refused.dng");
        TestFrame frame = new TestFrame(FakeImage.raw(8, 8, 1L));
        DngWriter writer = new DngWriter(ShadowCameraCharacteristics.newCameraCharacteristics(),
                pairing(frame, ShadowCaptureResult.newCaptureResult()),
                ExifInterface.ORIENTATION_NORMAL, file);

        assertFalse(writer.doInBackground());
        assertTrue("the frame has to go back to its reader", frame.image.isClosed());
        assertFalse(file.exists());
    }

    /** No location unless a backend supplies one. */
    @Test
    public void theDefaultLocationIsNone() {
        DngWriter writer = new DngWriter(ShadowCameraCharacteristics.newCameraCharacteristics(),
                pairing(new TestFrame(FakeImage.raw(8, 8, 1L)),
                        ShadowCaptureResult.newCaptureResult()),
                ExifInterface.ORIENTATION_NORMAL, new File(folder.getRoot(), "unused.dng"));

        assertNull(writer.getLocation());
    }

    private static RawCapturePairer.Pairing<CaptureResult> pairing(
            @NonNull TestFrame frame, @NonNull CaptureResult result) {
        RawCapturePairer<CaptureResult> pairer = new RawCapturePairer<>(1);
        pairer.offerFrame(frame);
        return pairer.offerMetadata(result, frame.getTimestamp());
    }

    /** A camera with this geometry, listing these RAW_SENSOR outputs. */
    private static CameraCharacteristics characteristics(
            @NonNull Size preCorrection, @NonNull Size pixelArray, Size... rawSizes) {
        CameraCharacteristics characteristics =
                ShadowCameraCharacteristics.newCameraCharacteristics();
        ShadowCameraCharacteristics shadow = Shadow.extract(characteristics);
        shadow.set(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE,
                new Rect(0, 0, preCorrection.getWidth(), preCorrection.getHeight()));
        shadow.set(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE, pixelArray);
        StreamConfigurationMapBuilder builder = StreamConfigurationMapBuilder.newBuilder();
        builder.addOutputSize(new Size(1920, 1080));
        for (Size size : rawSizes) {
            builder.addOutputSize(ImageFormat.RAW_SENSOR, size);
        }
        shadow.set(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP, builder.build());
        return characteristics;
    }

    private static final class TestFrame implements RawCapturePairer.Frame {
        private final FakeImage image;

        TestFrame(@NonNull FakeImage image) {
            this.image = image;
        }

        @Override
        public void writeTo(@NonNull DngCreator creator, @NonNull OutputStream output)
                throws IOException {
            creator.writeImage(output, image);
        }

        @Override
        public long getTimestamp() {
            return image.getTimestamp();
        }

        @Override
        public void close() {
            image.close();
        }
    }
}
