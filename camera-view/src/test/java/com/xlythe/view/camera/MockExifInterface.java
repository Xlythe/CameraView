package com.xlythe.view.camera;

import android.support.media.ExifInterface;

import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.HashMap;
import java.util.Map;

public class MockExifInterface {
    private Map<String, String> map = new HashMap<>();

    private final ExifInterface exifInterface;

    public MockExifInterface() {
        exifInterface = Mockito.mock(ExifInterface.class);
        Mockito.doAnswer(new Answer<String>() {
            @Override
            public String answer(InvocationOnMock invocation) throws Throwable {
                String key = (String) invocation.getArgument(0);
                return map.get(key);
            }
        }).when(exifInterface).getAttribute(Mockito.anyString());
        Mockito.doAnswer(new Answer<Integer>() {
            @Override
            public Integer answer(InvocationOnMock invocation) throws Throwable {
                String key = (String) invocation.getArgument(0);
                int defaultValue = (Integer) invocation.getArgument(1);
                if (map.containsKey(key)) {
                    return Integer.parseInt(map.get(key));
                }
                return defaultValue;
            }
        }).when(exifInterface).getAttributeInt(Mockito.anyString(), Mockito.anyInt());
        Mockito.doAnswer(new Answer<Double>() {
            @Override
            public Double answer(InvocationOnMock invocation) throws Throwable {
                String key = (String) invocation.getArgument(0);
                double defaultValue = (Double) invocation.getArgument(1);
                if (map.containsKey(key)) {
                    return Double.parseDouble(map.get(key));
                }
                return defaultValue;
            }
        }).when(exifInterface).getAttributeDouble(Mockito.anyString(), Mockito.anyDouble());
        Mockito.doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                String key = (String) invocation.getArgument(0);
                String value = (String) invocation.getArgument(1);
                map.put(key, value);
                return null;
            }
        }).when(exifInterface).setAttribute(Mockito.anyString(), Mockito.anyString());
        Mockito.doCallRealMethod().when(exifInterface).setLatLong(Mockito.anyDouble(), Mockito.anyDouble());
        Mockito.doCallRealMethod().when(exifInterface).getLatLong();
        Mockito.doCallRealMethod().when(exifInterface).getGpsDateTime();
        Mockito.doCallRealMethod().when(exifInterface).getDateTime();
    }

    public ExifInterface asMock() {
        return exifInterface;
    }
}
