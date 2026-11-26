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
pictureQuality [1 to 100] and colorSpace [srgb, displayP3].

The following attributes are accepted as well, but the Camera2 backend that ships enabled ignores
all of them. They are read by the CameraX backend, which is still opt-in:
exposureCompensation [integer], torchEnabled [boolean], dynamicRange [sdr, hdr],
extension [none, bokeh, hdr, night, faceRetouch, auto], previewStabilizationEnabled [boolean],
videoStabilizationEnabled [boolean], lowLightBoostEnabled [boolean] and
frontVideoMirroringEnabled [boolean].

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
supportedColorSpaces, supportedDynamicRanges, supportedExtensions,
isPreviewStabilizationSupported, isVideoStabilizationSupported, isLowLightBoostSupported,
isSnapshotWhileRecordingSupported, isFaceDetectionSupported, isFrameAnalysisSupported,
horizontalFieldOfView, verticalFieldOfView, isRecording and isAnalyzingFrames.

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
frontVideoMirroringEnabled [boolean], stabilizationEnabled [boolean],
exposureCompensation [integer], torchEnabled [boolean] and lowLightBoostEnabled [boolean].
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

Pointing at something
-----------------
WorldOverlay turns a place in the world into a position in the view, so an app can draw a marker
over the preview. It draws nothing itself - what the marker looks like is yours.

If all you know is a direction, which is all a compass and a location give, use the bearing with
the angle of view. Position goes as the tangent of the angle, not in proportion to it, so half the
angle of view away is the edge of the view and a quarter is not halfway out.
```java
float x = WorldOverlay.screenXForBearing(bearing, mCameraView.getHorizontalFieldOfView(), getWidth());
float y = WorldOverlay.screenYForElevation(elevation, mCameraView.getVerticalFieldOfView(), getHeight());
// NaN when there is no angle of view to work from, or the bearing is at a right angle
```

If you have a session that tracks the device through the world and can say where things are in
metres, project a point through its matrices instead. A point behind the camera has no position in
the view and is refused rather than drawn in front of you.
```java
PointF at = new PointF();
if (WorldOverlay.project(viewProjectionMatrix, x, y, z, getWidth(), getHeight(), at)) {
  // draw at `at`
}
```

For a marker that should sit in the room rather than on the glass, compare how far away it is
against how far away the scene is at that point, and hide it when something is in front. A tall
marker is handled by visiblePillar, which returns the stretch that is not hidden - so a pillar
whose base is behind the sofa still shows its top, which is the point of it being tall.
```java
float away = WorldOverlay.distanceFromCamera(viewMatrix, x, y, z);
if (!WorldOverlay.isOccluded(away, depthMetersAt(at.x, at.y), 0.1f)) {
  // draw at `at`
}
```

Picture format
-----------------
Pictures are JPEG everywhere. setPictureFormat asks for something else where the camera can write
it, and declines rather than silently writing a JPEG, so check first.
```java
if (mCameraView.isPictureFormatSupported(CameraView.PictureFormat.ULTRA_HDR)) {
  mCameraView.setPictureFormat(CameraView.PictureFormat.ULTRA_HDR);
}
```
ULTRA_HDR is a JPEG carrying a gain map, so a capable display shows the bright parts brighter while
anything else reads an ordinary photo. RAW is the sensor's own reading, much larger and needing an
editor rather than a gallery; RAW_JPEG writes both from one shutter press. All three need the
CameraX backend - the camera2 backend writes the JPEG the HAL produced and takes no second frame.

Streams and the background
-----------------
A stream is the caller's: it runs until you close it, and closing the camera does not close it.
That matters for an app that goes to the background. The camera cannot be held while another app
wants it, so the video stops arriving - keeping the picture alive needs picture-in-picture, which
is the app's business - but the audio half is an AudioRecord that owes the camera nothing and keeps
running, so a call carries on. Reopening the camera afterwards works, and a stream left open holds
none of the camera's buffers.

Captions
-----------------
Captions go beside a recording rather than inside it. The platform cannot put them inside:
MediaMuxer writes audio, video and (from API 26) an `application/`-prefixed metadata track, and has
no subtitle track at all, while MediaRecorder has no caption API. A sidecar file of the same name is
what every desktop player and most mobile ones pick up on their own, nothing is re-encoded, and the
recording is not touched.
```java
Captions captions = new Captions.Builder()
    .addCue(0, 2_500, "Hello.")
    .addCue(2_500, 4_000, "Is this thing on?")
    .build();
captions.writeSubRip(Captions.sidecarFor(videoFile, "srt"));
// or writeWebVtt(...) for a browser
```
Cues may overlap and need not be added in order. Captions are never drawn into the frames.

transcribe writes captions for a recording from what was said in it, as a subtitle file beside the
recording - something a viewer can turn off, which text burned into the frames would not be.
Nothing is re-encoded, so the recording is untouched. It needs Android 13, where the platform
recogniser first accepted a file rather than only the microphone, and a recogniser installed; ask
isTranscriptionSupported() first, or let it answer through the listener. Android 14 adds the time
each word was said, which isWordTimingSupported() reports - without it each cue is as precise as
the six-second window it was heard in. On-device recognition is used where the device has it.
```java
mCameraView.transcribe(recording, CaptionFormat.SUB_RIP, new TranscriptionListener() {
    public void onTranscriptionProgress(float progress) {}
    public void onTranscribed(Captions captions, File sidecar) {}
    public void onTranscriptionFailed(String reason) {}
});
```
A recording of a quiet room has nothing to transcribe and reports that through onTranscriptionFailed
rather than as a fault, which is the commonest reason it is called.

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
the recorded frame and a rotation is clockwise, as the camera reports it. Needs API 23, the floor
androidx.media3 is built against, and is left out of a -Pcameraview.core=true build.
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

Panorama
-----------------
startPanorama sweeps a panorama or a photosphere while the person turns the phone, and saves it as
a JPEG carrying GPano metadata - so Google Photos and every web viewer treat it as a panorama
rather than as a wide picture. Frames are placed by the rotation the phone reports, which is why
the sweep has to be a turn on the spot: walking moves the camera, and nothing close by will line
up. The listener reports how far along the sweep is and where to point next; drawing that is up to
you. Needs a backend that can deliver frames to an analyzer, and a device that reports its rotation
- both are refused by name rather than accepted and never honoured.
```java
mCameraView.startPanorama(CameraView.PanoramaMode.PHOTOSPHERE, new PanoramaSession.Listener() {
    public void onPanoramaProgress(float progress, float nextYaw, float nextPitch) {}
    public void onPanoramaCaptured(File file) {}
    public void onPanoramaFailed() {}
});
```
Finishes the sweep and writes it. Whatever was covered is saved, so this is both the finish and the
cancel, and the metadata records which part of the sphere the picture is.
```java
mCameraView.stopPanorama(file);
```
The sweep in progress, whose getBitmap() is the sphere as it fills in - which is the clearest
guidance you can show somebody.
```java
mCameraView.getPanoramaSession();
```

PhotosphereView
-----------------
PhotosphereView shows a panorama as the place it was taken, with a drag to look around and a pinch
to zoom. It reads the file's own GPano metadata, so a sweep that stopped partway shows as the part
it covered rather than being stretched over the sphere; a picture with no metadata is shown as a
full sphere. Decoding happens on the calling thread, so a large photosphere belongs off the main
one.
```java
photosphereView.setPanorama(file);
```
Or point it somewhere, in degrees - a yaw east of where the sweep started and a pitch above the
horizon.
```java
photosphereView.lookAt(90f, 0f);
```

AR
-----------------
Backend.AR is Camera2 with ARCore tracking the world through the same camera, for drawing something
onto the room rather than onto the glass. It needs com.google.ar:core on the classpath, API 24, and
a device Google has certified for AR with ARCore installed - so it is the one backend that can be
asked for and not got, and it falls back to Camera2 rather than failing. getBackend() reports which
you ended up with. A still or a recording is not available while it is tracking, because both need
a capture session a shared-camera session cannot be swapped for.

Your application manifest has to declare whether AR is needed, which ARCore insists on before it
will say anything about the device. "required" has the Play Store hide your app from every device
that cannot do AR, so "optional" is what an app with an AR mode wants.
```xml
<meta-data android:name="com.google.ar.core" android:value="optional" />
```
Then ask to be told where the camera is, every frame it is tracked. The listener is called on the
GL render thread.
```java
mCameraView.setArFrameListener(new ArFrameListener() {
    public void onArFrame(ArFrame frame) {}
    public void onArNotTracking(ArTrackingFailure reason) {}
});
```
ArFrame carries the view and view-projection matrices, how far away the room is across the view,
and a hit test that turns a tap into a point in the world. WorldOverlay turns those into positions
in the view, including which parts of a marker the room is standing in front of - so a pillar of
light placed across the room disappears behind the sofa in front of it.
```java
float[] where = frame.hitTest(event.getX(), event.getY());
WorldOverlay.VisibleRun visible = WorldOverlay.visiblePillar(
    frame.getViewMatrix(), frame.getViewProjectionMatrix(),
    where[0], where[1], where[2], 2f /* metres tall */,
    view.getWidth(), view.getHeight(), 24 /* samples */, 0.04f /* tolerance */,
    frame.getDepth());
```
Most of the reasons tracking stops are things only the person holding the phone can fix - it is too
dark, there is nothing to fix on, they are moving too fast - which is why they are reported rather
than swallowed.

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
