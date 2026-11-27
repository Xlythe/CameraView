package com.xlythe.view.camera;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;

/**
 * The conditions a picture was taken in, as {@link Exif} records them. Every reading is
 * optional, and one that wasn't recorded is NaN.
 */
@AnyThread
public final class AmbientConditions {
    /** The ambient temperature in degrees Celsius, or NaN. */
    private final double mTemperature;
    /** The relative humidity as a percentage, or NaN. */
    private final double mHumidity;
    /** The ambient pressure in hectopascals, or NaN. */
    private final double mPressure;
    /** The depth below the water's surface in metres, or NaN. */
    private final double mWaterDepth;
    /** The acceleration in milligals, or NaN. */
    private final double mAcceleration;
    /** How far above the horizontal the camera pointed, in degrees, or NaN. */
    private final double mCameraElevationAngle;

    private AmbientConditions(@NonNull Builder builder) {
        mTemperature = builder.mTemperature;
        mHumidity = builder.mHumidity;
        mPressure = builder.mPressure;
        mWaterDepth = builder.mWaterDepth;
        mAcceleration = builder.mAcceleration;
        mCameraElevationAngle = builder.mCameraElevationAngle;
    }

    /** The ambient temperature in degrees Celsius, or NaN. */
    public double getTemperature() {
        return mTemperature;
    }

    /** The relative humidity as a percentage, or NaN. */
    public double getHumidity() {
        return mHumidity;
    }

    /** The ambient pressure in hectopascals, or NaN. */
    public double getPressure() {
        return mPressure;
    }

    /** The depth below the water's surface in metres, or NaN. */
    public double getWaterDepth() {
        return mWaterDepth;
    }

    /** The acceleration in milligals, where one metre per second squared is 100,000, or NaN. */
    public double getAcceleration() {
        return mAcceleration;
    }

    /** How far above the horizontal the camera pointed, in degrees, negative below it, or NaN. */
    public double getCameraElevationAngle() {
        return mCameraElevationAngle;
    }

    /** Whether nothing was recorded. */
    boolean isEmpty() {
        for (double reading : readings()) {
            if (!Double.isNaN(reading)) {
                return false;
            }
        }
        return true;
    }

    @NonNull
    private double[] readings() {
        return new double[] {mTemperature, mHumidity, mPressure, mWaterDepth, mAcceleration,
                mCameraElevationAngle};
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AmbientConditions)) {
            return false;
        }
        double[] mine = readings();
        double[] theirs = ((AmbientConditions) other).readings();
        for (int i = 0; i < mine.length; i++) {
            // Double.compare, which holds two NaNs equal: neither recorded the reading.
            if (Double.compare(mine[i], theirs[i]) != 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(readings());
    }

    /** The readings that were recorded, by name. */
    @NonNull
    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("AmbientConditions{");
        String separator = "";
        String[] names = {"temperature", "humidity", "pressure", "waterDepth", "acceleration",
                "cameraElevationAngle"};
        double[] readings = readings();
        for (int i = 0; i < readings.length; i++) {
            if (!Double.isNaN(readings[i])) {
                text.append(separator).append(names[i]).append('=').append(readings[i]);
                separator = ", ";
            }
        }
        return text.append('}').toString();
    }

    /** Builds {@link AmbientConditions} from whichever readings there are. */
    public static final class Builder {
        /** The ambient temperature in degrees Celsius, or NaN. */
        private double mTemperature = Double.NaN;
        /** The relative humidity as a percentage, or NaN. */
        private double mHumidity = Double.NaN;
        /** The ambient pressure in hectopascals, or NaN. */
        private double mPressure = Double.NaN;
        /** The depth below the water's surface in metres, or NaN. */
        private double mWaterDepth = Double.NaN;
        /** The acceleration in milligals, or NaN. */
        private double mAcceleration = Double.NaN;
        /** How far above the horizontal the camera pointed, in degrees, or NaN. */
        private double mCameraElevationAngle = Double.NaN;

        /** Sets the ambient temperature in degrees Celsius, or NaN for none. */
        @NonNull
        public Builder setTemperature(double celsius) {
            mTemperature = celsius;
            return this;
        }

        /** Sets the relative humidity as a percentage, or NaN for none. */
        @NonNull
        public Builder setHumidity(double percent) {
            mHumidity = percent;
            return this;
        }

        /** Sets the ambient pressure in hectopascals, or NaN for none. */
        @NonNull
        public Builder setPressure(double hectopascals) {
            mPressure = hectopascals;
            return this;
        }

        /** Sets the depth below the water's surface in metres, or NaN for none. */
        @NonNull
        public Builder setWaterDepth(double metres) {
            mWaterDepth = metres;
            return this;
        }

        /**
         * Sets the acceleration in milligals, where one metre per second squared is 100,000, or
         * NaN for none.
         */
        @NonNull
        public Builder setAcceleration(double milligals) {
            mAcceleration = milligals;
            return this;
        }

        /**
         * Sets how far above the horizontal the camera pointed, in degrees, negative below it, or
         * NaN for none.
         */
        @NonNull
        public Builder setCameraElevationAngle(double degrees) {
            mCameraElevationAngle = degrees;
            return this;
        }

        @NonNull
        public AmbientConditions build() {
            return new AmbientConditions(this);
        }
    }
}
