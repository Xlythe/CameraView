package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.location.Location;

import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Exif's orientation algebra, over all eight of them.
 *
 * <p>An EXIF orientation is not a rotation; it is one of eight combinations of a quarter turn and
 * a mirror, and rotating or flipping an image means moving between them along a table written out
 * by hand. A single wrong entry shows up as one lens, on one device, producing upside-down or
 * mirrored pictures - which is exactly the kind of thing nobody notices until it ships.</p>
 *
 * <p>So these are stated as properties that have to hold from every starting orientation, rather
 * than as a second copy of the table: four right turns is where you started, two flips is where
 * you started, and a turn one way undoes a turn the other. Between them they walk every entry.
 * </p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class ExifOrientationTest {
    /** Every orientation EXIF defines, plus the undefined one a file may carry. */
    private static final int[] ORIENTATIONS = {
            ExifInterface.ORIENTATION_UNDEFINED,
            ExifInterface.ORIENTATION_NORMAL,
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_FLIP_VERTICAL,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270,
    };

    private File file;

    @Before
    public void setUp() throws IOException {
        file = File.createTempFile("orientation", ".jpg");
        file.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(jpeg());
        }
    }

    @Test
    public void fourRightTurnsIsWhereYouStarted() throws IOException {
        for (int start : ORIENTATIONS) {
            Exif exif = given(start);
            String before = orientationOf(exif);

            exif.rotate(90);
            exif.rotate(90);
            exif.rotate(90);
            exif.rotate(90);

            assertEquals("from orientation " + start, before, orientationOf(exif));
        }
    }

    @Test
    public void fourLeftTurnsIsAlsoWhereYouStarted() throws IOException {
        for (int start : ORIENTATIONS) {
            Exif exif = given(start);
            String before = orientationOf(exif);

            exif.rotate(-90);
            exif.rotate(-90);
            exif.rotate(-90);
            exif.rotate(-90);

            assertEquals("from orientation " + start, before, orientationOf(exif));
        }
    }

    @Test
    public void aTurnOneWayUndoesATurnTheOther() throws IOException {
        for (int start : ORIENTATIONS) {
            Exif exif = given(start);
            String before = orientationOf(exif);

            exif.rotate(90);
            exif.rotate(-90);

            assertEquals("from orientation " + start, before, orientationOf(exif));
        }
    }

    @Test
    public void twoRightTurnsIsAHalfTurn() throws IOException {
        for (int start : ORIENTATIONS) {
            Exif twice = given(start);
            twice.rotate(90);
            twice.rotate(90);
            Exif once = given(start);
            once.rotate(180);

            assertEquals("from orientation " + start,
                    orientationOf(once), orientationOf(twice));
        }
    }

    @Test
    public void threeRightTurnsIsOneLeftTurn() throws IOException {
        for (int start : ORIENTATIONS) {
            Exif right = given(start);
            right.rotate(270);
            Exif left = given(start);
            left.rotate(-90);

            assertEquals("from orientation " + start,
                    orientationOf(left), orientationOf(right));
        }
    }

    @Test
    public void flippingTwiceIsWhereYouStarted() throws IOException {
        for (int start : ORIENTATIONS) {
            Exif horizontal = given(start);
            String before = orientationOf(horizontal);
            horizontal.flipHorizontally();
            horizontal.flipHorizontally();
            assertEquals("horizontally, from " + start, before, orientationOf(horizontal));

            Exif vertical = given(start);
            vertical.flipVertically();
            vertical.flipVertically();
            assertEquals("vertically, from " + start, before, orientationOf(vertical));
        }
    }

    /** Both flips together are a half turn, which is the one identity that ties the two tables. */
    @Test
    public void flippingBothWaysIsAHalfTurn() throws IOException {
        for (int start : ORIENTATIONS) {
            Exif flipped = given(start);
            flipped.flipHorizontally();
            flipped.flipVertically();
            Exif turned = given(start);
            turned.rotate(180);

            assertEquals("from orientation " + start,
                    orientationOf(turned), orientationOf(flipped));
        }
    }

    /** What is read back has to agree with what was written, for every orientation. */
    @Test
    public void theQueriesAgreeWithTheOrientation() throws IOException {
        for (int start : ORIENTATIONS) {
            Exif exif = given(start);
            int rotation = exif.getRotation();

            assertTrue("from orientation " + start + " the rotation was " + rotation,
                    rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270);
            // No orientation is both mirrors at once: a double mirror is a half turn, which is a
            // rotation, so reporting both would double-count it.
            assertFalse("from orientation " + start,
                    exif.isFlippedHorizontally() && exif.isFlippedVertically());
        }
    }

    /** Rotating by something that is not a right angle is refused, not half-applied. */
    @Test
    public void anImpossibleRotationLeavesTheOrientationUndefined() throws IOException {
        Exif exif = given(ExifInterface.ORIENTATION_ROTATE_90);

        exif.rotate(45);

        assertEquals("an undefined orientation reads as no rotation at all",
                orientationOf(given(ExifInterface.ORIENTATION_UNDEFINED)), orientationOf(exif));
        assertEquals(0, exif.getRotation());
    }

    @Test
    public void aFullTurnChangesNothing() throws IOException {
        for (int start : ORIENTATIONS) {
            Exif exif = given(start);
            String before = orientationOf(exif);

            exif.rotate(360);
            assertEquals("from orientation " + start, before, orientationOf(exif));

            exif.rotate(0);
            assertEquals("from orientation " + start, before, orientationOf(exif));
        }
    }

    /** More than a full turn is the same as the remainder. */
    @Test
    public void turningMoreThanOnceRoundIsTheRemainder() throws IOException {
        for (int start : ORIENTATIONS) {
            Exif far = given(start);
            far.rotate(450);
            Exif near = given(start);
            near.rotate(90);

            assertEquals("from orientation " + start,
                    orientationOf(near), orientationOf(far));
        }
    }

    /** Copying carries the orientation and the metadata, which is what a re-encode needs. */
    @Test
    public void copyingCarriesTheMetadata() throws IOException {
        Exif source = given(ExifInterface.ORIENTATION_TRANSVERSE);
        source.setDescription("the original");
        Location location = new Location("test");
        location.setLatitude(37.4219983);
        location.setLongitude(-122.084);
        location.setAltitude(5);
        source.attachLocation(location);
        source.attachTimestamp();
        source.save();

        File copy = File.createTempFile("copy", ".jpg");
        copy.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(copy)) {
            out.write(jpeg());
        }
        Exif destination = new Exif(copy);
        destination.copyFrom(new Exif(file));
        destination.save();

        Exif reread = new Exif(copy);
        assertEquals("the original", reread.getDescription());
        assertEquals(orientationOf(given(ExifInterface.ORIENTATION_TRANSVERSE)),
                orientationOf(reread));
        assertEquals(37.4219983, reread.getLocation().getLatitude(), 0.0001);
        assertEquals(-122.084, reread.getLocation().getLongitude(), 0.0001);
    }

    /** A removed location leaves nothing behind for the next reader to find. */
    @Test
    public void aRemovedLocationIsGone() throws IOException {
        Exif exif = given(ExifInterface.ORIENTATION_NORMAL);
        Location location = new Location("test");
        location.setLatitude(1.5);
        location.setLongitude(2.5);
        location.setSpeed(10f);
        exif.attachLocation(location);
        exif.save();
        assertEquals(1.5, new Exif(file).getLocation().getLatitude(), 0.0001);

        exif.removeLocation();
        exif.save();

        assertEquals("no location should survive", null, new Exif(file).getLocation());
    }

    /** Speed is stored in miles per hour, and has to come back as the metres per second given. */
    @Test
    public void speedSurvivesTheUnitItIsStoredIn() throws IOException {
        Exif exif = given(ExifInterface.ORIENTATION_NORMAL);
        Location location = new Location("test");
        location.setLatitude(1);
        location.setLongitude(2);
        location.setSpeed(13.4112f); // 30mph, give or take
        exif.attachLocation(location);
        exif.save();

        Location read = new Exif(file).getLocation();

        assertEquals(13.4112f, read.getSpeed(), 0.01f);
    }

    /** A removed timestamp stays removed across a save, which is the privacy promise. */
    @Test
    public void aRemovedTimestampStaysRemoved() throws IOException {
        Exif exif = given(ExifInterface.ORIENTATION_NORMAL);
        exif.attachTimestamp();
        exif.save();
        assertTrue(new Exif(file).getTimestamp() > 0);

        exif.removeTimestamp();
        exif.save();

        assertEquals(-1, new Exif(file).getTimestamp());
    }

    /**
     * A speed is read back in metres per second whatever unit it was written in.
     *
     * <p>EXIF stores a speed next to a one-letter unit - K, M or N - and a reader that ignores it
     * is wrong by a factor of 1.6 or 1.9. The library always writes K, so the other two only ever
     * arrive from someone else's camera, which is exactly why they are worth reading correctly.
     * </p>
     */
    @Test
    public void everySpeedUnitIsConvertedOnTheWayOut() throws IOException {
        // 100 km/h, and the same speed expressed the two other ways EXIF allows. Written as
        // rationals, because that is the type the tag has - a plain decimal reads back as zero,
        // which is the bug this library had writing them.
        assertSpeed("K", "100/1", 27.78f);
        assertSpeed("M", "62137/1000", 27.78f);
        assertSpeed("N", "53996/1000", 27.78f);
    }

    /** No unit at all reads as kilometres per hour, which is what the tag's default means. */
    @Test
    public void aSpeedWithNoUnitIsKilometresPerHour() throws IOException {
        assertSpeed(null, "100/1", 27.78f);
    }

    private void assertSpeed(@Nullable String unit, String value, float expectedMetresPerSecond)
            throws IOException {
        ExifInterface exifInterface = new ExifInterface(file.getAbsolutePath());
        exifInterface.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N");
        exifInterface.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "1/1,0/1,0/1");
        exifInterface.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E");
        exifInterface.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "2/1,0/1,0/1");
        exifInterface.setAttribute(ExifInterface.TAG_GPS_SPEED_REF, unit);
        exifInterface.setAttribute(ExifInterface.TAG_GPS_SPEED, value);
        exifInterface.saveAttributes();

        Location location = new Exif(file).getLocation();
        assertNotNull("a location with a speed should be readable", location);
        assertEquals("speed in " + unit, expectedMetresPerSecond, location.getSpeed(), 0.05f);
    }

    private Exif given(int orientation) throws IOException {
        ExifInterface exifInterface = new ExifInterface(file.getAbsolutePath());
        exifInterface.setAttribute(
                ExifInterface.TAG_ORIENTATION, String.valueOf(orientation));
        exifInterface.saveAttributes();
        return new Exif(file);
    }

    /**
     * The orientation as a caller can see it.
     *
     * <p>Exif does not expose the raw attribute, and it should not have to: what a consumer acts
     * on is the rotation and the two mirrors it decomposes into, so two orientations that read
     * the same here are the same picture.</p>
     */
    private static String orientationOf(Exif exif) {
        return "rotation=" + exif.getRotation()
                + " flippedHorizontally=" + exif.isFlippedHorizontally()
                + " flippedVertically=" + exif.isFlippedVertically();
    }

    /** The smallest thing ExifInterface will accept as a JPEG it can write into. */
    private static byte[] jpeg() {
        return new byte[] {
                (byte) 0xFF, (byte) 0xD8, // SOI
                (byte) 0xFF, (byte) 0xD9, // EOI
        };
    }
}
