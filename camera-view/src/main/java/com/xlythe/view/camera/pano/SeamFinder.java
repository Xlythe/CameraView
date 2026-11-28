package com.xlythe.view.camera.pano;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides which frame each pixel of the picture comes from, so that the seams between frames run
 * where the frames agree.
 *
 * <p>Every pixel starts with the frame whose centre is nearest it, where its lens is sharpest and
 * least distorted. Then each pair of frames that meet is settled by a minimum cut over the pixels
 * either could supply: the cost of cutting between two neighbouring pixels is how much the two
 * frames disagree at them, after their exposures are matched (Kwatra et al., 2003). The cut runs
 * through what the frames show alike and around what they show differently: someone who walked
 * across the overlap between one frame and the next, or something close enough to the lens that
 * the two frames see it from different angles. That is where ghosts and doubled edges come from,
 * and a cut that goes around them keeps one whole copy.</p>
 *
 * <p>It works on the small picture, where a pair's overlap is a few thousand pixels.</p>
 */
@WorkerThread
final class SeamFinder {
    /** What a cut costs even where the frames agree, so seams are no longer than they need be. */
    static final float BASE_COST = 1f;

    /** No frame. */
    static final int NONE = -1;

    private SeamFinder() {}

    /**
     * Labels every pixel of the rows given with the frame it comes from.
     *
     * @param top the first row of the small picture labelled
     * @param rows how many rows
     * @return each pixel's frame, or {@link #NONE}, row by row across the whole width
     */
    @NonNull
    static int[] find(@NonNull List<FrameWarp> warps, @NonNull StitchFrame[] frames,
            @NonNull Equirect grid, int top, int rows) {
        int width = grid.width;
        int[] labels = new int[width * rows];
        float[] closeness = new float[width * rows];
        Arrays.fill(labels, NONE);
        Arrays.fill(closeness, -2f);
        FrameWarp[] byFrame = new FrameWarp[frames.length];
        double[] direction = new double[3];
        double[] forward = new double[3];
        for (FrameWarp warp : warps) {
            byFrame[warp.frame] = warp;
            frames[warp.frame].forward(forward);
            for (int row = 0; row < warp.height; row++) {
                int y = warp.top + row - top;
                if (y < 0 || y >= rows) {
                    continue;
                }
                for (int column = 0; column < warp.width; column++) {
                    if (warp.pixels[row * warp.width + column] == 0) {
                        continue;
                    }
                    int x = grid.wrap(warp.left + column);
                    grid.direction(x, y + top, direction);
                    float dot = (float) (direction[0] * forward[0] + direction[1] * forward[1]
                            + direction[2] * forward[2]);
                    int p = y * width + x;
                    if (dot > closeness[p]) {
                        closeness[p] = dot;
                        labels[p] = warp.frame;
                    }
                }
            }
        }

        for (long pair : neighbours(labels, width, rows)) {
            int a = (int) (pair >> 32);
            int b = (int) pair;
            if (byFrame[a] != null && byFrame[b] != null) {
                cut(labels, width, rows, top, byFrame[a], byFrame[b], frames[a], frames[b]);
            }
        }
        return labels;
    }

    /** Every pair of frames whose labels touch, the lower frame first. */
    @NonNull
    private static Set<Long> neighbours(@NonNull int[] labels, int width, int rows) {
        Set<Long> pairs = new LinkedHashSet<>();
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < width; x++) {
                int here = labels[y * width + x];
                if (here == NONE) {
                    continue;
                }
                int right = labels[y * width + (x + 1) % width];
                int below = y + 1 < rows ? labels[(y + 1) * width + x] : NONE;
                for (int other : new int[] {right, below}) {
                    if (other != NONE && other != here) {
                        long a = Math.min(here, other);
                        long b = Math.max(here, other);
                        pairs.add((a << 32) | b);
                    }
                }
            }
        }
        return pairs;
    }

    /** Settles the seam between two frames by a minimum cut over the pixels either could own. */
    private static void cut(@NonNull int[] labels, int width, int rows, int top,
            @NonNull FrameWarp warpA, @NonNull FrameWarp warpB, @NonNull StitchFrame a,
            @NonNull StitchFrame b) {
        int firstRow = Math.max(0, Math.max(warpA.top, warpB.top) - top);
        int lastRow = Math.min(rows, Math.min(warpA.top + warpA.height, warpB.top + warpB.height)
                - top);
        if (firstRow >= lastRow) {
            return;
        }
        // The region: pixels labelled either frame that both frames reach, numbered as nodes
        // over the first frame's columns.
        int columns = warpA.width;
        int[] nodeAt = new int[(lastRow - firstRow) * columns];
        Arrays.fill(nodeAt, -1);
        int[] region = new int[nodeAt.length];
        int count = 0;
        for (int y = firstRow; y < lastRow; y++) {
            for (int column = 0; column < columns; column++) {
                int x = grid(warpA.left + column, width);
                int p = y * width + x;
                int label = labels[p];
                if ((label != warpA.frame && label != warpB.frame)
                        || warpA.at(x, y + top, width) == 0 || warpB.at(x, y + top, width) == 0) {
                    continue;
                }
                nodeAt[(y - firstRow) * columns + column] = count;
                region[count++] = p;
            }
        }
        if (count < 4) {
            return;
        }
        float[] costs = new float[count];
        for (int n = 0; n < count; n++) {
            int p = region[n];
            costs[n] = difference(p % width, p / width + top, width, warpA, warpB, a, b);
        }
        MaxFlow flow = new MaxFlow(count);
        boolean anySource = false;
        boolean anySink = false;
        int[] dx = {1, -1, 0, 0};
        int[] dy = {0, 0, 1, -1};
        for (int n = 0; n < count; n++) {
            int p = region[n];
            int x = p % width;
            int y = p / width;
            for (int k = 0; k < 4; k++) {
                int qy = y + dy[k];
                if (qy < 0 || qy >= rows) {
                    continue;
                }
                int qx = grid(x + dx[k], width);
                int other = -1;
                int column = grid(qx - warpA.left, width);
                if (qy >= firstRow && qy < lastRow && column < columns) {
                    other = nodeAt[(qy - firstRow) * columns + column];
                }
                if (other >= 0) {
                    // Each edge once, from the node on its left or above.
                    if (k == 0 || k == 2) {
                        float cost = costs[n] + costs[other] + BASE_COST;
                        flow.addEdge(n, other, cost, cost);
                    }
                    continue;
                }
                int label = labels[qy * width + qx];
                if (label == warpA.frame) {
                    flow.addTerminals(n, MaxFlow.INFINITE, 0);
                    anySource = true;
                } else if (label == warpB.frame) {
                    flow.addTerminals(n, 0, MaxFlow.INFINITE);
                    anySink = true;
                }
            }
        }
        if (!anySource || !anySink) {
            return;
        }
        flow.solve();
        for (int n = 0; n < count; n++) {
            labels[region[n]] = flow.isSourceSide(n) ? warpA.frame : warpB.frame;
        }
    }

    /** A column brought into the picture. */
    private static int grid(int x, int width) {
        int wrapped = x % width;
        return wrapped < 0 ? wrapped + width : wrapped;
    }

    /** How much two frames disagree at a pixel, exposure matched, out of about 440. */
    private static float difference(int x, int y, int width, @NonNull FrameWarp warpA,
            @NonNull FrameWarp warpB, @NonNull StitchFrame a, @NonNull StitchFrame b) {
        int pa = warpA.at(x, y, width);
        int pb = warpB.at(x, y, width);
        float dr = ArgbImage.red(pa) * a.gains[0] - ArgbImage.red(pb) * b.gains[0];
        float dg = ArgbImage.green(pa) * a.gains[1] - ArgbImage.green(pb) * b.gains[1];
        float db = ArgbImage.blue(pa) * a.gains[2] - ArgbImage.blue(pb) * b.gains[2];
        return (float) Math.sqrt(dr * dr + dg * dg + db * db);
    }
}
