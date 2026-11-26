package com.xlythe.view.camera.stream;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Choosing the rate and the channel count a stream will actually be recorded at.
 *
 * <p>What the buffer settles on is what goes out on the wire and into the header, so an unnoticed
 * answer here is heard at the other end rather than seen. The old implementation took the first
 * rate it could size from an ascending list, which always meant 8kHz - telephone quality - on the
 * grounds that a shorter buffer streams sooner. These pin the order and the fallbacks so that
 * cannot drift back.</p>
 *
 * <p>The fake answers from static state rather than its own fields, which looks odd and is not:
 * {@link AudioBuffer}'s constructor calls {@code getMinBufferSize} to size itself, so a subclass
 * is asked the question before its own field initialisers have run and anything it stores is still
 * null. The library's two real subclasses are stateless and so never meet this.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class AudioBufferTest {
    /** Every rate the fake was asked about, in order, as "rate/channels". */
    private static final List<String> sAsked = new ArrayList<>();

    /** What the next constructed buffer claims to support. */
    private static int[] sSupportedRates;
    private static int sMaxChannels;

    @Before
    public void setUp() {
        sAsked.clear();
        sSupportedRates = new int[] {48000, 44100, 22050, 16000, 11025, 8000};
        sMaxChannels = 1;
    }

    private static class Fake extends AudioBuffer {
        Fake(int preferredRate, int channelCount) {
            super(preferredRate, channelCount);
        }

        Fake() {
            super();
        }

        @Override
        protected boolean validSize(int size) {
            return size > 0;
        }

        @Override
        protected int getMinBufferSize(int sampleRate, int channelCount) {
            sAsked.add(sampleRate + "/" + channelCount);
            if (channelCount > sMaxChannels) {
                return -1;
            }
            for (int rate : sSupportedRates) {
                if (rate == sampleRate) {
                    // Bytes scale with rate and channels, as a real buffer's do.
                    return sampleRate * channelCount / 100;
                }
            }
            return -1;
        }
    }

    private static Fake buffer(int preferredRate, int channelCount, int[] supported,
            int maxChannels) {
        sAsked.clear();
        sSupportedRates = supported;
        sMaxChannels = maxChannels;
        return new Fake(preferredRate, channelCount);
    }

    /** The rate asked for is the rate used, when the device can size it. */
    @Test
    public void thePreferredRateIsUsedWhenItCanBeSized() {
        Fake wideband = buffer(16000, 1, new int[] {48000, 16000, 8000}, 1);
        assertEquals(16000, wideband.getSampleRate());
        assertEquals(1, wideband.getChannelCount());

        Fake music = buffer(48000, 1, new int[] {48000, 16000, 8000}, 1);
        assertEquals("a caller asking for more should get it", 48000, music.getSampleRate());
    }

    /** And it is asked for first, rather than arrived at by walking a list. */
    @Test
    public void thePreferredRateIsTriedFirst() {
        buffer(22050, 1, new int[] {48000, 22050}, 1);

        assertTrue("expected the first question to be the rate asked for, got " + sAsked,
                sAsked.get(0).startsWith("22050/"));
    }

    /**
     * A rate the device cannot size falls back to the highest it can.
     *
     * <p>Highest rather than lowest: a higher rate costs bandwidth, not delay, because the minimum
     * buffer scales with the rate and so holds roughly the same number of milliseconds either way.
     * </p>
     */
    @Test
    public void anUnsupportedRateFallsBackToTheHighestAvailable() {
        Fake buffer = buffer(32000 /* not offered */, 1, new int[] {44100, 22050, 8000}, 1);

        assertEquals(44100, buffer.getSampleRate());
    }

    /** The default is wideband speech, not the telephone rate it replaced. */
    @Test
    public void theDefaultIsWideband() {
        assertEquals(16000, AudioBuffer.DEFAULT_SAMPLE_RATE);
        assertEquals(1, AudioBuffer.DEFAULT_CHANNEL_COUNT);

        sAsked.clear();
        sSupportedRates = new int[] {16000};
        sMaxChannels = 1;
        Fake plain = new Fake();

        assertEquals(16000, plain.getSampleRate());
        assertEquals(160, plain.getSize());
        assertNotNull(plain.data());
        assertEquals(160, plain.data().length);
    }

    /** Stereo is given when the device can do it. */
    @Test
    public void stereoIsHonouredWhenAvailable() {
        Fake buffer = buffer(44100, 2, new int[] {44100}, 2);

        assertEquals(2, buffer.getChannelCount());
        assertEquals(44100, buffer.getSampleRate());
    }

    /**
     * And the second channel is given up before the rate is.
     *
     * <p>A caller asking for 44.1kHz stereo on a one-microphone device is better served by 44.1kHz
     * mono than by stereo at some rate it never asked for.</p>
     */
    @Test
    public void stereoIsGivenUpBeforeTheRateIs() {
        Fake buffer = buffer(44100, 2, new int[] {44100, 8000}, 1 /* mono only */);

        assertEquals("the rate asked for survives", 44100, buffer.getSampleRate());
        assertEquals("the channel does not", 1, buffer.getChannelCount());
    }

    /** More than two channels is not a thing a stream carries. */
    @Test
    public void moreThanTwoChannelsIsClampedToTwo() {
        Fake buffer = buffer(44100, 6, new int[] {44100}, 2);

        assertEquals(2, buffer.getChannelCount());
    }

    /** When nothing can be sized at all, there is still a buffer to write into. */
    @Test
    public void nothingSupportedStillLeavesAUsableBuffer() {
        Fake buffer = buffer(16000, 1, new int[] {} /* nothing works */, 1);

        assertEquals(1024, buffer.getSize());
        assertEquals(1024, buffer.data().length);
        assertEquals("the last rate tried is the lowest one", 8000, buffer.getSampleRate());
    }
}
