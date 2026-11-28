package com.xlythe.view.camera.edit;

import androidx.annotation.AnyThread;
import androidx.annotation.ColorInt;
import androidx.annotation.FloatRange;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;

/**
 * How a stroke looks, apart from where it went: its kind, width, colour and opacity, and how it
 * answers how hard the pen presses, how far it leans and how fast it moves. Brushes are
 * immutable; {@link Builder} makes a changed copy.
 *
 * <p>A device that cannot say how hard, how far over or how fast draws at the brush's resting
 * width for that answer, never a made-up middle value, so a line drawn with a finger or a mouse
 * is the brush's plain line.</p>
 *
 * <p>Every field here is written into a drawing's JSON, which is the same format Muse's ink
 * documents use, so a drawing moves between apps without changing how it looks.</p>
 */
public final class Brush {
    /** What kind of mark a brush makes. */
    @IntDef({TYPE_PEN, TYPE_MARKER, TYPE_PENCIL, TYPE_ERASER})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {}

    /** A line of even density whose width follows pressure. */
    public static final int TYPE_PEN = 0;
    /** A wide, translucent, flat highlighter. */
    public static final int TYPE_MARKER = 1;
    /** A grainy line whose opacity follows pressure and whose width follows the lean. */
    public static final int TYPE_PENCIL = 2;
    /** Takes away the marks under it, never what they are drawn over. */
    public static final int TYPE_ERASER = 3;

    /** What a stamped brush's texture is made of. */
    @IntDef({GRAIN_NONE, GRAIN_SPECKLE, GRAIN_CHALK, GRAIN_DRY})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Grain {}

    /** Solid. */
    public static final int GRAIN_NONE = 0;
    /** Scattered specks, as graphite leaves on rough paper. */
    public static final int GRAIN_SPECKLE = 1;
    /** Broad, soft mottling, as chalk or charcoal leaves. */
    public static final int GRAIN_CHALK = 2;
    /** Streaks that grow along the stroke, as a brush running out of paint leaves. */
    public static final int GRAIN_DRY = 3;

    /** The seed a texture's noise starts from unless one is given. */
    static final int DEFAULT_SEED = 0x5EED;

    /** A fine dark pen. */
    public static final Brush PEN = new Builder().build();

    /** A wide translucent yellow highlighter. */
    public static final Brush MARKER = new Builder()
            .setType(TYPE_MARKER)
            .setWidth(18f)
            .setColor(0xFFFFE082)
            .setPressureResponse(1f, 1f, 1f)
            .setOpacity(0.4f)
            .build();

    /** A soft pencil that broadens and goes pale as it leans, showing paper through its grain. */
    public static final Brush PENCIL = new Builder()
            .setType(TYPE_PENCIL)
            .setWidth(4f)
            .setColor(0xFF4A4A4A)
            .setPressureResponse(0.4f, 1f, 1.6f)
            .setOpacity(0.85f)
            .setTiltResponse(1f, 2.4f, 1f, 0.55f, 60f)
            .setSpacing(0.12f)
            .setFlow(0.55f)
            .setGrain(GRAIN_CHALK, 0.45f, 2.4f, DEFAULT_SEED)
            .build();

    /** Broad, soft charcoal. */
    public static final Brush CHARCOAL = new Builder()
            .setType(TYPE_PENCIL)
            .setWidth(12f)
            .setColor(0xFF2B2B2B)
            .setPressureResponse(0.5f, 1.2f, 1.3f)
            .setOpacity(0.9f)
            .setTiltResponse(1f, 2.4f, 1f, 0.55f, 60f)
            .setSpacing(0.16f)
            .setFlow(0.45f)
            .setGrain(GRAIN_CHALK, 0.55f, 5f, DEFAULT_SEED)
            .build();

    /** An inking pen that tapers when it is flicked. */
    public static final Brush INKING_PEN = new Builder()
            .setWidth(5f)
            .setColor(0xFF101010)
            .setPressureResponse(0.15f, 1f, 1.4f)
            .setVelocityResponse(1f, 0.55f, 2.5f, 1f)
            .build();

    /** A brush that runs dry as it is dragged. */
    public static final Brush DRY_BRUSH = new Builder()
            .setType(TYPE_PENCIL)
            .setWidth(16f)
            .setColor(0xFF303030)
            .setPressureResponse(0.35f, 1f, 1.2f)
            .setOpacity(0.95f)
            .setVelocityResponse(0.8f, 1.3f, 2.5f, 1f)
            .setSpacing(0.18f)
            .setFlow(0.4f)
            .setGrain(GRAIN_DRY, 0.6f, 4f, DEFAULT_SEED)
            .build();

    /** An eraser sized for comfortable removal. */
    public static final Brush ERASER = new Builder()
            .setType(TYPE_ERASER)
            .setWidth(20f)
            .setColor(0x00000000)
            .setPressureResponse(1f, 1f, 1f)
            .build();

    /** The kind of mark. */
    @Type private final int mType;
    /** The width at full pressure, in the drawing's units. */
    private final float mWidth;
    /** The colour, as ARGB. */
    @ColorInt private final int mColor;
    /** A further opacity on top of the colour's own alpha. */
    private final float mOpacity;
    /** The width multiplier at no pressure. */
    private final float mPressureMinScale;
    /** The width multiplier at full pressure. */
    private final float mPressureMaxScale;
    /** The pressure curve's shape: 1 is straight, above 1 makes light touches thinner. */
    private final float mPressureExponent;
    /** The width multiplier with the pen upright. */
    private final float mTiltUprightScale;
    /** The width multiplier with the pen leaned right over. */
    private final float mTiltFlatScale;
    /** The opacity multiplier with the pen upright. */
    private final float mTiltUprightAlpha;
    /** The opacity multiplier with the pen leaned right over. */
    private final float mTiltFlatAlpha;
    /** The lean, in degrees from upright, at which the flat multipliers are reached. */
    private final float mTiltFlatDegrees;
    /** The width multiplier with the pen still. */
    private final float mVelocitySlowScale;
    /** The width multiplier at the reference speed and above. */
    private final float mVelocityFastScale;
    /** The speed, in drawing units a millisecond, at which the fast multiplier is reached. */
    private final float mVelocityReference;
    /** The speed curve's shape. */
    private final float mVelocityExponent;
    /** The gap between dabs as a fraction of the width, or 0 for a continuous line. */
    private final float mSpacing;
    /** How much each dab lays down. */
    private final float mFlow;
    /** The texture. */
    @Grain private final int mGrain;
    /** How much of a dab the texture takes away. */
    private final float mGrainDepth;
    /** How coarse the texture is, in drawing units. */
    private final float mGrainScale;
    /** The seed the texture's noise starts from. */
    private final int mGrainSeed;

    private Brush(@NonNull Builder builder) {
        mType = builder.mType;
        mWidth = builder.mWidth;
        mColor = builder.mColor;
        mOpacity = builder.mOpacity;
        mPressureMinScale = builder.mPressureMinScale;
        mPressureMaxScale = builder.mPressureMaxScale;
        mPressureExponent = builder.mPressureExponent;
        mTiltUprightScale = builder.mTiltUprightScale;
        mTiltFlatScale = builder.mTiltFlatScale;
        mTiltUprightAlpha = builder.mTiltUprightAlpha;
        mTiltFlatAlpha = builder.mTiltFlatAlpha;
        mTiltFlatDegrees = builder.mTiltFlatDegrees;
        mVelocitySlowScale = builder.mVelocitySlowScale;
        mVelocityFastScale = builder.mVelocityFastScale;
        mVelocityReference = builder.mVelocityReference;
        mVelocityExponent = builder.mVelocityExponent;
        mSpacing = builder.mSpacing;
        mFlow = builder.mFlow;
        mGrain = builder.mGrain;
        mGrainDepth = builder.mGrainDepth;
        mGrainScale = builder.mGrainScale;
        mGrainSeed = builder.mGrainSeed;
    }

    @AnyThread
    @Type
    public int getType() {
        return mType;
    }

    /** The width at full pressure, in the drawing's units. */
    @AnyThread
    public float getWidth() {
        return mWidth;
    }

    @AnyThread
    @ColorInt
    public int getColor() {
        return mColor;
    }

    /** A further opacity, 0 to 1, on top of the colour's own alpha. */
    @AnyThread
    public float getOpacity() {
        return mOpacity;
    }

    /** The width multiplier at the lightest touch. */
    @AnyThread
    public float getPressureMinScale() {
        return mPressureMinScale;
    }

    /** The width multiplier at full pressure, and with no pressure sensor. */
    @AnyThread
    public float getPressureMaxScale() {
        return mPressureMaxScale;
    }

    /** The pressure curve's shape: 1 is straight, above 1 makes light touches thinner. */
    @AnyThread
    public float getPressureExponent() {
        return mPressureExponent;
    }

    /** The width multiplier with the pen upright, and with no tilt sensor. */
    @AnyThread
    public float getTiltUprightScale() {
        return mTiltUprightScale;
    }

    /** The width multiplier with the pen leaned right over. */
    @AnyThread
    public float getTiltFlatScale() {
        return mTiltFlatScale;
    }

    /** The opacity multiplier with the pen upright, and with no tilt sensor. */
    @AnyThread
    public float getTiltUprightAlpha() {
        return mTiltUprightAlpha;
    }

    /** The opacity multiplier with the pen leaned right over. */
    @AnyThread
    public float getTiltFlatAlpha() {
        return mTiltFlatAlpha;
    }

    /** The lean, in degrees from upright, at which the flat multipliers are reached. */
    @AnyThread
    public float getTiltFlatDegrees() {
        return mTiltFlatDegrees;
    }

    /** The width multiplier with the pen still, and when the speed is unknown. */
    @AnyThread
    public float getVelocitySlowScale() {
        return mVelocitySlowScale;
    }

    /** The width multiplier at the reference speed and above. */
    @AnyThread
    public float getVelocityFastScale() {
        return mVelocityFastScale;
    }

    /** The speed, in drawing units a millisecond, at which the fast multiplier is reached. */
    @AnyThread
    public float getVelocityReference() {
        return mVelocityReference;
    }

    /** The speed curve's shape: 1 is straight. */
    @AnyThread
    public float getVelocityExponent() {
        return mVelocityExponent;
    }

    /** The gap between dabs as a fraction of the width, or 0 for a continuous line. */
    @AnyThread
    public float getSpacing() {
        return mSpacing;
    }

    /** How much each dab lays down, 0 to 1. */
    @AnyThread
    public float getFlow() {
        return mFlow;
    }

    @AnyThread
    @Grain
    public int getGrain() {
        return mGrain;
    }

    /** How much of a dab the texture takes away, 0 to 1. */
    @AnyThread
    public float getGrainDepth() {
        return mGrainDepth;
    }

    /** How coarse the texture is, in drawing units. */
    @AnyThread
    public float getGrainScale() {
        return mGrainScale;
    }

    /** The seed the texture's noise starts from, so a stroke grains alike on every device. */
    @AnyThread
    public int getGrainSeed() {
        return mGrainSeed;
    }

    /** Whether this brush takes marks away rather than making them. */
    @AnyThread
    public boolean isErasing() {
        return mType == TYPE_ERASER;
    }

    /** Whether this brush lays separate dabs rather than a continuous line. */
    @AnyThread
    boolean isStamped() {
        return mSpacing > 0f || !hasNoGrain();
    }

    private boolean hasNoGrain() {
        return mGrain == GRAIN_NONE || mGrainDepth == 0f;
    }

    /** The widest half-width this brush can draw, for the bounds of what it covers. */
    @AnyThread
    float maxRadius() {
        return mWidth * mPressureMaxScale * Math.max(mTiltUprightScale, mTiltFlatScale)
                * Math.max(mVelocitySlowScale, mVelocityFastScale) / 2f;
    }

    /** How far over the pen leans, 0 to 1, from its two tilts, or NaN when unknown. */
    @AnyThread
    float leanOf(float tiltXDegrees, float tiltYDegrees) {
        if (Float.isNaN(tiltXDegrees) || Float.isNaN(tiltYDegrees)) {
            return Float.NaN;
        }
        float lean = (float) Math.sqrt(tiltXDegrees * tiltXDegrees + tiltYDegrees * tiltYDegrees);
        return clamp(lean / mTiltFlatDegrees, 0f, 1f);
    }

    /**
     * The width of a sample, from how hard the pen pressed, how far it leaned (0 to 1) and how
     * fast it moved. Any of them may be NaN, for a device that cannot say.
     */
    @AnyThread
    float widthFor(float pressure, float lean, float speed) {
        return mWidth * pressureScale(pressure) * tiltScale(lean) * velocityScale(speed);
    }

    /** The opacity of a sample leaned over by {@code lean}, 0 to 1, or NaN when unknown. */
    @AnyThread
    float alphaFor(float lean) {
        if (Float.isNaN(lean)) {
            return mOpacity * mTiltUprightAlpha;
        }
        return mOpacity * (mTiltUprightAlpha
                + (mTiltFlatAlpha - mTiltUprightAlpha) * clamp(lean, 0f, 1f));
    }

    private float pressureScale(float pressure) {
        if (Float.isNaN(pressure)) {
            return mPressureMaxScale;
        }
        float clamped = clamp(pressure, 0f, 1f);
        float shaped = mPressureExponent == 1f ? clamped : power(clamped, mPressureExponent);
        return mPressureMinScale + (mPressureMaxScale - mPressureMinScale) * shaped;
    }

    private float tiltScale(float lean) {
        if (Float.isNaN(lean)) {
            return mTiltUprightScale;
        }
        return mTiltUprightScale + (mTiltFlatScale - mTiltUprightScale) * clamp(lean, 0f, 1f);
    }

    private float velocityScale(float speed) {
        if (Float.isNaN(speed)) {
            return mVelocitySlowScale;
        }
        float normalised = clamp(speed / mVelocityReference, 0f, 1f);
        float shaped = mVelocityExponent == 1f ? normalised
                : power(Math.max(normalised, 1e-6f), mVelocityExponent);
        return mVelocitySlowScale + (mVelocityFastScale - mVelocitySlowScale) * shaped;
    }

    /**
     * How much ink lands at a point, 0 to 1, through this brush's texture. {@code along} is how
     * far through the stroke the dab is, which lets a dry brush run out towards the end. The
     * same point gives the same answer on every device.
     */
    @AnyThread
    float coverageAt(float x, float y, float along) {
        if (hasNoGrain()) {
            return 1f;
        }
        float cell = noise(x / mGrainScale, y / mGrainScale, mGrainSeed);
        switch (mGrain) {
            case GRAIN_SPECKLE:
                return cell < mGrainDepth ? 0f : 1f;
            case GRAIN_CHALK:
                return clamp(1f - mGrainDepth * cell, 0f, 1f);
            case GRAIN_DRY: {
                float drying = clamp(along, 0f, 1f) * mGrainDepth;
                return cell < drying ? 0f : clamp(1f - drying * 0.5f, 0f, 1f);
            }
            default:
                return 1f;
        }
    }

    /**
     * Value noise from 0 to 1, from a hash of the whole cell a point falls in blended across the
     * cell, rather than of floating point bits, which differ between devices.
     */
    static float noise(float x, float y, int seed) {
        int xi = (int) Math.floor(x);
        int yi = (int) Math.floor(y);
        float xf = x - xi;
        float yf = y - yi;
        float a = cell(xi, yi, seed);
        float b = cell(xi + 1, yi, seed);
        float c = cell(xi, yi + 1, seed);
        float d = cell(xi + 1, yi + 1, seed);
        float u = xf * xf * (3f - 2f * xf);
        float v = yf * yf * (3f - 2f * yf);
        return (a * (1 - u) + b * u) * (1 - v) + (c * (1 - u) + d * u) * v;
    }

    private static float cell(int x, int y, int seed) {
        int h = x * 374761393 + y * 668265263 + seed * 1274126177;
        h = (h ^ (h >> 13)) * 1274126177;
        h = h ^ (h >> 16);
        return Math.abs(h % 1000) / 1000f;
    }

    private static float power(float base, float exponent) {
        if (base <= 0f) {
            return 0f;
        }
        return (float) Math.exp(exponent * Math.log(base));
    }

    static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /** A copy in another colour. */
    @AnyThread
    @NonNull
    public Brush withColor(@ColorInt int color) {
        return new Builder(this).setColor(color).build();
    }

    /** A copy at another width. */
    @AnyThread
    @NonNull
    public Brush withWidth(float width) {
        return new Builder(this).setWidth(width).build();
    }

    /** A copy at another opacity. */
    @AnyThread
    @NonNull
    public Brush withOpacity(@FloatRange(from = 0, to = 1) float opacity) {
        return new Builder(this).setOpacity(opacity).build();
    }

    private float[] values() {
        return new float[] {mType, mWidth, mColor, mOpacity, mPressureMinScale,
                mPressureMaxScale, mPressureExponent, mTiltUprightScale, mTiltFlatScale,
                mTiltUprightAlpha, mTiltFlatAlpha, mTiltFlatDegrees, mVelocitySlowScale,
                mVelocityFastScale, mVelocityReference, mVelocityExponent, mSpacing, mFlow, mGrain,
                mGrainDepth, mGrainScale, mGrainSeed};
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof Brush && mColor == ((Brush) other).mColor
                && mGrainSeed == ((Brush) other).mGrainSeed
                && Arrays.equals(values(), ((Brush) other).values());
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(values()) + mColor;
    }

    @NonNull
    @Override
    public String toString() {
        return "Brush{type=" + mType + ", width=" + mWidth + ", color="
                + Integer.toHexString(mColor) + ", opacity=" + mOpacity + "}";
    }

    /** Builds a brush, starting from a fine dark pen. */
    public static final class Builder {
        @Type private int mType = TYPE_PEN;
        private float mWidth = 3f;
        @ColorInt private int mColor = 0xFF202020;
        private float mOpacity = 1f;
        private float mPressureMinScale = 0.25f;
        private float mPressureMaxScale = 1f;
        private float mPressureExponent = 1f;
        private float mTiltUprightScale = 1f;
        private float mTiltFlatScale = 1f;
        private float mTiltUprightAlpha = 1f;
        private float mTiltFlatAlpha = 1f;
        private float mTiltFlatDegrees = 60f;
        private float mVelocitySlowScale = 1f;
        private float mVelocityFastScale = 1f;
        private float mVelocityReference = 2.5f;
        private float mVelocityExponent = 1f;
        private float mSpacing;
        private float mFlow = 1f;
        @Grain private int mGrain = GRAIN_NONE;
        private float mGrainDepth;
        private float mGrainScale = 3f;
        private int mGrainSeed = DEFAULT_SEED;

        /** Starts from a fine dark pen. */
        public Builder() {}

        /** Starts from another brush. */
        public Builder(@NonNull Brush brush) {
            mType = brush.mType;
            mWidth = brush.mWidth;
            mColor = brush.mColor;
            mOpacity = brush.mOpacity;
            mPressureMinScale = brush.mPressureMinScale;
            mPressureMaxScale = brush.mPressureMaxScale;
            mPressureExponent = brush.mPressureExponent;
            mTiltUprightScale = brush.mTiltUprightScale;
            mTiltFlatScale = brush.mTiltFlatScale;
            mTiltUprightAlpha = brush.mTiltUprightAlpha;
            mTiltFlatAlpha = brush.mTiltFlatAlpha;
            mTiltFlatDegrees = brush.mTiltFlatDegrees;
            mVelocitySlowScale = brush.mVelocitySlowScale;
            mVelocityFastScale = brush.mVelocityFastScale;
            mVelocityReference = brush.mVelocityReference;
            mVelocityExponent = brush.mVelocityExponent;
            mSpacing = brush.mSpacing;
            mFlow = brush.mFlow;
            mGrain = brush.mGrain;
            mGrainDepth = brush.mGrainDepth;
            mGrainScale = brush.mGrainScale;
            mGrainSeed = brush.mGrainSeed;
        }

        @NonNull
        public Builder setType(@Type int type) {
            mType = type;
            return this;
        }

        /** The width at full pressure, in the drawing's units. */
        @NonNull
        public Builder setWidth(float width) {
            if (!(width > 0f)) {
                throw new IllegalArgumentException("A brush is wider than 0: " + width);
            }
            mWidth = width;
            return this;
        }

        @NonNull
        public Builder setColor(@ColorInt int color) {
            mColor = color;
            return this;
        }

        /** A further opacity, 0 to 1, on top of the colour's own alpha. */
        @NonNull
        public Builder setOpacity(@FloatRange(from = 0, to = 1) float opacity) {
            mOpacity = checkFraction(opacity, "opacity");
            return this;
        }

        /**
         * How the width follows pressure: the multiplier at the lightest touch and at full
         * pressure, and the curve between, where 1 is straight and above 1 keeps light touches
         * thin. Equal multipliers ignore pressure.
         */
        @NonNull
        public Builder setPressureResponse(float minScale, float maxScale, float exponent) {
            if (!(minScale >= 0f) || !(maxScale >= minScale) || !(exponent > 0f)) {
                throw new IllegalArgumentException("A pressure response runs from 0 or more up, "
                        + "with a positive exponent: " + minScale + ", " + maxScale + ", "
                        + exponent);
            }
            mPressureMinScale = minScale;
            mPressureMaxScale = maxScale;
            mPressureExponent = exponent;
            return this;
        }

        /**
         * How the width and opacity follow the pen's lean: their multipliers upright and leaned
         * right over, and the lean in degrees at which the latter are reached.
         */
        @NonNull
        public Builder setTiltResponse(float uprightScale, float flatScale, float uprightAlpha,
                float flatAlpha, float flatDegrees) {
            if (!(uprightScale > 0f) || !(flatScale > 0f) || !(flatDegrees > 0f)) {
                throw new IllegalArgumentException("Tilt scales and the flat angle are positive");
            }
            mTiltUprightScale = uprightScale;
            mTiltFlatScale = flatScale;
            mTiltUprightAlpha = checkFraction(uprightAlpha, "uprightAlpha");
            mTiltFlatAlpha = checkFraction(flatAlpha, "flatAlpha");
            mTiltFlatDegrees = flatDegrees;
            return this;
        }

        /**
         * How the width follows speed: its multiplier with the pen still and at the reference
         * speed, in drawing units a millisecond, and the curve between.
         */
        @NonNull
        public Builder setVelocityResponse(float slowScale, float fastScale,
                float referenceSpeed, float exponent) {
            if (!(slowScale > 0f) || !(fastScale > 0f) || !(referenceSpeed > 0f)
                    || !(exponent > 0f)) {
                throw new IllegalArgumentException("Speed scales, the reference and the exponent "
                        + "are positive");
            }
            mVelocitySlowScale = slowScale;
            mVelocityFastScale = fastScale;
            mVelocityReference = referenceSpeed;
            mVelocityExponent = exponent;
            return this;
        }

        /** The gap between dabs as a fraction of the width, or 0 for a continuous line. */
        @NonNull
        public Builder setSpacing(float spacing) {
            if (!(spacing >= 0f)) {
                throw new IllegalArgumentException("Spacing is 0 or more: " + spacing);
            }
            mSpacing = spacing;
            return this;
        }

        /** How much each dab lays down, 0 to 1. Overlapping dabs build up to the opacity. */
        @NonNull
        public Builder setFlow(@FloatRange(from = 0, to = 1) float flow) {
            mFlow = checkFraction(flow, "flow");
            return this;
        }

        /**
         * The texture dabs are laid through: what it is, how much it takes away (0 to 1), how
         * coarse it is in drawing units, and the seed its noise starts from.
         */
        @NonNull
        public Builder setGrain(@Grain int grain, float depth, float scale, int seed) {
            if (!(scale > 0f)) {
                throw new IllegalArgumentException("A grain's scale is positive: " + scale);
            }
            mGrain = grain;
            mGrainDepth = checkFraction(depth, "depth");
            mGrainScale = scale;
            mGrainSeed = seed;
            return this;
        }

        @NonNull
        public Brush build() {
            return new Brush(this);
        }

        private static float checkFraction(float value, @NonNull String name) {
            if (!(value >= 0f && value <= 1f)) {
                throw new IllegalArgumentException(name + " is 0 to 1: " + value);
            }
            return value;
        }
    }
}
