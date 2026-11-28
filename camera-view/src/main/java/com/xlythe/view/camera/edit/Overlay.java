package com.xlythe.view.camera.edit;

import android.graphics.Color;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import androidx.annotation.AnyThread;
import androidx.annotation.ColorInt;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.UUID;

/**
 * One item over a picture: a sticker, which may be animated, or a line of text, placed by its
 * middle, scaled, turned and flipped. Positions and sizes are in the units of the picture it is
 * over, such as its pixels. Overlays are immutable; {@link Builder} makes a changed copy.
 *
 * <p>An item in a video can show for part of it: see {@link #getStartMillis()}. Items are
 * {@link Parcelable}, so an app can keep them across a configuration change, as
 * {@link OverlayView} keeps its own.</p>
 */
public final class Overlay implements Parcelable {
    /** What an item is. */
    @IntDef({KIND_IMAGE, KIND_TEXT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Kind {}

    /** A picture, still or animated: PNG, JPEG, WebP, GIF or animated PNG. */
    public static final int KIND_IMAGE = 0;
    /** Text. */
    public static final int KIND_TEXT = 1;

    /** What is drawn behind text. */
    @IntDef({BACKGROUND_NONE, BACKGROUND_SOLID, BACKGROUND_OUTLINE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Background {}

    /** Nothing. */
    public static final int BACKGROUND_NONE = 0;
    /** A rounded block of the background colour. */
    public static final int BACKGROUND_SOLID = 1;
    /** An outline of the background colour round each letter. */
    public static final int BACKGROUND_OUTLINE = 2;

    /** The time an item shows until when it shows to the end. */
    public static final long UNSET_TIME = -1;

    /** What the item is called across edits. */
    @NonNull private final String mId;
    @Kind private final int mKind;
    /** The picture, for an image. */
    @Nullable private final Uri mUri;
    /** The picture's MIME type, or null to tell it from its bytes. */
    @Nullable private final String mMimeType;
    /** The text, for text. */
    @Nullable private final CharSequence mText;
    @ColorInt private final int mTextColor;
    @Background private final int mBackground;
    @ColorInt private final int mBackgroundColor;
    /** Where the item's middle is, in the picture's units. */
    private final float mCenterX;
    private final float mCenterY;
    /** How wide the item is at a scale of 1, in the picture's units. */
    private final float mWidth;
    /** How tall the item is at a scale of 1. */
    private final float mHeight;
    private final float mScale;
    /** How far the item is turned clockwise, in degrees. */
    private final float mRotationDegrees;
    private final boolean mFlippedHorizontally;
    private final boolean mFlippedVertically;
    /** When the item starts showing in a video, in milliseconds, or 0. */
    private final long mStartMillis;
    /** When it stops, or {@link #UNSET_TIME}. */
    private final long mEndMillis;

    private Overlay(@NonNull Builder builder) {
        mId = builder.mId;
        mKind = builder.mKind;
        mUri = builder.mUri;
        mMimeType = builder.mMimeType;
        mText = builder.mText;
        mTextColor = builder.mTextColor;
        mBackground = builder.mBackground;
        mBackgroundColor = builder.mBackgroundColor;
        mCenterX = builder.mCenterX;
        mCenterY = builder.mCenterY;
        mWidth = builder.mWidth;
        mHeight = builder.mHeight;
        mScale = builder.mScale;
        mRotationDegrees = builder.mRotationDegrees;
        mFlippedHorizontally = builder.mFlippedHorizontally;
        mFlippedVertically = builder.mFlippedVertically;
        mStartMillis = builder.mStartMillis;
        mEndMillis = builder.mEndMillis;
    }

    @AnyThread
    @NonNull
    public String getId() {
        return mId;
    }
    @AnyThread
    @Kind
    public int getKind() {
        return mKind;
    }

    /** The picture, for an image, or null. */
    @AnyThread
    @Nullable
    public Uri getUri() {
        return mUri;
    }

    /** The picture's MIME type, or null where it is told from its bytes. */
    @AnyThread
    @Nullable
    public String getMimeType() {
        return mMimeType;
    }

    /** The text, for text, or null. */
    @AnyThread
    @Nullable
    public CharSequence getText() {
        return mText;
    }
    @AnyThread
    @ColorInt
    public int getTextColor() {
        return mTextColor;
    }
    @AnyThread
    @Background
    public int getBackground() {
        return mBackground;
    }
    @AnyThread
    @ColorInt
    public int getBackgroundColor() {
        return mBackgroundColor;
    }

    /** Where the item's middle is across, in the picture's units. */
    @AnyThread
    public float getCenterX() {
        return mCenterX;
    }

    /** Where the item's middle is down, in the picture's units. */
    @AnyThread
    public float getCenterY() {
        return mCenterY;
    }

    /** How wide the item is at a scale of 1, in the picture's units. */
    @AnyThread
    public float getWidth() {
        return mWidth;
    }

    /** How tall the item is at a scale of 1, in the picture's units. */
    @AnyThread
    public float getHeight() {
        return mHeight;
    }
    @AnyThread
    public float getScale() {
        return mScale;
    }

    /** How far the item is turned clockwise, in degrees. */
    @AnyThread
    public float getRotationDegrees() {
        return mRotationDegrees;
    }

    /** Whether the item is mirrored left to right. */
    @AnyThread
    public boolean isFlippedHorizontally() {
        return mFlippedHorizontally;
    }

    /** Whether the item is mirrored top to bottom. */
    @AnyThread
    public boolean isFlippedVertically() {
        return mFlippedVertically;
    }

    /** When the item starts showing in a video, in milliseconds from its start. */
    @AnyThread
    public long getStartMillis() {
        return mStartMillis;
    }

    /** When the item stops showing in a video, or {@link #UNSET_TIME} to show to the end. */
    @AnyThread
    public long getEndMillis() {
        return mEndMillis;
    }

    /** Whether the item shows at a moment of a video. */
    @AnyThread
    public boolean isShownAt(long timeMillis) {
        return timeMillis >= mStartMillis && (mEndMillis == UNSET_TIME || timeMillis < mEndMillis);
    }

    /**
     * Maps the item's own units, with (0, 0) at its top left and its width and height at its
     * bottom right, to the picture's.
     */
    @AnyThread
    @NonNull
    Matrix matrix() {
        Matrix matrix = new Matrix();
        matrix.setTranslate(-mWidth / 2f, -mHeight / 2f);
        matrix.postScale(mFlippedHorizontally ? -mScale : mScale,
                mFlippedVertically ? -mScale : mScale);
        matrix.postRotate(mRotationDegrees);
        matrix.postTranslate(mCenterX, mCenterY);
        return matrix;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (!(other instanceof Overlay)) {
            return false;
        }
        Overlay that = (Overlay) other;
        return mId.equals(that.mId) && mKind == that.mKind
                && (mUri == null ? that.mUri == null : mUri.equals(that.mUri))
                && (mMimeType == null ? that.mMimeType == null : mMimeType.equals(that.mMimeType))
                && (mText == null ? that.mText == null
                        : that.mText != null && mText.toString().equals(that.mText.toString()))
                && mTextColor == that.mTextColor && mBackground == that.mBackground
                && mBackgroundColor == that.mBackgroundColor
                && Arrays.equals(numbers(), that.numbers())
                && mFlippedHorizontally == that.mFlippedHorizontally
                && mFlippedVertically == that.mFlippedVertically
                && mStartMillis == that.mStartMillis && mEndMillis == that.mEndMillis;
    }

    private float[] numbers() {
        return new float[] {mCenterX, mCenterY, mWidth, mHeight, mScale, mRotationDegrees};
    }

    @Override
    public int hashCode() {
        return 31 * mId.hashCode() + Arrays.hashCode(numbers());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeString(mId);
        out.writeInt(mKind);
        Uri.writeToParcel(out, mUri);
        out.writeString(mMimeType);
        out.writeInt(mText == null ? 0 : 1);
        if (mText != null) {
            TextUtils.writeToParcel(mText, out, flags);
        }
        out.writeInt(mTextColor);
        out.writeInt(mBackground);
        out.writeInt(mBackgroundColor);
        out.writeFloatArray(numbers());
        out.writeInt((mFlippedHorizontally ? 1 : 0) | (mFlippedVertically ? 2 : 0));
        out.writeLong(mStartMillis);
        out.writeLong(mEndMillis);
    }

    public static final Creator<Overlay> CREATOR = new Creator<Overlay>() {
        @Override
        public Overlay createFromParcel(@NonNull Parcel in) {
            Builder builder = new Builder();
            String id = in.readString();
            builder.mId = id == null ? builder.mId : id;
            builder.mKind = in.readInt();
            builder.mUri = Uri.CREATOR.createFromParcel(in);
            builder.mMimeType = in.readString();
            builder.mText = in.readInt() == 0 ? null
                    : TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
            builder.mTextColor = in.readInt();
            builder.mBackground = in.readInt();
            builder.mBackgroundColor = in.readInt();
            float[] numbers = in.createFloatArray();
            if (numbers != null && numbers.length == 6) {
                builder.mCenterX = numbers[0];
                builder.mCenterY = numbers[1];
                builder.mWidth = numbers[2];
                builder.mHeight = numbers[3];
                builder.mScale = numbers[4];
                builder.mRotationDegrees = numbers[5];
            }
            int flips = in.readInt();
            builder.mFlippedHorizontally = (flips & 1) != 0;
            builder.mFlippedVertically = (flips & 2) != 0;
            builder.mStartMillis = in.readLong();
            builder.mEndMillis = in.readLong();
            return builder.build();
        }

        @Override
        public Overlay[] newArray(int size) {
            return new Overlay[size];
        }
    };

    @NonNull
    @Override
    public String toString() {
        return "Overlay{id=" + mId + ", kind=" + mKind + ", at=" + mCenterX + "," + mCenterY
                + ", scale=" + mScale + ", rotation=" + mRotationDegrees + "}";
    }

    /** Builds an item. */
    public static final class Builder {
        @NonNull private String mId = UUID.randomUUID().toString();
        @Kind private int mKind;
        @Nullable private Uri mUri;
        @Nullable private String mMimeType;
        @Nullable private CharSequence mText;
        @ColorInt private int mTextColor = Color.WHITE;
        @Background private int mBackground = BACKGROUND_NONE;
        @ColorInt private int mBackgroundColor = Color.BLACK;
        private float mCenterX;
        private float mCenterY;
        private float mWidth = 1;
        private float mHeight = 1;
        private float mScale = 1;
        private float mRotationDegrees;
        private boolean mFlippedHorizontally;
        private boolean mFlippedVertically;
        private long mStartMillis;
        private long mEndMillis = UNSET_TIME;

        /** An image item of a picture, its MIME type told from its bytes if null. */
        public static Builder image(@NonNull Uri uri, @Nullable String mimeType) {
            Builder builder = new Builder();
            builder.mKind = KIND_IMAGE;
            builder.mUri = uri;
            builder.mMimeType = mimeType;
            return builder;
        }

        /** A text item. */
        public static Builder text(@NonNull CharSequence text) {
            Builder builder = new Builder();
            builder.mKind = KIND_TEXT;
            builder.mText = text;
            return builder;
        }

        private Builder() {}

        /** Starts from another item, keeping its name. */
        public Builder(@NonNull Overlay overlay) {
            mId = overlay.mId;
            mKind = overlay.mKind;
            mUri = overlay.mUri;
            mMimeType = overlay.mMimeType;
            mText = overlay.mText;
            mTextColor = overlay.mTextColor;
            mBackground = overlay.mBackground;
            mBackgroundColor = overlay.mBackgroundColor;
            mCenterX = overlay.mCenterX;
            mCenterY = overlay.mCenterY;
            mWidth = overlay.mWidth;
            mHeight = overlay.mHeight;
            mScale = overlay.mScale;
            mRotationDegrees = overlay.mRotationDegrees;
            mFlippedHorizontally = overlay.mFlippedHorizontally;
            mFlippedVertically = overlay.mFlippedVertically;
            mStartMillis = overlay.mStartMillis;
            mEndMillis = overlay.mEndMillis;
        }

        @NonNull
        public Builder setText(@NonNull CharSequence text) {
            mText = text;
            return this;
        }
        @NonNull
        public Builder setTextColor(@ColorInt int color) {
            mTextColor = color;
            return this;
        }

        /** What is drawn behind text, in a colour. */
        @NonNull
        public Builder setBackground(@Background int background, @ColorInt int color) {
            mBackground = background;
            mBackgroundColor = color;
            return this;
        }

        /** Where the item's middle is, in the picture's units. */
        @NonNull
        public Builder setCenter(float x, float y) {
            mCenterX = x;
            mCenterY = y;
            return this;
        }

        /** How large the item is at a scale of 1, in the picture's units. */
        @NonNull
        public Builder setSize(float width, float height) {
            if (!(width > 0) || !(height > 0)) {
                throw new IllegalArgumentException("An item has a size: " + width + "x" + height);
            }
            mWidth = width;
            mHeight = height;
            return this;
        }

        @NonNull
        public Builder setScale(float scale) {
            if (!(scale > 0)) {
                throw new IllegalArgumentException("A scale is positive: " + scale);
            }
            mScale = scale;
            return this;
        }

        /** How far the item is turned clockwise, in degrees. */
        @NonNull
        public Builder setRotationDegrees(float degrees) {
            float turned = degrees % 360f;
            mRotationDegrees = turned < 0 ? turned + 360f : turned;
            return this;
        }

        @NonNull
        public Builder setFlippedHorizontally(boolean flipped) {
            mFlippedHorizontally = flipped;
            return this;
        }

        @NonNull
        public Builder setFlippedVertically(boolean flipped) {
            mFlippedVertically = flipped;
            return this;
        }

        /**
         * When the item shows in a video, in milliseconds from its start, until
         * {@link #UNSET_TIME} to show to the end.
         */
        @NonNull
        public Builder setTimeRangeMillis(long startMillis, long endMillis) {
            if (startMillis < 0 || (endMillis != UNSET_TIME && endMillis <= startMillis)) {
                throw new IllegalArgumentException("An item shows from 0 or later until after it "
                        + "starts: " + startMillis + " to " + endMillis);
            }
            mStartMillis = startMillis;
            mEndMillis = endMillis;
            return this;
        }

        @NonNull
        public Overlay build() {
            return new Overlay(this);
        }
    }
}
