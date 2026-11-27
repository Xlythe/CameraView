Camera View
====================

CameraView provides a simple wrapper around the Android camera APIs, with backwards compatibility
to API 21. Rather than directly manipulating the camera, here you have an Android View that can be
placed within your app. This View displays a preview and has the methods takePicture(File),
startRecording(File) and stopRecording(). For simple use cases, CameraFragment exists as well.
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
cannot reach that far back and covers API 21 and up. This is not hypothetical: `camera-camera2`
pulls in `camera-camera2-pipe`, which declares a minSdk of 24, and a dependency's floor becomes
your app's floor whatever this library declares.

Permissions
-----------------
The following permissions are required in your AndroidManfiest.xml
```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<!-- If you're not saving the file to the SD Card, you can set a max sdk version of 18 (Jellybean) -->
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="18" />

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

Camera also accepts zoomLevel [0 to maxZoomLevel, or null to leave the zoom to pinchToZoom],
pictureQuality [1 to 100], colorSpace [srgb, displayP3], pictureFormat [jpeg, ultraHdr],
exposureCompensation [integer], torchEnabled [boolean], dynamicRange [sdr, hdr],
extension [none, bokeh, hdr, night, faceRetouch, auto], stabilizationEnabled [boolean] and
lowLightBoostEnabled [boolean]. Not every camera can do all of these, so CameraState below says
which this one supports.

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
maxZoomLevel, isExposureCompensationSupported, minExposureCompensation, maxExposureCompensation,
supportedColorSpaces, supportedPictureFormats, supportedDynamicRanges, supportedExtensions,
isStabilizationSupported, isLowLightBoostSupported, isSnapshotWhileRecordingSupported,
isFaceDetectionSupported, horizontalFieldOfView, verticalFieldOfView, isRecording,
isAnalyzingFrames and isCapturingPanorama.

Camera's methods are rather straight forward.

Takes a picture and saves it to the given file
```kotlin
cameraController.takePicture(file)
```
Starts recording until stopRecording is called, the max duration is reached, or the max file size is reached
```kotlin
cameraController.startRecording(file)
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
Sweeps a panorama or a photosphere into a file, with the guide drawn over the preview, until
everything is covered or stopPanorama is called. See Panoramas below.
```kotlin
cameraController.startPanorama(file, PanoramaMode.PHOTOSPHERE, onCaptured = { file -> ... })
cameraController.stopPanorama()   // or cancelPanorama()
```

CameraFragment
-----------------
CameraFragment allows for simple use cases of CameraView without requiring much more logic than a
layout xml file. Extend CameraFragment and override the required methods. As pictures and videos are
saved, you'll be notified via onImageCaptured(File) and onVideoCaptured(File). Note that both are
saved to a cache directory and may eventually be overwritten or deleted if you don't move them.
It's advised that you copy the files to persistent storage, or back them up to a server, if your
usecase requires long term storage.
```java
public class MainFragment extends CameraFragment {

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return View.inflate(getContext(), ..., container);
    }

    @Override
    public void onImageCaptured(File file) {
        ...
    }

    @Override
    public void onVideoCaptured(File file) {
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
pinchToZoomEnabled [boolean], matchPreviewAspectRatio [boolean],
dynamicRange [sdr, hdr], extension [none, bokeh, hdr, night, faceRetouch, auto],
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

CameraView's methods are rather straight forward. Again, if you're using CameraFragment, it will
handle binding the Views to the appropriate methods on CameraView.

Takes a picture and saves it to the given file
```java
mCameraView.takePicture(file);
```
Starts recording until stopRecording is called, the max duration is reached, or the max file size is reached
```java
mCameraView.startRecording(file);
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
or onVideoConfirmation() fires instead of onImageCaptured(File) or onVideoCaptured(File). Accept or
discard it to continue; rejecting also deletes the file. Enable matchPreviewAspectRatio alongside
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
startPanorama sweeps a panorama or a photosphere into a file. CameraView draws the guide over its
own preview - the target to aim at next, how much is covered, and which way to turn - so the
person only has to turn on the spot. A panorama finishes once it has gone all the way round, and a
photosphere once the whole sphere is covered, straight up and down included. stopPanorama saves
what was covered so far, and cancelPanorama saves nothing.

The file is a JPEG carrying photo sphere metadata, so galleries and panorama viewers show it as a
panorama. startPanorama returns false on a device that cannot report which way it is pointing.
Lock the screen's orientation while a sweep runs, or turning the phone will rotate it.
```java
mCameraView.startPanorama(file, CameraView.PanoramaMode.PHOTOSPHERE,
    new CameraView.PanoramaListener() {
      @Override public void onPanoramaProgress(float progress) {}
      @Override public void onPanoramaCaptured(File file) {}
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
```

VideoView
-----------------
VideoView is another simplified Android View. In this case, as the name implies, it plays Videos.
```java
mVideoView.setFile(file);
mVideoView.play();
mVideoView.pause();
```
```java
mVideoView.setStream(new VideoStream.Builder().withAudioStream(...).withVideoStream(...).build());
mVideoView.play();
mVideoView.pause();
```

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
