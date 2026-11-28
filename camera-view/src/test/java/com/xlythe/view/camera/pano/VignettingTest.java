package com.xlythe.view.camera.pano;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Fitting a lens's falloff towards its corners from where frames overlap, and taking it out, on
 * frames of a scene whose every direction is known.
 */
public class VignettingTest {
    /** Two rows of frames, so a patch is seen near the middle of one and the edge of another. */
    private static StitchScene scene() {
        StitchScene scene = new StitchScene();
        for (int i = 0; i < 4; i++) {
            scene.add(i * 22f, 0, 0, 0, 0);
            scene.add(11 + i * 22f, 25, 0, 0, 0);
        }
        return scene;
    }

    @Test
    public void aLensThatDarkensItsCornersByAThird_isFoundAndUndone() {
        StitchScene scene = scene();
        // exp(-0.4 r^2): the corners at two thirds of the middle.
        double[] lens = {-0.4, 0};
        for (int i = 0; i < scene.images.size(); i++) {
            darkenCorners(scene.images.get(i), scene.frames.get(i), lens);
        }
        Equirect small = new Equirect(720, 30);
        List<FrameWarp> warps = warps(scene, small);
        double before = disagreement(warps, small);

        double[] fitted = Vignetting.fit(warps, frames(scene), small);
        double corner = Math.exp(Vignetting.logFalloff(fitted, 1));
        assertEquals("the corners at " + corner, Math.exp(-0.4), corner, 0.03);
        // Halfway out too, so it is the shape and not just the corner.
        assertEquals(Math.exp(-0.1), Math.exp(Vignetting.logFalloff(fitted, 0.25)), 0.02);

        Vignetting.correct(warps, frames(scene), small, fitted);
        double after = disagreement(warps, small);
        assertTrue("overlaps disagree by " + before + " then " + after, after < before / 3);
    }

    @Test
    public void aLensThatIsEvenAlready_isLeftAlone() {
        StitchScene scene = scene();
        Equirect small = new Equirect(720, 30);
        double[] fitted = Vignetting.fit(warps(scene, small), frames(scene), small);
        assertEquals(1, Math.exp(Vignetting.logFalloff(fitted, 1)), 0.02);
    }

    @Test
    public void tooFewOverlappingPixels_sayNothing() {
        StitchScene scene = new StitchScene().add(0, 0, 0, 0, 0).add(120, 0, 0, 0, 0);
        Equirect small = new Equirect(720, 30);
        double[] fitted = Vignetting.fit(warps(scene, small), frames(scene), small);
        assertEquals(0, fitted[0], 0);
        assertEquals(0, fitted[1], 0);
    }

    @Test
    public void theCorrectionIsHeldBackFromBlowingOutCorners() {
        assertEquals(1f, Vignetting.correction(Vignetting.NONE, 1), 0f);
        assertEquals(Math.exp(0.4), Vignetting.correction(new double[] {-0.4, 0}, 1), 1e-6);
        assertEquals(Vignetting.MAX_CORRECTION,
                Vignetting.correction(new double[] {-3, 0}, 1), 1e-6);
        assertEquals(0, Vignetting.radiusSquared(scene().frames.get(0), 320, 240), 1e-12);
        assertEquals(1, Vignetting.radiusSquared(scene().frames.get(0), 640, 480), 1e-12);
    }

    // Helpers.

    private static StitchFrame[] frames(StitchScene scene) {
        return scene.frames.toArray(new StitchFrame[0]);
    }

    private static List<FrameWarp> warps(StitchScene scene, Equirect small) {
        List<FrameWarp> warps = new ArrayList<>();
        for (int i = 0; i < scene.frames.size(); i++) {
            warps.add(FrameWarp.of(i, scene.frames.get(i), scene.images.get(i), 1, small));
        }
        return warps;
    }

    private static void darkenCorners(ArgbImage image, StitchFrame frame, double[] lens) {
        for (int y = 0; y < image.height; y++) {
            for (int x = 0; x < image.width; x++) {
                double falloff = Math.exp(Vignetting.logFalloff(lens,
                        Vignetting.radiusSquared(frame, x + 0.5, y + 0.5)));
                int level = (int) Math.round(ArgbImage.green(image.pixels[y * image.width + x])
                        * falloff);
                image.pixels[y * image.width + x] = 0xFF000000 | (level << 16) | (level << 8)
                        | level;
            }
        }
    }

    /** The mean difference of the logarithms of overlapping frames' brightness. */
    private static double disagreement(List<FrameWarp> warps, Equirect small) {
        double sum = 0;
        int count = 0;
        for (int i = 0; i < warps.size(); i++) {
            FrameWarp a = warps.get(i);
            for (int j = i + 1; j < warps.size(); j++) {
                FrameWarp b = warps.get(j);
                for (int row = 0; row < a.height; row++) {
                    for (int column = 0; column < a.width; column++) {
                        int pa = a.pixels[row * a.width + column];
                        if (pa == 0) {
                            continue;
                        }
                        int pb = b.at(small.wrap(a.left + column), a.top + row, small.width);
                        if (pb == 0) {
                            continue;
                        }
                        sum += Math.abs(Math.log(ArgbImage.green(pa) + 1.0)
                                - Math.log(ArgbImage.green(pb) + 1.0));
                        count++;
                    }
                }
            }
        }
        return sum / Math.max(1, count);
    }
}
