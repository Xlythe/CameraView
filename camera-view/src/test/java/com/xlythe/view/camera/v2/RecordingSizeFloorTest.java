package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;

import android.util.Size;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * How much resolution matching the preview's shape is allowed to cost. A real difference in shape
 * costs about a third at most and is honoured; a loss beyond half is not, and resolution wins.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class RecordingSizeFloorTest {
    private static final Comparator<Size> BY_AREA = (left, right) -> Long.signum(
            (long) left.getWidth() * left.getHeight()
                    - (long) right.getWidth() * right.getHeight());

    /** The case from the Samsung: a square match against a 4K offering. */
    @Test
    public void aSquareMatchDoesNotCostMostOfTheResolution() {
        List<Size> all = Arrays.asList(
                new Size(3840, 2160), new Size(1920, 1080), new Size(1088, 1088));
        List<Size> squareOnly = Collections.singletonList(new Size(1088, 1088));

        assertEquals("1088x1088 is a seventh of 3840x2160, which is not a shape difference",
                new Size(3840, 2160),
                SessionImpl.CameraSurface.chooseLargestMatching(squareOnly, all, BY_AREA));
    }

    /** A genuine 4:3 against 16:9 difference is still honoured, because it costs a third. */
    @Test
    public void anOrdinaryAspectDifferenceIsHonoured() {
        List<Size> all = Arrays.asList(
                new Size(3840, 2160), new Size(2560, 1920), new Size(1920, 1080));
        List<Size> fourByThree = Arrays.asList(new Size(2560, 1920));

        assertEquals("4.9MP against 8.3MP is the price of matching the preview, and worth it",
                new Size(2560, 1920),
                SessionImpl.CameraSurface.chooseLargestMatching(fourByThree, all, BY_AREA));
    }

    /** The largest of the matching sizes is the one taken, not the first. */
    @Test
    public void theLargestMatchingSizeWins() {
        List<Size> all = Arrays.asList(new Size(1920, 1080), new Size(1280, 720));
        List<Size> matching = Arrays.asList(
                new Size(1280, 720), new Size(1920, 1080), new Size(640, 360));

        assertEquals(new Size(1920, 1080),
                SessionImpl.CameraSurface.chooseLargestMatching(matching, all, BY_AREA));
    }

    /** Nothing of the preview's shape falls back to the best there is. */
    @Test
    public void noMatchFallsBackToTheBest() {
        List<Size> all = Arrays.asList(new Size(3840, 2160), new Size(1920, 1080));

        assertEquals(new Size(3840, 2160),
                SessionImpl.CameraSurface.chooseLargestMatching(
                        Collections.emptyList(), all, BY_AREA));
    }

    /** A match that is the best size is taken unchanged. */
    @Test
    public void amatchThatIsAlreadyTheBestIsKept() {
        List<Size> all = Arrays.asList(new Size(3840, 2160), new Size(1920, 1080));
        List<Size> matching = Collections.singletonList(new Size(3840, 2160));

        assertEquals(new Size(3840, 2160),
                SessionImpl.CameraSurface.chooseLargestMatching(matching, all, BY_AREA));
    }

    /** Exactly half is kept, so the boundary is not a surprise either side of itself. */
    @Test
    public void exactlyHalfTheResolutionIsStillAMatch() {
        List<Size> all = Collections.singletonList(new Size(2000, 1000));
        List<Size> half = Collections.singletonList(new Size(1000, 1000));

        assertEquals("1,000,000 against 2,000,000 is exactly the floor, so it stands",
                new Size(1000, 1000),
                SessionImpl.CameraSurface.chooseLargestMatching(half, all, BY_AREA));

        List<Size> underHalf = Collections.singletonList(new Size(999, 1000));
        assertEquals("a pixel under it does not",
                new Size(2000, 1000),
                SessionImpl.CameraSurface.chooseLargestMatching(underHalf, all, BY_AREA));
    }

    /** One size on offer is the answer whether it matches or not. */
    @Test
    public void oneSizeOnOfferIsTheAnswer() {
        List<Size> only = Collections.singletonList(new Size(640, 480));

        assertEquals(new Size(640, 480),
                SessionImpl.CameraSurface.chooseLargestMatching(only, only, BY_AREA));
        assertEquals(new Size(640, 480),
                SessionImpl.CameraSurface.chooseLargestMatching(
                        Collections.emptyList(), only, BY_AREA));
    }
}
