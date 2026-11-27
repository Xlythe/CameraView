package com.xlythe.view.camera.shadow;

import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraExtensionCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.util.Range;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.annotation.Resetter;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The phone maker's photo modes, as a test lists them for each camera.
 *
 * <p>The real class binds to the phone maker's extension service and waits for it on a condition
 * variable timed by the system clock, which never moves under Robolectric, so the first question
 * a test asks never returns. Registered for every test in {@code robolectric.properties}: a camera
 * a test listed nothing for has no modes at all, which is what a phone without the service
 * says.</p>
 */
@Implements(value = CameraExtensionCharacteristics.class, minSdk = 31)
public class ShadowCameraExtensionCharacteristics {
    /** The preview size every listed mode offers. */
    public static final Size PREVIEW_SIZE = new Size(1920, 1080);
    /** The JPEG size every listed mode offers. */
    public static final Size JPEG_SIZE = new Size(4000, 2250);

    /** What each camera lists, by camera id. */
    private static final Map<String, Listing> sListings = new HashMap<>();

    /** How many times any camera's modes were asked for. */
    private static int sQueries;

    @RealObject private CameraExtensionCharacteristics realCharacteristics;

    /** What one camera says about its modes. */
    private static final class Listing {
        /** The platform's numbers for the modes it lists. */
        final List<Integer> mExtensions = new ArrayList<>();
        /** The request keys each mode reads. */
        final Set<CaptureRequest.Key> mRequestKeys = new HashSet<>();
        /** The zoom ratios each mode works between, or null where it lists none. */
        @Nullable Range<Float> mZoomRange;
        /** Whether its modes list a preview and a still size, which a usable mode does. */
        boolean mSized = true;
    }

    /**
     * Has a camera list these extensions, each with a preview and a JPEG size, reading the given
     * request keys and zooming over the given range, which may be null for none.
     */
    public static void setExtensions(@NonNull String cameraId, @NonNull int[] extensions,
            @NonNull Set<CaptureRequest.Key> requestKeys, @Nullable Range<Float> zoomRange) {
        Listing listing = new Listing();
        for (int extension : extensions) {
            listing.mExtensions.add(extension);
        }
        listing.mRequestKeys.addAll(requestKeys);
        listing.mZoomRange = zoomRange;
        sListings.put(cameraId, listing);
    }

    /** Has a camera list one extension with no sizes at all, as a broken listing would. */
    public static void setSizelessExtension(@NonNull String cameraId, int extension) {
        Listing listing = new Listing();
        listing.mExtensions.add(extension);
        listing.mSized = false;
        sListings.put(cameraId, listing);
    }

    /** How many times any camera's modes were asked for since the last reset. */
    public static int getQueries() {
        return sQueries;
    }

    @Resetter
    public static void reset() {
        sListings.clear();
        sQueries = 0;
    }

    @Implementation
    protected List<Integer> getSupportedExtensions() {
        sQueries++;
        Listing listing = listing();
        return listing == null ? Collections.<Integer>emptyList()
                : Collections.unmodifiableList(listing.mExtensions);
    }

    @Implementation
    protected <T> List<Size> getExtensionSupportedSizes(int extension, Class<T> klass) {
        Listing listing = listing();
        if (listing == null || !listing.mSized || klass != SurfaceTexture.class
                || !listing.mExtensions.contains(extension)) {
            return Collections.emptyList();
        }
        return Collections.singletonList(PREVIEW_SIZE);
    }

    @Implementation
    protected List<Size> getExtensionSupportedSizes(int extension, int format) {
        Listing listing = listing();
        if (listing == null || !listing.mSized || format != ImageFormat.JPEG
                || !listing.mExtensions.contains(extension)) {
            return Collections.emptyList();
        }
        return Collections.singletonList(JPEG_SIZE);
    }

    @Implementation(minSdk = 33)
    protected Set<CaptureRequest.Key> getAvailableCaptureRequestKeys(int extension) {
        Listing listing = listing();
        return listing == null ? Collections.<CaptureRequest.Key>emptySet()
                : Collections.unmodifiableSet(listing.mRequestKeys);
    }

    @Implementation(minSdk = 35)
    @SuppressWarnings("unchecked")
    protected <T> T get(int extension, CameraCharacteristics.Key<T> key) {
        Listing listing = listing();
        if (listing == null || !CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE.equals(key)) {
            return null;
        }
        return (T) listing.mZoomRange;
    }

    /** What the camera these characteristics describe lists, or null for nothing. */
    @Nullable
    private Listing listing() {
        String cameraId = ReflectionHelpers.getField(realCharacteristics, "mCameraId");
        return cameraId == null ? null : sListings.get(cameraId);
    }
}
