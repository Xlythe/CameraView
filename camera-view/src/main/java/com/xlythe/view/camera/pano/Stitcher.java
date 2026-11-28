package com.xlythe.view.camera.pano;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Stitches the frames kept from a sweep into one equirectangular picture.
 *
 * <ol>
 *   <li>Corners are found in every frame and matched between frames the sensor says overlap,
 *       keeping only matches one rotation explains ({@link PairMatcher}).</li>
 *   <li>Every frame is turned at once until the matches agree, held to the sensor's rotation
 *       about the horizontal axes and to the turns it measured between one frame and the next
 *       ({@link BundleAdjuster}).</li>
 *   <li>Overlaps too plain for corners, as on a ceiling or a floor, are aligned by their
 *       brightness from there ({@link DirectAligner}), and every frame is turned again with what
 *       those found as well, refining the focal length too when the matches close a loop.</li>
 *   <li>The frames are drawn onto a small picture, where their exposures are matched
 *       ({@link GainCompensator}) and the seams between them cut around whatever they disagree
 *       on ({@link SeamFinder}).</li>
 *   <li>The finished picture is drawn at full size and blended across the seams band by band
 *       ({@link MultiBandBlender}).</li>
 * </ol>
 *
 * <p>The picture is equirectangular for a panorama as for a photosphere: it is the only
 * projection photo sphere metadata describes, so it is what lets a viewer show a panorama as the
 * part of the sphere it is.</p>
 */
@WorkerThread
final class Stitcher {
    private static final String TAG = "CameraView";

    /** Corners are found in frames shrunk this much each way. */
    static final int FEATURE_SHRINK = 2;

    /** The small picture is this much smaller each way than the finished one. */
    static final int SMALL_SHRINK = 4;

    /**
     * Frames this far apart, measured as a share of the wider angle of view, are matched; past it
     * they overlap too little to share corners worth finding.
     */
    static final float PAIR_REACH = 0.85f;

    /** How far off the sensor may be, in degrees, which sets how far a corner is looked for. */
    static final float SENSOR_ERROR_DEGREES = 6f;

    /**
     * The sine of the steepest pitch a frame may look along and still count towards a loop round
     * the horizon: nearer a pole, a small turn is a large change of yaw.
     */
    static final double LOOP_PITCH = Math.sin(Math.toRadians(60));

    /** How many frames' pyramids are kept at once while aligning by brightness. */
    static final int PYRAMIDS_KEPT = 8;

    /** Takes the stitched picture, once its size is known. */
    interface Output {
        /** Where the picture's tiles go, for a picture this big. */
        @NonNull
        MultiBandBlender.Sink open(int width, int height);
    }

    /** Supplies each kept frame at full size. */
    interface Frames {
        /** The frame as kept, which may be read from storage; null if it cannot be. */
        @Nullable
        ArgbImage image(int frame);
    }

    /** Where the stitched picture sits on the sphere, and what stitching it found. */
    static final class Result {
        /** The whole sphere the picture is part of. */
        final Equirect grid;

        /** The picture: its left column on the sphere, top row, width and height. */
        final int[] region;

        /** What aligning the frames did. */
        @Nullable final BundleAdjuster.Result alignment;

        /** How many pairs of frames were matched. */
        final int pairs;

        /** The small picture the seams were cut on. */
        final Equirect small;

        /** Which frame owns each pixel of the small picture, rows from {@link #labelTop} on. */
        final int[] labels;

        /** The small picture's first labelled row. */
        final int labelTop;

        Result(Equirect grid, int[] region, @Nullable BundleAdjuster.Result alignment,
                int pairs, Equirect small, int[] labels, int labelTop) {
            this.grid = grid;
            this.region = region;
            this.alignment = alignment;
            this.pairs = pairs;
            this.small = small;
            this.labels = labels;
            this.labelTop = labelTop;
        }
    }

    /**
     * Keeps the few frames read most recently, since the blender asks for each per tile, from
     * several threads.
     */
    private static final class Cache implements Frames {
        /** How many frames are kept: a tile rarely draws on more. */
        private static final int SIZE = 6;

        /** Where frames come from. */
        private final Frames mSource;

        /** The frames kept, the least recently used first. */
        private final LinkedHashMap<Integer, ArgbImage> mKept =
                new LinkedHashMap<>(SIZE + 1, 1f, true);

        Cache(@NonNull Frames source) {
            mSource = source;
        }

        @Nullable
        @Override
        public synchronized ArgbImage image(int frame) {
            ArgbImage image = mKept.get(frame);
            if (image != null) {
                return image;
            }
            image = mSource.image(frame);
            if (image != null) {
                mKept.put(frame, image);
                if (mKept.size() > SIZE) {
                    mKept.remove(mKept.keySet().iterator().next());
                }
            }
            return image;
        }
    }

    /** The frames, in the order they were kept. */
    private final StitchFrame[] mFrames;

    /** Where their pixels come from. */
    private final Frames mSource;

    /** Whether the whole sphere was wanted, rather than a band round it. */
    private final boolean mPhotosphere;

    /** The pitch a panorama was swept along. */
    private final float mBandPitch;

    /** The widest the finished picture may be, all the way round. */
    private final int mMaxWidth;

    /** The yaw the picture is centred on. */
    private final float mYawOrigin;

    Stitcher(@NonNull List<StitchFrame> frames, @NonNull Frames source, boolean photosphere,
            float bandPitch, float yawOrigin, int maxWidth) {
        mFrames = frames.toArray(new StitchFrame[0]);
        mSource = new Cache(source);
        mPhotosphere = photosphere;
        mBandPitch = bandPitch;
        mYawOrigin = yawOrigin;
        mMaxWidth = maxWidth;
    }

    /**
     * Stitches the frames, handing the picture over a tile at a time.
     *
     * @return where the picture sits, or null if there was nothing to stitch
     */
    @Nullable
    Result stitch(@NonNull Output output) {
        if (mFrames.length == 0) {
            return null;
        }
        long start = System.nanoTime() / 1_000_000;
        int n = mFrames.length;
        Features[] features = new Features[n];
        byte[][] lumas = new byte[n][];
        ArgbImage[] small = new ArgbImage[n];
        float focal = mFrames[0].focal;
        float pixelsPerDegree = Math.min(focal * (float) Math.PI / 180f, mMaxWidth / 360f);
        int step = 2 * SMALL_SHRINK;
        int width = Math.max(step, step * Math.round(360f * pixelsPerDegree / step));
        Equirect grid = new Equirect(width, mYawOrigin);
        Equirect smallGrid = new Equirect(width / SMALL_SHRINK, mYawOrigin);
        // A small frame a little sharper than the small picture it is drawn into.
        float smallPixelsPerDegree = smallGrid.width / 360f;
        int smallShrink = Math.max(1, (int) Math.floor(
                focal * Math.PI / 180 / (2 * smallPixelsPerDegree)));
        for (int i = 0; i < n; i++) {
            ArgbImage image = mSource.image(i);
            if (image == null) {
                continue;
            }
            lumas[i] = luma(image, FEATURE_SHRINK);
            features[i] = Features.detect(lumas[i], image.width / FEATURE_SHRINK,
                    image.height / FEATURE_SHRINK);
            small[i] = shrink(image, smallShrink);
        }
        long detected = System.nanoTime() / 1_000_000;

        List<PairMatcher.Pair> pairs = match(features);
        long matched = System.nanoTime() / 1_000_000;
        double[][] sensor = new double[n][];
        long[] times = new long[n];
        for (int i = 0; i < n; i++) {
            sensor[i] = mFrames[i].sensorToWorld;
            times[i] = mFrames[i].timeMillis;
        }
        BundleAdjuster.Result alignment = null;
        if (!pairs.isEmpty()) {
            // The corners first, for the plain overlaps to start from.
            alignment = BundleAdjuster.adjust(sensor, times, pairs, focal / FEATURE_SHRINK,
                    false);
            for (int i = 0; i < n; i++) {
                mFrames[i].toWorld = alignment.toWorld[i];
            }
        }
        List<DirectAligner.Link> links = link(lumas, pairs);
        List<PairMatcher.Pair> all = new ArrayList<>(pairs);
        for (DirectAligner.Link link : links) {
            all.add(link.matches);
        }
        double focalScale = 1;
        if (!all.isEmpty()) {
            // Then everything, which also says how far off the calibrated focal length is when
            // the matches close a loop.
            alignment = BundleAdjuster.adjust(sensor, times, all, focal / FEATURE_SHRINK,
                    closesOnItself(all));
            focalScale = alignment.focalScale;
            for (int i = 0; i < n; i++) {
                mFrames[i].toWorld = alignment.toWorld[i];
            }
        }
        for (StitchFrame frame : mFrames) {
            frame.focal = (float) (frame.focal * focalScale);
        }
        long aligned = System.nanoTime() / 1_000_000;

        List<FrameWarp> warps = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (small[i] != null) {
                warps.add(FrameWarp.of(i, mFrames[i], small[i], smallShrink, smallGrid));
            }
        }
        if (warps.isEmpty()) {
            return null;
        }
        GainCompensator.compensate(warps, mFrames, smallGrid.width);
        int top = smallGrid.height;
        int bottom = 0;
        for (FrameWarp warp : warps) {
            top = Math.min(top, warp.top);
            bottom = Math.max(bottom, warp.top + warp.height);
        }
        top = Math.max(0, top);
        bottom = Math.min(smallGrid.height, bottom);
        int rows = bottom - top;
        int[] labels = SeamFinder.find(warps, mFrames, smallGrid, top, rows);
        long seamed = System.nanoTime() / 1_000_000;

        int bandRow = mPhotosphere ? -1
                : (int) Math.round(smallGrid.yOf(mBandPitch)) - top;
        int[] smallRegion = coveredRegion(labels, smallGrid.width, rows, bandRow);
        if (smallRegion == null) {
            return null;
        }
        int[] region = scaleRegion(smallRegion, top, smallGrid.width, grid);
        MultiBandBlender.Sink sink = output.open(region[2], region[3]);
        MultiBandBlender.blend(mFrames, frame -> {
            ArgbImage image = mSource.image(frame);
            return image != null ? image : small[frame];
        }, labels, top, rows, smallGrid, grid, region, sink);
        long blended = System.nanoTime() / 1_000_000;

        if (alignment != null) {
            Log.i(TAG, String.format(Locale.US, "Stitched %d frames, %d pairs, %d rays, %d "
                            + "aligned by brightness, focal length x%.4f: alignment error %.2f "
                            + "px rms (%.2f median, %.2f p95), %.2f with the sensor alone (%.2f "
                            + "median, %.2f p95)",
                    n, pairs.size(), alignment.rays, links.size(), focalScale,
                    alignment.rmsAfter, alignment.medianAfter,
                    alignment.p95After, alignment.rmsBefore, alignment.medianBefore,
                    alignment.p95Before));
        }
        Log.i(TAG, String.format(Locale.US, "Stitching took %d ms: corners %d, matches %d, "
                        + "alignment %d, seams %d, blending %d",
                blended - start, detected - start, matched - detected, aligned - matched,
                seamed - aligned, blended - seamed));
        return new Result(grid, region, alignment, pairs.size(), smallGrid, labels, top);
    }

    /**
     * Whether the frames go all the way round, so that the matches close a loop. Only then do
     * they say what the focal length is: along an open sweep, a lens a percent longer looks just
     * like turns a percent shorter.
     */
    private boolean closesOnItself(@NonNull List<PairMatcher.Pair> pairs) {
        // Each pair of frames below the caps that share matches covers the turn between them;
        // the matches close a loop when those turns cover the whole way round.
        boolean[] covered = new boolean[360];
        double[] fi = new double[3];
        double[] fj = new double[3];
        for (PairMatcher.Pair pair : pairs) {
            mFrames[pair.first].forward(fi);
            mFrames[pair.second].forward(fj);
            if (Math.abs(fi[2]) > LOOP_PITCH || Math.abs(fj[2]) > LOOP_PITCH) {
                continue;
            }
            double from = Math.toDegrees(Math.atan2(fi[0], fi[1]));
            double turn = Math.toDegrees(Math.atan2(fj[0], fj[1])) - from;
            turn = turn - 360 * Math.round(turn / 360);
            double start = turn >= 0 ? from : from + turn;
            for (int d = 0; d < Math.abs(turn); d++) {
                covered[(int) (((Math.floor(start) + d) % 360 + 360) % 360)] = true;
            }
        }
        for (boolean c : covered) {
            if (!c) {
                return false;
            }
        }
        return true;
    }

    /** Matches every pair of frames the sensor says overlap. */
    @NonNull
    private List<PairMatcher.Pair> match(@NonNull Features[] features) {
        List<PairMatcher.Pair> pairs = new ArrayList<>();
        int n = mFrames.length;
        Features.Index[] indexes = new Features.Index[n];
        double[] fi = new double[3];
        double[] fj = new double[3];
        for (int i = 0; i < n; i++) {
            if (features[i] == null) {
                continue;
            }
            StitchFrame a = mFrames[i];
            float focal = a.focal / FEATURE_SHRINK;
            float radius = (float) (focal * Math.tan(Math.toRadians(SENSOR_ERROR_DEGREES))) + 8;
            double wider = 2 * Math.atan(Math.max(a.width, a.height) / 2.0 / a.focal);
            a.forward(fi);
            for (int j = i + 1; j < n; j++) {
                if (features[j] == null) {
                    continue;
                }
                mFrames[j].forward(fj);
                double angle = Math.acos(Math.max(-1, Math.min(1,
                        fi[0] * fj[0] + fi[1] * fj[1] + fi[2] * fj[2])));
                if (angle > PAIR_REACH * wider) {
                    continue;
                }
                if (indexes[j] == null) {
                    indexes[j] = new Features.Index(features[j], 32);
                }
                PairMatcher.Pair pair = PairMatcher.match(i, features[i], a.sensorToWorld,
                        j, features[j], indexes[j], mFrames[j].sensorToWorld, focal, radius);
                if (pair != null) {
                    pairs.add(pair);
                }
            }
        }
        return pairs;
    }

    /**
     * Aligns by brightness every pair of frames that overlap but share too few corners to match,
     * starting from where the frames are now.
     */
    @NonNull
    private List<DirectAligner.Link> link(@NonNull byte[][] lumas,
            @NonNull List<PairMatcher.Pair> pairs) {
        int n = mFrames.length;
        boolean[] matched = new boolean[n * n];
        for (PairMatcher.Pair pair : pairs) {
            matched[pair.first * n + pair.second] = true;
        }
        List<DirectAligner.Link> links = new ArrayList<>();
        // A few frames' pyramids at a time, the least recently used dropped: a pyramid is quick
        // to make again, and every frame's at once is more memory than a phone may spare.
        LinkedHashMap<Integer, DirectAligner.Pyramid> pyramids =
                new LinkedHashMap<Integer, DirectAligner.Pyramid>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(
                            Map.Entry<Integer, DirectAligner.Pyramid> eldest) {
                        return size() > PYRAMIDS_KEPT;
                    }
                };
        double[] fi = new double[3];
        double[] fj = new double[3];
        for (int i = 0; i < n; i++) {
            if (lumas[i] == null) {
                continue;
            }
            StitchFrame a = mFrames[i];
            double wider = 2 * Math.atan(Math.max(a.width, a.height) / 2.0 / a.focal);
            a.forward(fi);
            for (int j = i + 1; j < n; j++) {
                if (lumas[j] == null || matched[i * n + j]) {
                    continue;
                }
                StitchFrame b = mFrames[j];
                b.forward(fj);
                double angle = Math.acos(Math.max(-1, Math.min(1,
                        fi[0] * fj[0] + fi[1] * fj[1] + fi[2] * fj[2])));
                if (angle > PAIR_REACH * wider) {
                    continue;
                }
                DirectAligner.Link link = DirectAligner.align(i, pyramid(pyramids, lumas, i),
                        j, pyramid(pyramids, lumas, j),
                        Rotations.multiply(Rotations.transpose(b.toWorld), a.toWorld),
                        a.focal / FEATURE_SHRINK);
                if (link != null) {
                    links.add(link);
                }
            }
        }
        return links;
    }

    /** A frame's pyramid, from those kept or made now. */
    @NonNull
    private DirectAligner.Pyramid pyramid(@NonNull Map<Integer, DirectAligner.Pyramid> kept,
            @NonNull byte[][] lumas, int frame) {
        DirectAligner.Pyramid pyramid = kept.get(frame);
        if (pyramid == null) {
            pyramid = new DirectAligner.Pyramid(lumas[frame],
                    mFrames[frame].width / FEATURE_SHRINK, mFrames[frame].height / FEATURE_SHRINK);
            kept.put(frame, pyramid);
        }
        return pyramid;
    }

    /** A frame's brightness, shrunk by a whole number each way by averaging. */
    @NonNull
    static byte[] luma(@NonNull ArgbImage image, int shrink) {
        int w = image.width / shrink;
        int h = image.height / shrink;
        byte[] out = new byte[w * h];
        int area = shrink * shrink;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sum = 0;
                for (int dy = 0; dy < shrink; dy++) {
                    int row = (y * shrink + dy) * image.width + x * shrink;
                    for (int dx = 0; dx < shrink; dx++) {
                        int argb = image.pixels[row + dx];
                        sum += (ArgbImage.red(argb) * 77 + ArgbImage.green(argb) * 150
                                + ArgbImage.blue(argb) * 29) >> 8;
                    }
                }
                out[y * w + x] = (byte) (sum / area);
            }
        }
        return out;
    }

    /** A frame shrunk by a whole number each way by averaging. */
    @NonNull
    static ArgbImage shrink(@NonNull ArgbImage image, int shrink) {
        if (shrink == 1) {
            return image;
        }
        int w = Math.max(1, image.width / shrink);
        int h = Math.max(1, image.height / shrink);
        int[] out = new int[w * h];
        int area = shrink * shrink;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = 0;
                int g = 0;
                int b = 0;
                for (int dy = 0; dy < shrink; dy++) {
                    int row = (y * shrink + dy) * image.width + x * shrink;
                    for (int dx = 0; dx < shrink; dx++) {
                        int argb = image.pixels[row + dx];
                        r += ArgbImage.red(argb);
                        g += ArgbImage.green(argb);
                        b += ArgbImage.blue(argb);
                    }
                }
                out[y * w + x] = 0xFF000000 | ((r / area) << 16) | ((g / area) << 8) | (b / area);
            }
        }
        return new ArgbImage(out, w, h);
    }

    /**
     * The largest rectangle of the small picture worth keeping: for a panorama, the band round
     * the row it was swept along that is covered all the way across; for a photosphere,
     * everything covered, less the widest gap of columns.
     *
     * @param bandRow the swept row, counted from the first labelled row, or -1 for a sphere
     * @return the left column, top row, width and height, rows counted from the first labelled
     *     row; or null if nothing is covered
     */
    @Nullable
    static int[] coveredRegion(@NonNull int[] labels, int width, int rows, int bandRow) {
        boolean[] coveredColumns = new boolean[width];
        int top = -1;
        int bottom = -1;
        for (int y = 0; y < rows; y++) {
            boolean any = false;
            for (int x = 0; x < width; x++) {
                if (labels[y * width + x] != SeamFinder.NONE) {
                    coveredColumns[x] = true;
                    any = true;
                }
            }
            if (any) {
                if (top < 0) {
                    top = y;
                }
                bottom = y;
            }
        }
        if (top < 0) {
            return null;
        }
        if (bandRow >= 0) {
            int row = Math.max(top, Math.min(bottom, bandRow));
            boolean[] alongRow = new boolean[width];
            for (int x = 0; x < width; x++) {
                alongRow[x] = labels[row * width + x] != SeamFinder.NONE;
            }
            int[] columns = widestRun(alongRow, true, width);
            if (columns[1] > 0) {
                int first = row;
                int last = row;
                while (first > 0 && isFull(labels, width, first - 1, columns[0], columns[1])) {
                    first--;
                }
                while (last < rows - 1
                        && isFull(labels, width, last + 1, columns[0], columns[1])) {
                    last++;
                }
                return new int[] {columns[0], first, columns[1], last - first + 1};
            }
        }
        int[] gap = widestRun(coveredColumns, false, width);
        int left = (gap[0] + gap[1]) % width;
        return new int[] {left, top, width - gap[1], bottom - top + 1};
    }

    /** Whether a row is covered all the way across a stretch of columns. */
    private static boolean isFull(@NonNull int[] labels, int width, int y, int left, int count) {
        for (int i = 0; i < count; i++) {
            if (labels[y * width + (left + i) % width] == SeamFinder.NONE) {
                return false;
            }
        }
        return true;
    }

    /**
     * The widest run of columns, going round, whose flag is {@code value}: its first column and
     * length, wrapping past the right edge.
     */
    @NonNull
    static int[] widestRun(@NonNull boolean[] columns, boolean value, int width) {
        int firstOther = -1;
        for (int x = 0; x < width && firstOther < 0; x++) {
            if (columns[x] != value) {
                firstOther = x;
            }
        }
        if (firstOther < 0) {
            return new int[] {0, width};
        }
        int bestStart = 0;
        int bestLength = 0;
        int runStart = -1;
        for (int step = 1; step <= width; step++) {
            int x = (firstOther + step) % width;
            if (columns[x] == value) {
                if (runStart < 0) {
                    runStart = step;
                }
            } else if (runStart >= 0) {
                if (step - runStart > bestLength) {
                    bestLength = step - runStart;
                    bestStart = (firstOther + runStart) % width;
                }
                runStart = -1;
            }
        }
        return new int[] {bestStart, bestLength};
    }

    /**
     * A region of the small picture at full size, pulled in by one small pixel on every edge that
     * is not the whole way round or a pole, since a small pixel on the edge may be only partly
     * covered.
     */
    @NonNull
    static int[] scaleRegion(@NonNull int[] small, int top, int smallWidth,
            @NonNull Equirect grid) {
        int scale = grid.width / smallWidth;
        int left = small[0] * scale;
        int width = small[2] * scale;
        if (small[2] < smallWidth) {
            left += scale;
            width -= 2 * scale;
        }
        int first = (small[1] + top) * scale;
        int height = small[3] * scale;
        if (first > 0) {
            first += scale;
            height -= scale;
        }
        if (first + height < grid.height) {
            height -= scale;
        }
        return new int[] {left, first, Math.max(scale, width), Math.max(scale, height)};
    }
}
