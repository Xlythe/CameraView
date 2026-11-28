package com.xlythe.view.camera.stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * How a stream measures whoever reads it.
 *
 * <p>Reads are the link's rate once the link is the bottleneck. Acknowledgements are better when
 * the app has them, and the time from reading a byte to hearing it arrived is the one signal
 * that sees a queue the pipe cannot.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class DeliveryMeterTest {
    /** A clock the test moves by hand. */
    private long mNow;

    /** What the meter reported. */
    private final List<long[]> mDelivered = new ArrayList<>();

    /** And the delays. */
    private final List<long[]> mDelays = new ArrayList<>();

    private final DeliveryMeter.Listener mListener = new DeliveryMeter.Listener() {
        @Override
        public void onDelivered(long nowMillis, long totalBytes) {
            mDelivered.add(new long[] {nowMillis, totalBytes});
        }

        @Override
        public void onDelay(long nowMillis, long delayMillis) {
            mDelays.add(new long[] {nowMillis, delayMillis});
        }
    };

    private DeliveryMeter meter() {
        DeliveryMeter meter = new DeliveryMeter(() -> mNow);
        meter.setListener(mListener);
        return meter;
    }

    @Test
    public void readsAreCountedAsDelivered() throws IOException {
        DeliveryMeter meter = meter();
        InputStream in = meter.wrap(new ByteArrayInputStream(new byte[100]));

        mNow = 10;
        assertEquals(0, in.read());
        mNow = 20;
        assertEquals(40, in.read(new byte[40], 0, 40));
        mNow = 30;
        assertEquals(9, in.skip(9));

        assertEquals(3, mDelivered.size());
        assertEquals(1, mDelivered.get(0)[1]);
        assertEquals(41, mDelivered.get(1)[1]);
        assertEquals(50, mDelivered.get(2)[1]);
        assertEquals(30, mDelivered.get(2)[0]);
    }

    /** The end of the stream is not a delivery. */
    @Test
    public void theEndIsNotCounted() throws IOException {
        DeliveryMeter meter = meter();
        InputStream in = meter.wrap(new ByteArrayInputStream(new byte[0]));

        assertEquals(-1, in.read());
        assertEquals(-1, in.read(new byte[4], 0, 4));
        assertEquals(0, in.skip(4));

        assertTrue(mDelivered.isEmpty());
    }

    /**
     * An acknowledgement is matched to the read that contained its last byte, and the time
     * between the two is the delay. From then on delivery is counted from acknowledgements.
     */
    @Test
    public void acknowledgementsMeasureTheDelay() throws IOException {
        DeliveryMeter meter = meter();
        InputStream in = meter.wrap(new ByteArrayInputStream(new byte[100]));
        mNow = 100;
        in.read(new byte[30], 0, 30);
        mNow = 200;
        in.read(new byte[30], 0, 30);
        mDelivered.clear();

        mNow = 350;
        meter.onAcknowledged(45);

        assertEquals(1, mDelays.size());
        assertEquals("byte 45 was read at 200", 150, mDelays.get(0)[1]);
        assertEquals(45, mDelivered.get(0)[1]);

        mNow = 400;
        in.read(new byte[10], 0, 10);
        assertEquals("reads no longer count once acknowledgements do", 1, mDelivered.size());

        mNow = 420;
        meter.onAcknowledged(70);
        assertEquals("byte 70 was read at 400", 20, mDelays.get(1)[1]);
    }

    /** An acknowledgement beyond anything remembered still counts as delivery. */
    @Test
    public void anAcknowledgementBeyondTheReadsHasNoDelay() {
        DeliveryMeter meter = meter();

        meter.onAcknowledged(1_000);

        assertTrue(mDelays.isEmpty());
        assertEquals(1_000, mDelivered.get(0)[1]);
    }

    /** With nobody listening, measuring costs nothing and breaks nothing. */
    @Test
    public void withoutAListenerNothingIsReported() throws IOException {
        DeliveryMeter meter = new DeliveryMeter(() -> mNow);
        InputStream in = meter.wrap(new ByteArrayInputStream(new byte[2_000 * 2]));
        for (int i = 0; i < 2_000; i++) {
            in.read(new byte[2], 0, 2);
        }
        meter.onAcknowledged(10);

        meter.setListener(mListener);
        meter.onAcknowledged(3_999);

        assertTrue(mDelivered.size() == 1 && mDelivered.get(0)[1] == 3_999);
        assertEquals("the read history is bounded, but the newest reads are kept",
                1, mDelays.size());
        new DeliveryMeter().setListener(null);
    }
}
