Camera View
====================

CameraView provides a simple wrapper around the Android camera APIs, with backwards compatibility
to API 14. Rather than directly manipulating the camera, here you have an Android View that can be
placed within your app. This View displays a preview and has the methods takePicture(File),
startRecording(File) and stopRecording(File). For simple use cases, CameraFragment exists as well.
If you inflate a CameraFragment with Views that have the correct ids (eg. @id/capture),
CameraFragment will bind to them to control the CameraView.


Where to Download
-----------------
```groovy
dependencies {
  compile 'com.xlythe:camera-view:1.1.2'
}
```

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
maxVideoDuration [milliseconds], and maxVideoSize [bytes].
```xml
<com.xlythe.view.camera.CameraView
    xmlns:camera="http://schemas.android.com/apk/res-auto"
    android:id="@id/camera"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    camera:quality="high"
    camera:maxVideoDuration="10000"
    camera:maxVideoSize="10000000" />
```

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
Toggles between the various cameras on the device (typically the front and back cameras)
```java
mCameraView.toggleCamera();
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
