Camera View
====================

A View/Fragment that display the Android camera.


Where to Download
-----------------
```groovy
dependencies {
  compile 'com.xlythe:camera-view:1.0.12'
}
```

Permissions
-----------------
The following permissions are required in your AndroidManfiest.xml
```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />

<!-- Optional -->
<uses-permission android:name="android.permission.VIBRATE" />
```

CameraFragment
-----------------
Extend CameraFragment and override the required methods. Both pictures and videos are saved to a cache directory and may be overwritten or deleted. It's advised that you copy the files to persistent storage, depending on your usecase.
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

Your layout MUST contain @id/layout_camera [Any], @id/layout_permissions [Any], @id/camera [CameraView], id/capture [Any], and @id/request_permissions [Any].
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
Optionally, you may also include @id/duration [TextView], @id/progress [ProgressBar], @id/toggle [CompoundButton], id/confirm [Any], and @id/cancel [Any]

CameraView
-----------------
CameraView includes the optional attributes quality [high, medium, low], maxVideoDuration [milliseconds], and maxVideoSize [bits].
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
__For the most part, you'll be interacting directly with CameraFragment and can stop here.__

CameraView Lifecycle
-----------------
After obtaining permissions, call CameraView.open() in onStart() and CameraView.close() in onStop.
```java
@Override
public void onStart() {
    super.onStart();
    mCamera.open();
}

@Override
public void onStop() {
    mCamera.close();
    super.onStop();
}
```

CameraView Methods
-----------------
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
