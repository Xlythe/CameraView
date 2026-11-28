package com.xlythe.view.camera.pano;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

/**
 * One frame drawn onto a small equirectangular picture, where exposure is matched and seams are
 * chosen: cheap enough to keep every frame's at once, and big enough to see what a seam crosses.
 *
 * <p>Pixels the frame does not reach are 0, which is transparent; every pixel it does reach is
 * opaque.</p>
 */
@WorkerThread
final class FrameWarp {
    /** Which frame this is. */
    final int frame;

    /** The picture's leftmost column, which may be off either side; columns wrap. */
    final int left;

    /** The top row. */
    final int top;

    /** How many columns. */
    final int width;

    /** How many rows. */
    final int height;

    /** The frame's colours, ARGB, row by row; 0 where the frame does not reach. */
    final int[] pixels;

    private FrameWarp(int frame, int left, int top, int width, int height, int[] pixels) {
        this.frame = frame;
        this.left = left;
        this.top = top;
        this.width = width;
        this.height = height;
        this.pixels = pixels;
    }

    /**
     * Draws a frame.
     *
     * @param small the frame shrunk by {@code shrink} in each direction
     */
    @NonNull
    static FrameWarp of(int index, @NonNull StitchFrame frame, @NonNull ArgbImage small,
            int shrink, @NonNull Equirect grid) {
        int[] bounds = grid.bounds(frame);
        int width = bounds[1];
        int height = bounds[3];
        int[] pixels = new int[width * height];
        double[] direction = new double[3];
        double[] camera = new double[3];
        double[] toCamera = Rotations.transpose(frame.toWorld);
        double cx = frame.width / 2.0;
        double cy = frame.height / 2.0;
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                grid.direction(bounds[0] + column, bounds[2] + row, direction);
                Rotations.apply(toCamera, direction[0], direction[1], direction[2], camera);
                if (camera[2] <= 1e-6) {
                    continue;
                }
                double px = cx + frame.focal * camera[0] / camera[2];
                double py = cy + frame.focal * camera[1] / camera[2];
                if (px < 1 || py < 1 || px > frame.width - 1 || py > frame.height - 1) {
                    continue;
                }
                pixels[row * width + column] = 0xFF000000 | small.sample(
                        (float) Math.max(0, px / shrink - 0.5),
                        (float) Math.max(0, py / shrink - 0.5));
            }
        }
        return new FrameWarp(index, bounds[0], bounds[2], width, height, pixels);
    }

    /**
     * The frame's colour at a pixel of the whole picture, or 0 where it does not reach.
     *
     * @param x the column, already wrapped into the picture
     */
    int at(int x, int y, int pictureWidth) {
        int row = y - top;
        if (row < 0 || row >= height) {
            return 0;
        }
        int column = x - left;
        column = ((column % pictureWidth) + pictureWidth) % pictureWidth;
        if (column >= width) {
            return 0;
        }
        return pixels[row * width + column];
    }
}
