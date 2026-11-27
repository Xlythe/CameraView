package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The conditions a picture was taken in, as a value: built from whichever readings exist. */
public class AmbientConditionsTest {
    /** Each reading comes back as it was set, in the units its setter names. */
    @Test
    public void everyReadingIsKept() {
        AmbientConditions conditions = new AmbientConditions.Builder()
                .setTemperature(21.5)
                .setHumidity(40)
                .setPressure(1013.25)
                .setWaterDepth(3)
                .setAcceleration(980665)
                .setCameraElevationAngle(-12)
                .build();

        assertEquals(21.5, conditions.getTemperature(), 0);
        assertEquals(40, conditions.getHumidity(), 0);
        assertEquals(1013.25, conditions.getPressure(), 0);
        assertEquals(3, conditions.getWaterDepth(), 0);
        assertEquals(980665, conditions.getAcceleration(), 0);
        assertEquals(-12, conditions.getCameraElevationAngle(), 0);
        assertFalse(conditions.isEmpty());
    }

    /**
     * A reading nobody set is NaN, as is one set to NaN, which is how a sensor that has no
     * reading leaves it out.
     */
    @Test
    public void anAbsentReadingIsNaN() {
        AmbientConditions conditions = new AmbientConditions.Builder()
                .setTemperature(21.5)
                .setHumidity(Float.NaN)
                .build();

        assertTrue(Double.isNaN(conditions.getHumidity()));
        assertTrue(Double.isNaN(conditions.getPressure()));
        assertTrue(Double.isNaN(conditions.getWaterDepth()));
        assertTrue(Double.isNaN(conditions.getAcceleration()));
        assertTrue(Double.isNaN(conditions.getCameraElevationAngle()));
        assertTrue(new AmbientConditions.Builder().build().isEmpty());
    }

    /** Two with the same readings are equal, absent ones included, and hash alike. */
    @Test
    public void theSameReadingsAreEqual() {
        AmbientConditions one = new AmbientConditions.Builder().setPressure(990).build();
        AmbientConditions two = new AmbientConditions.Builder().setPressure(990).build();
        AmbientConditions other = new AmbientConditions.Builder().setPressure(991).build();

        assertEquals(one, two);
        assertEquals(one.hashCode(), two.hashCode());
        assertNotEquals(one, other);
        assertNotEquals(one,
                new AmbientConditions.Builder().setPressure(990).setHumidity(1).build());
        assertNotEquals(one, "990 hPa");
        assertEquals(one, one);
    }

    /** The summary names the readings there are and nothing else. */
    @Test
    public void theSummaryNamesWhatWasRecorded() {
        assertEquals("AmbientConditions{temperature=21.5, cameraElevationAngle=-12.0}",
                new AmbientConditions.Builder()
                        .setTemperature(21.5)
                        .setCameraElevationAngle(-12)
                        .build()
                        .toString());
        assertEquals("AmbientConditions{}", new AmbientConditions.Builder().build().toString());
    }
}
