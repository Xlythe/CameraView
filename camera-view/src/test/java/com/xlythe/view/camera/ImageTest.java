package com.xlythe.view.camera;

import android.content.Context;
import android.net.Uri;
import android.widget.ImageView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;

import static org.junit.Assert.assertNotNull;

@RunWith(RobolectricTestRunner.class)
@Config(minSdk = 21)
public class ImageTest {

    private Context context;
    private ImageView imageView;

    @Before
    public void setup() {
        context = RuntimeEnvironment.application;
        imageView = new ImageView(context);
    }

    @Test
    public void testImageInitialization() {
        Image image = Image.with(context);
        assertNotNull(image);
    }

    @Test
    public void testLoadFile() {
        Image image = Image.with(context);
        File file = new File(context.getCacheDir(), "test.jpg");
        Image.Loader loader = image.load(file);
        assertNotNull(loader);

        loader.into(imageView);
        Image.clear(imageView);
    }

    @Test
    public void testLoadUri() {
        Image image = Image.with(context);
        Uri uri = Uri.fromFile(new File(context.getCacheDir(), "test.jpg"));
        Image.Loader loader = image.load(uri);
        assertNotNull(loader);

        loader.into(imageView);
        Image.clear(imageView);
    }
}
