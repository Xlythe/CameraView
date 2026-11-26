package com.xlythe.view.camera.v2;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.util.SizeF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.util.Size;
import android.view.TextureView;

import com.xlythe.view.camera.CameraView;

import org.robolectric.Shadows;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowCameraCharacteristics;
import org.robolectric.shadows.ShadowCamcorderProfile;
import org.robolectric.shadows.ShadowCameraManager;
import org.robolectric.shadows.StreamConfigurationMapBuilder;

/**
 * Registers fake cameras with Robolectric's camera2 shadows and hands back a CameraView the
 * Camera2 backend can actually drive.
 *
 * <p>Robolectric ships shadows for CameraManager, CameraDevice, CameraCaptureSession and
 * ImageReader, so the backend runs against the real framework classes rather than mocks. Two
 * things still need arranging: the characteristics a camera reports, which the shadows leave
 * empty, and a SurfaceTexture, which a TextureView never produces without a hardware layer.</p>
 */
public final class FakeCamera2 {
    static final String BACK_ID = "0";
    static final String FRONT_ID = "1";

    static final Size SIZE_1080P = new Size(1920, 1080);
    static final Size SIZE_720P = new Size(1280, 720);
    static final Size SIZE_480P = new Size(640, 480);

    /** The sensor's pixel array and pre-correction active array: the size a DNG is written at. */
    public static final Size SIZE_RAW = new Size(4000, 3000);

    /**
     * A RAW output larger than the sensor's arrays, as a camera with a remosaicing mode lists.
     * DngCreator refuses a frame this size, so taking the largest RAW output would be wrong.
     */
    static final Size SIZE_RAW_REMOSAIC = new Size(8000, 6000);

    /**
     * What a camera says about RAW.
     *
     * <p>Two separate claims, and a camera can make either without the other: the RAW
     * capability, which promises the metadata needed to interpret the sensor's readings, and
     * RAW_SENSOR outputs in the configuration map. Both are needed.</p>
     */
    public enum RawSupport {
        NONE, CAPABILITY_ONLY, FORMAT_ONLY, FULL;

        boolean hasCapability() {
            return this == CAPABILITY_ONLY || this == FULL;
        }

        boolean hasFormat() {
            return this == FORMAT_ONLY || this == FULL;
        }
    }

    /**
     * JPEG's internal HAL format.
     *
     * <p>StreamConfigurationMapBuilder registers sizes against internal formats, and
     * ImageFormat.JPEG (256) is not one of those: the map stores JPEG as BLOB and converts on the
     * way out, so getOutputSizes(ImageFormat.JPEG) finds what was added here.</p>
     */
    private static final int HAL_PIXEL_FORMAT_BLOB = 0x21;

    private FakeCamera2() {}

    /**
     * A CameraView whose SurfaceTexture is always available.
     *
     * <p>A real TextureView only creates one when it gets a hardware layer, which never happens
     * off-device, and the preview session refuses to initialize without it.</p>
     */
    public static class TestCameraView extends CameraView {
        private final SurfaceTexture mSurfaceTexture = new SurfaceTexture(0);

        public TestCameraView(Context context) {
            super(context);
        }

        @Override
        public SurfaceTexture getSurfaceTexture() {
            return mSurfaceTexture;
        }

        public void release() {
            mSurfaceTexture.release();
        }
    }

    /** Lays the view out, so the backend sees a non-zero size when choosing resolutions. */
    public static TestCameraView laidOutCameraView(Context context, int width, int height) {
        TestCameraView view = new TestCameraView(context);
        view.layout(0, 0, width, height);
        return view;
    }

    /** Registers a back and a front camera, both reporting the same capabilities. */
    public static void addBackAndFrontCameras(Context context) {
        addBackAndFrontCameras(context, true /* faceDetection */);
    }

    /** Registers a back and a front camera, optionally ones that cannot report faces. */
    public static void addBackAndFrontCameras(Context context, boolean faceDetection) {
        addBackAndFrontCameras(context, faceDetection,
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL);
    }

    /**
     * Registers a back and a front camera at a given hardware level.
     *
     * <p>The level decides which stream combinations the camera guarantees, so it is what the
     * library reads to decide whether a recording can carry a still alongside it. A null level is
     * a camera that does not report one at all.</p>
     */
    public static void addBackAndFrontCameras(
            Context context, boolean faceDetection, Integer hardwareLevel) {
        addBackAndFrontCameras(context, faceDetection, hardwareLevel, RawSupport.NONE);
    }

    /** Registers a back and a front camera that make this claim about RAW. */
    public static void addBackAndFrontCameras(Context context, RawSupport rawSupport) {
        addBackAndFrontCameras(context, true /* faceDetection */,
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL, rawSupport);
    }

    static void addBackAndFrontCameras(Context context, boolean faceDetection,
            Integer hardwareLevel, RawSupport rawSupport) {
        addCamera(context, BACK_ID, CameraMetadata.LENS_FACING_BACK, 90,
                CameraMetadata.INFO_DEVICE_TYPE_BUILT_IN, faceDetection, hardwareLevel,
                rawSupport);
        addCamera(context, FRONT_ID, CameraMetadata.LENS_FACING_FRONT, 270,
                CameraMetadata.INFO_DEVICE_TYPE_BUILT_IN, faceDetection, hardwareLevel,
                rawSupport);
        addCamcorderProfiles();
    }

    /**
     * Registers the recorder profiles the video path reads.
     *
     * <p>Without these, CamcorderProfile.get returns nothing and MediaRecorder setup fails
     * before the session is ever configured, so the whole recording path is unreachable. Camera2
     * guarantees QUALITY_LOW on every camera, and the rest describe a typical phone.</p>
     */
    static void addCamcorderProfiles() {
        for (String id : new String[] {BACK_ID, FRONT_ID}) {
            int cameraId = Integer.parseInt(id);
            addProfile(cameraId, CamcorderProfile.QUALITY_LOW, SIZE_480P, 1_000_000);
            addProfile(cameraId, CamcorderProfile.QUALITY_480P, SIZE_480P, 2_000_000);
            addProfile(cameraId, CamcorderProfile.QUALITY_720P, SIZE_720P, 8_000_000);
            addProfile(cameraId, CamcorderProfile.QUALITY_1080P, SIZE_1080P, 17_000_000);
        }
    }

    private static void addProfile(int cameraId, int quality, Size size, int bitRate) {
        ShadowCamcorderProfile.addProfile(cameraId, quality, ShadowCamcorderProfile.createProfile(
                /* duration= */ 30,
                quality,
                /* fileFormat= */ MediaRecorder.OutputFormat.MPEG_4,
                /* videoCodec= */ MediaRecorder.VideoEncoder.H264,
                bitRate,
                /* videoFrameRate= */ 30,
                size.getWidth(),
                size.getHeight(),
                /* audioCodec= */ MediaRecorder.AudioEncoder.AAC,
                /* audioBitRate= */ 96_000,
                /* audioSampleRate= */ 44_100,
                /* audioChannels= */ 1));
    }

    /**
     * Registers a camera that is not a built-in sensor, such as a USB webcam or a virtual camera.
     *
     * @param deviceType one of CameraMetadata.INFO_DEVICE_TYPE_*
     */
    static void addExternalCamera(Context context, String id, int lensFacing, int deviceType) {
        addCamera(context, id, lensFacing, 0, deviceType);
    }

    static void addCamera(Context context, String id, int lensFacing, int sensorOrientation) {
        addCamera(context, id, lensFacing, sensorOrientation,
                CameraMetadata.INFO_DEVICE_TYPE_BUILT_IN);
    }

    static void addCamera(Context context, String id, int lensFacing, int sensorOrientation,
            int deviceType) {
        addCamera(context, id, lensFacing, sensorOrientation, deviceType,
                true /* faceDetection */);
    }

    /**
     * Registers a camera, optionally one that cannot report faces.
     *
     * <p>Almost every real camera can, because it is already finding faces to focus and expose
     * for, so that is the default; an external or virtual camera is the usual exception.</p>
     */
    static void addCamera(Context context, String id, int lensFacing, int sensorOrientation,
            int deviceType, boolean faceDetection) {
        addCamera(context, id, lensFacing, sensorOrientation, deviceType, faceDetection,
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL);
    }

    static void addCamera(Context context, String id, int lensFacing, int sensorOrientation,
            int deviceType, boolean faceDetection, Integer hardwareLevel) {
        addCamera(context, id, lensFacing, sensorOrientation, deviceType, faceDetection,
                hardwareLevel, RawSupport.NONE);
    }

    static void addCamera(Context context, String id, int lensFacing, int sensorOrientation,
            int deviceType, boolean faceDetection, Integer hardwareLevel,
            RawSupport rawSupport) {
        ShadowCameraManager shadowManager =
                Shadow.extract(context.getSystemService(CameraManager.class));
        shadowManager.addCamera(id, characteristics(lensFacing, sensorOrientation, deviceType,
                faceDetection, hardwareLevel, rawSupport));
    }

    /** The characteristics {@link #addCamera} registers, for a test that needs them directly. */
    public static CameraCharacteristics characteristics(int lensFacing, int sensorOrientation,
            int deviceType, boolean faceDetection, Integer hardwareLevel,
            RawSupport rawSupport) {
        CameraCharacteristics characteristics = ShadowCameraCharacteristics.newCameraCharacteristics();
        ShadowCameraCharacteristics shadow = Shadow.extract(characteristics);

        shadow.set(CameraCharacteristics.LENS_FACING, lensFacing);
        shadow.set(CameraCharacteristics.SENSOR_ORIENTATION, sensorOrientation);
        shadow.set(CameraCharacteristics.FLASH_INFO_AVAILABLE, true);
        shadow.set(CameraCharacteristics.CONTROL_MAX_REGIONS_AF, 1);
        shadow.set(CameraCharacteristics.CONTROL_MAX_REGIONS_AE, 1);
        shadow.set(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM, 4f);
        shadow.set(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE, new Rect(0, 0, 4000, 3000));
        // The geometry a DNG is written against, which every real camera publishes.
        shadow.set(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE,
                new Rect(0, 0, SIZE_RAW.getWidth(), SIZE_RAW.getHeight()));
        shadow.set(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE, SIZE_RAW);
        shadow.set(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES, rawSupport.hasCapability()
                ? new int[] {CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE,
                        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW}
                : new int[] {CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE});
        // A plausible phone sensor and lens: 5.76mm x 4.29mm behind a 4.3mm lens, which is about
        // 67 degrees across. Published so that anything working out an angle of view has
        // something to work from - a camera that publishes neither can only decline.
        shadow.set(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE, new SizeF(5.76f, 4.29f));
        shadow.set(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
                new float[] {4.3f, 6.0f});
        if (faceDetection) {
            shadow.set(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES,
                    new int[] {CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF,
                            CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE,
                            CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL});
            shadow.set(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT, 5);
        } else {
            shadow.set(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES,
                    new int[] {CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF});
            shadow.set(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT, 0);
        }
        if (hardwareLevel != null) {
            shadow.set(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL, hardwareLevel);
        }
        shadow.set(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
                streamConfigurationMap(rawSupport));
        if (android.os.Build.VERSION.SDK_INT >= 37) {
            // Only exists from API 37, and a test configured for an older platform must not see
            // a characteristic that platform could not report.
            shadow.set(CameraCharacteristics.INFO_DEVICE_TYPE, deviceType);
        }
        return characteristics;
    }

    /**
     * Output sizes for every surface the sessions ask about: the preview reads the
     * implementation-defined sizes that back SurfaceTexture, MediaRecorder and MediaCodec, and
     * the picture surface reads the JPEG sizes.
     */
    private static android.hardware.camera2.params.StreamConfigurationMap streamConfigurationMap(
            RawSupport rawSupport) {
        StreamConfigurationMapBuilder builder = StreamConfigurationMapBuilder.newBuilder();
        for (Size size : new Size[] {SIZE_1080P, SIZE_720P, SIZE_480P}) {
            builder.addOutputSize(size);
            builder.addOutputSize(HAL_PIXEL_FORMAT_BLOB, size);
            builder.addOutputSize(ImageFormat.YUV_420_888, size);
        }
        if (rawSupport.hasFormat()) {
            // The remosaic size first and largest, so a choice of "the largest" or "the first"
            // would both pick the size DngCreator refuses.
            builder.addOutputSize(ImageFormat.RAW_SENSOR, SIZE_RAW_REMOSAIC);
            builder.addOutputSize(ImageFormat.RAW_SENSOR, SIZE_RAW);
        }
        return builder.build();
    }

    /** Runs everything the backend posted to the main looper. */
    static void idleMainLooper() {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }
}
