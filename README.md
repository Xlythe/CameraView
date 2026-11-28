Camera View
====================

CameraView provides a simple wrapper around the Android camera APIs, with backwards compatibility
to API 21, or to API 14 with the core build described below. Rather than directly manipulating the
camera, here you have an Android View that can be placed within your app. This View displays a
preview and has the methods takePicture(), startRecording() and stopRecording(). For simple use
cases, CameraFragment exists as well.
If you inflate a CameraFragment with Views that have the correct ids (eg. @id/capture),
CameraFragment will bind to them to control the CameraView.


Where to Download
-----------------
```groovy
dependencies {
  implementation 'com.xlythe:camera-view:2.0.8'
}
```

If your minSdk is below 24, build with `-Pcameraview.core=true`, which leaves out the parts that
cannot reach that far back and covers API 14 and up. This is not hypothetical: `camera-camera2`
pulls in `camera-camera2-pipe`, which declares a minSdk of 24, and a dependency's floor becomes
your app's floor whatever this library declares. In that build, barcode scanning needs API 21 and
`com.google.mlkit:barcode-scanning` in your own dependencies, background replacement API 21 and
`com.google.mlkit:segmentation-selfie`, frame analysis and frame renderers API 21, streaming API 18
and recording API 16. Without them, `enterBarcodeScanner`, `enterBackgroundReplacement`,
`startFrameAnalysis` and `isFrameEffectSupported` return false and `startRecording` reports
`onFailure`.

Permissions
-----------------
The following permissions are required in your AndroidManfiest.xml
```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<!-- Below API 29, takePicture(), startRecording() and startPanorama() need this to file captures in the gallery -->
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" />

<!-- Optional camera permissions -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.VIBRATE" />
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO"/>
```

Camera
-----------------
Camera is a Composable that simplifies the Android Camera APIs.

Camera includes the optional attributes quality [max, high, medium, low],
flash [auto, on, off], lensFacing [back, front], maxVideoDuration [milliseconds],
maxVideoSize [bytes], pinchToZoom [boolean], imageConfirmationEnabled [boolean],
videoConfirmationEnabled [boolean] and matchPreviewAspectRatioEnabled [boolean].
```kotlin
val cameraController = remember { mutableStateOf<CameraController?>(null) }
Camera(
    modifier = Modifier,
    quality = Quality.HIGH,
    lensFacing = LensFacing.BACK,
    maxVideoDuration = 10000,
    maxVideoSize = 10000000,
    controller = cameraController
)
```

Camera also accepts zoomRatio [minZoomRatio to maxZoomRatio, or null to leave the zoom to
pinchToZoom], animateZoom [boolean], shutterSoundEnabled [boolean], shutterSound [raw resource],
pictureQuality [1 to 100], colorSpace [srgb, displayP3],
pictureFormat [jpeg, ultraHdr, raw, rawJpeg], exposureCompensation [minExposureCompensation to
maxExposureCompensation, in steps of exposureCompensationStep EV], torchEnabled [boolean],
dynamicRange [sdr, hdr], extension [none, bokeh, hdr, night, faceRetouch, auto],
stabilizationEnabled [boolean], lowLightBoostEnabled [boolean], frameRenderer [FrameRenderer, see
Filters and masks below], cameraTextureProvider [CameraTextureProvider] and hdrPreviewEnabled
[boolean]. Not every camera can do all of these, so CameraState below says which this one
supports.

While the camera frames HDR, with dynamicRange set to hdr or pictureFormat set to ultraHdr, the
preview shows in HDR too, on a display that can show it and with a camera that can stream it
(CameraState.isHdrPreviewSupported). The window is put into HDR colour mode meanwhile, and the
mode it had before comes back when the HDR preview ends, the camera closes or the Camera leaves
the composition. An HDR preview follows the scaling and translation of the modifiers around it,
and is clipped to its bounds, but not to a rounded shape, and takes no alpha, rotation or mirror;
set hdrPreviewEnabled to false for a preview that needs those, and it shows tone-mapped instead.

Pass a state to be told what the camera can do and what it is doing. It holds null until a camera
opens, and is republished when the camera reconfigures, so ask it before offering a control: a
request the active camera cannot honour is dropped rather than applied.
```kotlin
val cameraState = remember { mutableStateOf<CameraState?>(null) }
Camera(
    modifier = Modifier,
    controller = cameraController,
    state = cameraState,
    torchEnabled = torchOn
)

val camera = cameraState.value
if (camera != null && camera.hasFlash) {
    Button(onClick = { torchOn = !torchOn }) { Text("Torch") }
}
```

CameraState reports hasFlash, hasFrontFacingCamera, isUsingFrontFacingCamera, isZoomSupported,
minZoomRatio, maxZoomRatio, zoomPresets, isExposureCompensationSupported,
minExposureCompensation, maxExposureCompensation, exposureCompensationStep, supportedColorSpaces,
supportedPictureFormats, supportedDynamicRanges, supportedExtensions, isStabilizationSupported,
isLowLightBoostSupported, isSnapshotWhileRecordingSupported, isFaceDetectionSupported,
horizontalFieldOfView, verticalFieldOfView, isRecording, isAnalyzingFrames, isFrameEffectSupported,
isReplacingBackground and isCapturingPanorama.

Pass onZoomChanged to hear the zoom ratio whenever it changes, a pinch included, so that buttons
for zoomPresets can follow it. Writing it back into zoomRatio is safe, and animateZoom eases over
to each new ratio rather than jumping to it.
```kotlin
var zoom by remember { mutableStateOf(1f) }
Camera(
    controller = cameraController,
    state = cameraState,
    zoomRatio = zoom,
    animateZoom = true,
    onZoomChanged = { zoom = it }
)

cameraState.value?.zoomPresets?.forEach { preset ->
    Button(onClick = { zoom = preset.ratio }) { Text("${preset.label}x") }
}
```

Camera's methods are rather straight forward.

Takes a picture and files it in the gallery, under Pictures
```kotlin
cameraController.takePicture()
```
Starts recording until stopRecording is called, the max duration is reached, or the max file size
is reached, then files the video in the gallery, under Movies
```kotlin
cameraController.startRecording()
```
Only these calls without a file put anything in the gallery, and they report where it went.
Given a file, takePicture(file) and startRecording(file) write there instead and report the file.
A raw picture is filed as a DNG. A rawJpeg picture files both, under the same name, and
onImageSaved hears each: the JPEG, then the DNG.
```kotlin
Camera(
    controller = cameraController,
    onImageSaved = { uri -> ... },
    onVideoSaved = { uri -> ... },
    onImageCaptured = { file -> ... },
    onVideoCaptured = { file -> ... }
)
```
Stops recording
```kotlin
cameraController.stopRecording()
```
Returns a stream of bytes for both audio and video channels. These bytes can then be played with Video.
```kotlin
cameraController.stream()
```
Toggles between the various cameras on the device (typically the front and back cameras)
```kotlin
cameraController.toggleCamera()
```
Focuses and exposes for an area of the preview, in coordinates from -1000 to 1000
```kotlin
cameraController.focus(focusArea, meteringArea)
```
Hands every camera frame to an analyzer on a background thread, until stopFrameAnalysis
```kotlin
cameraController.startFrameAnalysis { frame -> ... }
cameraController.stopFrameAnalysis()
```
Reports the faces the camera is already tracking, or stops when given null. Returns false if this
camera cannot report faces, in which case the listener is never called.
```kotlin
cameraController.setFaceDetectionListener { faces, faceToView -> ... }
```
Sweeps a panorama or a photosphere, with the guide drawn over the preview, until everything is
covered or stopPanorama is called, and files it in the gallery. Given a file, it is written there
and reported to onCaptured instead. See Panoramas below.
```kotlin
cameraController.startPanorama(PanoramaMode.PHOTOSPHERE, onSaved = { uri -> ... })
cameraController.stopPanorama()   // or cancelPanorama()
```
Segments the subject out of each frame and draws it over a colour or an image, in place of
frameRenderer, until exitBackgroundReplacement. Returns false if this camera cannot.
```kotlin
cameraController.enterBackgroundReplacement(BackgroundReplacer().apply { setBackgroundImage(bitmap) })
cameraController.exitBackgroundReplacement()
```

CameraFragment
-----------------
CameraFragment allows for simple use cases of CameraView without requiring much more logic than a
layout xml file. Extend CameraFragment and override the required methods. Every picture and video
it takes is filed in the gallery, and you'll be notified where via onImageSaved(Uri) and
onVideoSaved(Uri).
```java
public class MainFragment extends CameraFragment {

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return View.inflate(getContext(), ..., container);
    }

    @Override
    public void onImageSaved(Uri uri) {
        ...
    }

    @Override
    public void onVideoSaved(Uri uri) {
        ...
    }
}
```

Your layout MUST contain @id/layout_camera [Any], @id/layout_permissions [Any],
@id/camera [CameraView], id/capture [Any], and @id/request_permissions [Any].
```xml
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:camera="http://schemas.android.com/apk/res-auto"
    android:orientation="vertical"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <FrameLayout
        android:id="@id/layout_permissions"
        android:layout_width="match_parent"
        android:layout_height="match_parent">

        <Button
            android:id="@id/request_permissions"
            android:text="Request Permissions"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="center" />

    </FrameLayout>

    <LinearLayout
        android:id="@id/layout_camera"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical">

        <com.xlythe.view.camera.CameraView
            android:id="@id/camera"
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1" />

        <Button
            android:id="@id/capture"
            android:text="Capture"
            android:layout_width="match_parent"
            android:layout_height="58dp" />

        <Button
            android:id="@id/confirm"
            android:text="Confirm"
            android:layout_width="match_parent"
            android:layout_height="58dp" />

    </LinearLayout>

</FrameLayout>
```
Optionally, you may also include @id/duration [TextView], @id/progress [ProgressBar],
@id/toggle [CompoundButton], id/confirm [Any], and @id/cancel [Any]

CameraView
-----------------
CameraView is a View that simplifies the Android Camera APIs. Like any other Android View, it can
be inflated within an xml layout resource and obtained in an Activity/Fragment via
findViewById(int). Because the Camera is a limited resource, and consumes a high amount of power,
CameraView must be opened/closed. Typically, it's recommended to call CameraView.open() in your
application's onStart() lifecycle, and CameraView.close() in it's onStop() event. If you're using
CameraFragment, then this will happen for free.


CameraView includes the optional attributes quality [max, high, medium, low],
flash [auto, on, off], lensFacing [back, front], maxVideoDuration [milliseconds],
maxVideoSize [bytes], confirmImages [boolean], confirmVideos [boolean],
pinchToZoomEnabled [boolean], matchPreviewAspectRatio [boolean], shutterSoundEnabled [boolean],
shutterSound [raw resource], dynamicRange [sdr, hdr], extension [none, bokeh, hdr, night, faceRetouch, auto],
stabilizationEnabled [boolean], exposureCompensation [integer], torchEnabled [boolean] and
lowLightBoostEnabled [boolean].
```xml
<com.xlythe.view.camera.CameraView
    xmlns:camera="http://schemas.android.com/apk/res-auto"
    android:id="@id/camera"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    camera:quality="high"
    camera:lensFacing="back"
    camera:maxVideoDuration="10000"
    camera:maxVideoSize="10000000" />
```

Each attribute has a matching setter. The last seven describe things not every camera can do, so
each also has a companion that reports whether the camera now open supports it, for example
isDynamicRangeSupported(DynamicRange) or isLowLightBoostSupported(). Ask before relying on one; an
unsupported request is logged and dropped rather than applied.

exposureCompensation is an index rather than a number of stops, from
getMinExposureCompensation() to getMaxExposureCompensation(). Each step is worth
getExposureCompensationStep() EV, so with a step of 1/6, index 6 brightens by 1 EV.

While the camera frames HDR, with dynamicRange set to HDR or the picture format set to
ULTRA_HDR, the preview shows in HDR too, where isHdrPreviewSupported() says the camera and the
display can. CameraView puts its window into HDR colour mode meanwhile, and the mode the window
had before comes back when the HDR preview ends, the camera closes or the view is removed from the
window. An HDR preview follows this view's scaling and translation and is clipped to its bounds,
but takes no alpha, rotation or mirror; for a preview that needs those,
setHdrPreviewEnabled(false) keeps it tone-mapped instead.

CameraView's methods are rather straight forward. Again, if you're using CameraFragment, it will
handle binding the Views to the appropriate methods on CameraView.

Takes a picture and files it in the gallery, under Pictures
```java
mCameraView.takePicture();
```
The platform's shutter click plays as the picture is exposed, and a tone as a recording starts and
stops. Turn them off with setShutterSoundEnabled(false); where the law requires them they play
anyway. setShutterSound(R.raw.click) plays a sound of your own in place of the click. OnImageCapturedListener.onShutter() fires at the same moment, for a shutter animation or a
sound of your own.
Starts recording until stopRecording is called, the max duration is reached, or the max file size
is reached, then files the video in the gallery, under Movies
```java
mCameraView.startRecording();
```
Only these overloads without a file put anything in the gallery, and the listeners hear where it
went in onImageSaved(Uri) and onVideoSaved(Uri). Given a file, takePicture(file) and
startRecording(file) write there instead, and report the file in onImageCaptured(File) and
onVideoCaptured(File). Every method but the confirmation and failure ones has a default, so
override the ones you use. A RAW picture is filed as a DNG. A RAW_JPEG picture files both, under
the same name, and onImageSaved(Uri) hears each: the JPEG, then the DNG.
```java
mCameraView.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
  @Override public void onImageConfirmation() {}
  @Override public void onImageSaved(Uri uri) { ... }
  @Override public void onFailure() {}
});
```
Stops recording
```java
mCameraView.stopRecording();
```
Returns a stream of bytes for both audio and video channels. These bytes can then be played with VideoView.
```java
mCameraView.stream();
```
Toggles between the various cameras on the device (typically the front and back cameras)
```java
mCameraView.toggleCamera();
```
Reports when the camera opens and closes. CameraFragment already implements this.
```java
mCameraView.setOnCameraStateChangedListener(listener);
```
With confirmation enabled, a capture is shown in CameraView's own preview and onImageConfirmation()
or onVideoConfirmation() fires before it is reported. Accept or discard it to continue; rejecting
also deletes every file it wrote, and files nothing in the gallery. Enable matchPreviewAspectRatio alongside
this so the capture and the preview are framed the same.
```java
mCameraView.setImageConfirmationEnabled(true);
mCameraView.confirmPicture(); // or rejectPicture()
mCameraView.confirmVideo();   // or rejectVideo()
```
Scans the preview for barcodes while the camera is open, reporting each batch of results to the
listener. Formats come from Barcode.Format, and the camera must already be open.
```java
mCameraView.enterBarcodeScanner(listener, Barcode.Format.QR_CODE);
mCameraView.exitBarcodeScanner();
```


Video snapshot
-----------------
takePicture works during a recording, where the camera can hold a preview, a recording and a still
at once - which is most of them, but not a LEGACY camera2 device. Ask before showing the button;
where it is false the picture is refused and the recording carries on.
```java
if (mCameraView.isSnapshotWhileRecordingSupported()) {
  mCameraView.takePicture(file);
}
```

Focus
-----------------
Tapping the preview focuses the camera there. CameraView draws nothing for it, because an app with
a camera UI of its own wants its own reticle; setFocusListener is what one is driven by. It reports
when the camera took a request, where it was aimed in the view's coordinates, and - exactly once
per request - whether the camera said it converged, so a reticle can confirm a lock rather than
assume one. A programmatic focus(Rect, Rect) is reported the same way, and a camera that cannot be
told where to look says so through isFocusSupported.
```java
mCameraView.setFocusListener(new CameraView.FocusListener() {
  @Override public void onFocusRequested(RectF area) {
    reticle.showAt(area);
  }

  @Override public void onFocusCompleted(RectF area, boolean locked) {
    reticle.settle(locked);
  }
});
```
See FocusReticleView in the sample app for one of these drawn.

Faces
-----------------
The camera finds faces on its own, to decide what to focus and expose for. setFaceDetectionListener
reports them, with a matrix that maps a face's bounds onto the view so you can draw over the
preview. It returns false on a camera that cannot report faces.
```java
mCameraView.setFaceDetectionListener((faces, faceToView) -> {
  for (Face face : faces) {
    RectF onScreen = new RectF(face.getBounds());
    faceToView.mapRect(onScreen);
  }
});
mCameraView.setFaceDetectionListener(null);
```

Zoom
-----------------
Zoom is a ratio in the camera's own terms, where 1 is the main lens. getMinZoomRatio is below 1 on
a phone with an ultra-wide lens, and setZoomRatio switches between the lenses by itself where the
phone allows it. getZoomPresets returns the stops worth a button each - one per lens, plus 2x where the
main sensor can crop to it without upscaling - and setOnZoomChangedListener keeps those buttons in
step with a pinch. Each preset has the exact ratio to zoom to and a rounded label to show, so a
2.74x lens is labelled 3.
```java
for (ZoomPreset preset : mCameraView.getZoomPresets()) {
  Button button = new Button(context);
  button.setText(preset.getLabel() + "x");
  button.setOnClickListener(v -> mCameraView.setZoomRatio(preset.getRatio(), true));
  zoomBar.addView(button);
}
mCameraView.setOnZoomChangedListener(ratio -> highlightNearest(ratio));
```
setZoomRatio(ratio, true) eases over to the ratio rather than jumping, passing through the lenses
on the way as a phone's camera app does, and the listener hears each step. A pinch takes over from
wherever it has got to.

setZoomLevel and getZoomLevel cover the same range as a position from 0 to getMaxZoomLevel, on a
log scale, for a slider.

Field of view
-----------------
getHorizontalFieldOfView and getVerticalFieldOfView report the angle, in degrees, that the camera
can currently see. That is what turns a direction in the world into a position on the preview, so
anything drawn over the camera to point at something needs it: a bearing half the angle of view
away from where the camera points sits at the edge of the preview, a quarter sits halfway out.

Both narrow as the camera zooms in, so read them again after changing the zoom, and both return 0
when the camera is closed or does not publish its optics. Pair them with
getRelativeCameraOrientation to know which way round the frame is.
```java
float fov = mCameraView.getHorizontalFieldOfView();
// bearing is degrees clockwise from where the camera points, negative to the left
float x = getWidth() * (0.5f + (float) Math.tan(Math.toRadians(bearing))
        / (2f * (float) Math.tan(Math.toRadians(fov / 2))));
```

Filters and masks
-----------------
setFrameRenderer draws every camera frame through your renderer on its way to the preview, the
recording and the pictures. isFrameEffectSupported says whether this camera can; where it cannot,
the renderer is kept but never called. The renderer is called on a GL thread of its own with the
frame already on a texture, so a filter is a shader.
```java
if (mCameraView.isFrameEffectSupported()) {
    mCameraView.setFrameRenderer(new ColorFilter(ColorMatrix.sepia()));
}
mCameraView.setFrameRenderer(null); // back to the camera's own frames
```
ColorMatrix has the usual ones (grayscale, sepia, invert, saturation, contrast, brightness and
tint), concat combines them, and ColorFilter takes any 4x4 matrix of your own. A renderer that
returns false from onDrawFrame passes the frame through, so one renderer can filter some frames
and leave others alone.

A renderer draws the picture the right way up as it is shown. Whatever it draws at the top is at
the top of the preview, the recording and the picture, and left stays left, from either camera
and however the phone is held; only the camera's own frame turns and mirrors to match the preview.
frame.getWidth and frame.getHeight are the size of that upright picture.

For a mask rather than a filter, enterBackgroundReplacement segments the subject out of each frame
and draws it over a colour or an image, in place of any frame renderer. It takes the frames an
analyzer would, so it returns false while startFrameAnalysis or the barcode scanner is running,
and they return false while it is.
```java
BackgroundReplacer replacer = new BackgroundReplacer();
replacer.setBackgroundImage(bitmap); // or setBackgroundColor(Color.BLUE)
mCameraView.enterBackgroundReplacement(replacer);
mCameraView.exitBackgroundReplacement();
```
To use a segmentation model of your own, set a BackgroundReplacer with setFrameRenderer and hand
it each SegmentationMask with setMask.

setCameraTextureProvider has the camera write its frames into a texture you create, for a library
whose API takes the name of a texture it made. Changing it briefly restarts the camera.

A picture taken while something is drawing is drawn as the preview shows it and written as a
JPEG. isPictureFormatSupported reports every other format unsupported until nothing is drawing:
Ultra HDR chosen beforehand falls back to JPEG, and a picture taken in RAW or RAW_JPEG chosen
beforehand fails.

Frames
-----------------
startFrameAnalysis hands every frame to your own analyzer, on a background thread, for detection
CameraView does not do itself. Only the newest frame is kept, so a slow analyzer drops frames
rather than falling behind, and the image is recycled when onFrame returns. Params.setMaxSize asks
for smaller frames than the preview, which most detectors are no worse at.
```java
mCameraView.startFrameAnalysis(frame -> {
  InputImage image = InputImage.fromMediaImage(frame.getImage(), frame.getRotationDegrees());
  // ... and frame.getFrameToViewTransform() maps a result onto the view
}, new FrameStream.Params().setMaxSize(new Size(640, 480)));
mCameraView.stopFrameAnalysis();
```

Panoramas
-----------------
startPanorama sweeps a panorama or a photosphere. CameraView draws the guide over its own
preview, with no words in it: for a panorama, a strip of what is taken so far, a line to keep
level on and arrows the way to turn; for a photosphere, dots on everything still to take, each
taken by holding the circle on it until it fills. A panorama finishes once it has gone all the way
round, and a photosphere once every dot is taken, straight up and down included. stopPanorama
saves what was covered so far, and cancelPanorama saves nothing. Once a sweep ends, its frames are
aligned by what they show and blended into one picture, which takes several seconds before the
listener hears of it.

The picture is a JPEG with the same Exif as any other picture and photo sphere metadata, so
galleries and panorama viewers show it as a panorama. Without a file it is filed in the gallery,
under Pictures, and reported to onPanoramaSaved(Uri); given a file it is written there and
reported to onPanoramaCaptured(File). startPanorama returns false on a device that cannot report
which way it is pointing. While a sweep runs, lock the screen's orientation, or turning the phone
will rotate it, and keep the screen on, or aiming straight up will lay the phone face down and
turn the screen off.
```java
mCameraView.startPanorama(CameraView.PanoramaMode.PHOTOSPHERE,
    new CameraView.PanoramaListener() {
      @Override public void onPanoramaProgress(float progress) {}
      @Override public void onPanoramaSaved(Uri uri) {}
      @Override public void onPanoramaFailed() {}
    });
mCameraView.stopPanorama();
```

Streaming
-----------------
stream() takes parameters when the defaults do not suit the link you are sending over. A stream
can only be joined at a key frame, so a short interval is what makes one playable soon after it
is opened.
```java
mCameraView.stream(new VideoStream.Params.Builder()
    .setAudioEnabled(false)
    .setMaxSize(new Size(640, 480))
    .setBitRate(1_000_000)
    .setIFrameInterval(1)
    .setIsLossy(true)
    .build());
```
A stream runs until you close it, and closing the camera does not close it. When the app goes to
the background the video stops, since another app may want the camera, but the audio keeps
running, so a call carries on.


Exif
-----------------
CameraView encodes metadata into pictures via Exif. By default, there's nothing more you need to do.
Exif metadata will be read by most Android image libraries (we recommend Glide), as well as most
computers (Windows, Mac, Linux). However, if needed, Exif gives you the option to be more privacy
sensitive (via Exif.removeLocation(), Exif.removeTimestamp()) as well as the information needed to
manually rotate/flip images if you cannot use another library.
```java
Exif exif = new Exif(file);
exif.removeLocation();
exif.setTitle("Harbour at dawn");
exif.setPhotographer("A. Photographer");
exif.attachAmbientConditions(new AmbientConditions.Builder().setTemperature(21.5).build());
exif.save();
```
Any other tag can be read and written by its standard name, or by its number for one newer than
the library.
```java
exif.setAttribute("ImageEditor", "A. Editor");
exif.setTag(0xA4F0, Exif.TYPE_ASCII, "A tag from a later standard");
```
If the file has no room for some of its tags, save() writes everything else and throws an
ExifNoRoomException naming them.

VideoView
-----------------
VideoView is another simplified Android View. In this case, as the name implies, it plays Videos,
from a file or from a uri such as the one onVideoSaved(Uri) reports.
```java
mVideoView.setFile(file); // or setUri(uri)
mVideoView.play();
mVideoView.pause();
```
```java
mVideoView.setStream(new VideoStream.Builder().withAudioStream(...).withVideoStream(...).build());
mVideoView.play();
mVideoView.pause();
```

An HDR video, one whose frames are HLG or PQ, plays in HDR on a display that can show it, and the
window is in HDR colour mode while it does. It then follows the view's scaling and translation
but not a mirror (setShouldMirror), alpha or rotation; a mirrored HDR video plays tone-mapped.

Viewing pictures
-----------------
Three views show the pictures the camera takes, from a file or from a uri such as the one
onImageSaved(Uri) reports. None of them ever decodes a picture whole: each holds a bounded amount
of memory however large the picture is.

LargeImageView shows any picture, a DNG included, and zooms in until its own pixels show: pinch or
double-tap to zoom, drag or fling to pan. PanoramaView shows a panorama as a strip filling the
view's height, and one that goes all the way round has no ends. PhotosphereView shows a photo
sphere from the inside, to drag, fling and pinch around; a partial sphere is shown where it was
taken and the rest left black.
```java
mLargeImageView.setUri(uri);     // or setFile(file)
mPanoramaView.setUri(uri);
mPhotosphereView.setUri(uri);
mPhotosphereView.setGyroscopeEnabled(true); // where isGyroscopeAvailable()
mPhotosphereView.setImageLoadListener(new ImageLoadListener() {
  @Override public void onImageLoaded() { ... }
  @Override public void onImageFailed() { ... }
});
```
```kotlin
LargeImage(uri = uri, onFailed = { ... })
Panorama(uri = uri)
Photosphere(uri = uri, gyroscope = true)
```
A DNG is shown on Android 7.0 and newer. The first time, it is decoded once and kept in the app's
cache as a JPEG, so showing it again is as quick as any other picture.

VideoEditor
-----------------
VideoEditor changes a video that has already been recorded - trimming it, cropping, scaling and
rotating the frames, and removing or replacing the audio - and writes the result to a second file,
leaving the recording alone. Describe the changes with VideoEdits, in which a crop is a fraction of
the recorded frame and a rotation is clockwise, as the camera reports it.
```java
VideoEditor editor = new VideoEditor(context, recording, clip, new VideoEdits.Builder()
    .setTimeRangeMs(1000, 4000)
    .setCrop(new RectF(0.1f, 0.1f, 0.9f, 0.9f))
    .setOutputHeight(720)
    .setRotationDegrees(90)
    .setAudioSource(music)
    .build());
editor.setOnVideoEditedListener(listener);
editor.start();
```
The listener is called on the main thread, with onVideoEdited(File) or onFailure(). An export can be
abandoned while it runs, and reports nothing when it is.
```java
editor.cancel();
```

License
-------

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
