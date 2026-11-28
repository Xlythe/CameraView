package com.xlythe.view.camera.pano;

import androidx.annotation.NonNull;

/**
 * An equirectangular picture of the whole sphere: column x looks along a yaw and row y along a
 * pitch, both linearly, the middle column along {@link #yawOrigin} and the top row straight up,
 * as {@link SphereCanvas} lays it out. Columns wrap, so x may run off either side.
 */
final class Equirect {
    /** Columns all the way round. */
    final int width;

    /** Rows from straight up to straight down: half the width. */
    final int height;

    /** The yaw of the middle column, in degrees. */
    final double yawOrigin;

    Equirect(int width, double yawOrigin) {
        this.width = width;
        this.height = width / 2;
        this.yawOrigin = yawOrigin;
    }

    /** The yaw a column's centre looks along, in radians, unwrapped. */
    double yaw(double x) {
        return Math.toRadians(yawOrigin + (x + 0.5 - width / 2.0) * 360.0 / width);
    }

    /** The pitch a row's centre looks along, in radians, held to the poles. */
    double pitch(double y) {
        double degrees = 90.0 - (y + 0.5) * 180.0 / height;
        return Math.toRadians(Math.max(-90.0, Math.min(90.0, degrees)));
    }

    /** Where a yaw in degrees falls across, continuously, nearest to a column given. */
    double xOf(double yawDegrees, double near) {
        double x = (yawDegrees - yawOrigin) / 360.0 * width + width / 2.0 - 0.5;
        double turns = Math.round((near - x) / width);
        return x + turns * width;
    }

    /** Where a pitch in degrees falls down, continuously. */
    double yOf(double pitchDegrees) {
        return (90.0 - pitchDegrees) / 180.0 * height - 0.5;
    }

    /** A column brought into the picture. */
    int wrap(int x) {
        int wrapped = x % width;
        return wrapped < 0 ? wrapped + width : wrapped;
    }

    /** The unit vector a pixel's centre looks along, east, north and up. */
    void direction(double x, double y, @NonNull double[] out) {
        double yaw = yaw(x);
        double pitch = pitch(y);
        double horizontal = Math.cos(pitch);
        out[0] = horizontal * Math.sin(yaw);
        out[1] = horizontal * Math.cos(yaw);
        out[2] = Math.sin(pitch);
    }

    /**
     * Where a frame's corners and edges fall on this picture, as the leftmost column, the
     * number of columns, the top row and the number of rows, rounded outwards. Every column when
     * the frame contains a pole.
     */
    @NonNull
    int[] bounds(@NonNull StitchFrame frame) {
        double[] direction = new double[3];
        double[] camera = new double[3];
        double[] toCamera = Rotations.transpose(frame.toWorld);
        double centreX = xOfDirection(frame.toWorld[2], frame.toWorld[5], frame.toWorld[8], 0);
        double minX = centreX;
        double maxX = centreX;
        double minY = yOfDirection(frame.toWorld[8]);
        double maxY = minY;
        int samples = 24;
        for (int edge = 0; edge < 4; edge++) {
            for (int s = 0; s <= samples; s++) {
                double t = (double) s / samples;
                double px;
                double py;
                switch (edge) {
                    case 0:
                        px = t * frame.width;
                        py = 0;
                        break;
                    case 1:
                        px = frame.width;
                        py = t * frame.height;
                        break;
                    case 2:
                        px = (1 - t) * frame.width;
                        py = frame.height;
                        break;
                    default:
                        px = 0;
                        py = (1 - t) * frame.height;
                        break;
                }
                double a = (px - frame.width / 2.0) / frame.focal;
                double b = (py - frame.height / 2.0) / frame.focal;
                Rotations.apply(frame.toWorld, a, b, 1, direction);
                double length = Math.sqrt(direction[0] * direction[0]
                        + direction[1] * direction[1] + direction[2] * direction[2]);
                double x = xOfDirection(direction[0], direction[1], direction[2], centreX);
                double y = yOfDirection(direction[2] / length);
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        boolean pole = false;
        for (int sign = -1; sign <= 1; sign += 2) {
            Rotations.apply(toCamera, 0, 0, sign, camera);
            if (camera[2] > 0) {
                double px = frame.width / 2.0 + frame.focal * camera[0] / camera[2];
                double py = frame.height / 2.0 + frame.focal * camera[1] / camera[2];
                if (px >= 0 && px <= frame.width && py >= 0 && py <= frame.height) {
                    pole = true;
                    if (sign > 0) {
                        minY = 0;
                    } else {
                        maxY = height - 1;
                    }
                }
            }
        }
        int top = Math.max(0, (int) Math.floor(minY) - 1);
        int bottom = Math.min(height - 1, (int) Math.ceil(maxY) + 1);
        if (pole || maxX - minX >= width - 2) {
            return new int[] {0, width, top, bottom - top + 1};
        }
        int left = (int) Math.floor(minX) - 1;
        int right = (int) Math.ceil(maxX) + 1;
        return new int[] {left, right - left + 1, top, bottom - top + 1};
    }

    private double xOfDirection(double east, double north, double up, double near) {
        double yaw = Math.toDegrees(Math.atan2(east, north));
        if (east == 0 && north == 0) {
            yaw = yawOrigin;
        }
        return xOf(yaw, near);
    }

    private double yOfDirection(double up) {
        return yOf(Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, up)))));
    }
}
