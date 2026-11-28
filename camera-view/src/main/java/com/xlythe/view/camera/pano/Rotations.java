package com.xlythe.view.camera.pano;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Rotations as row-major 3x3 matrices of doubles, for the alignment, where float rounding over
 * a hundred frames and a dozen iterations is the size of the errors being removed.
 */
final class Rotations {
    private Rotations() {}

    /** The identity. */
    @NonNull
    static double[] identity() {
        return new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
    }

    /** a times b. */
    @NonNull
    static double[] multiply(@NonNull double[] a, @NonNull double[] b) {
        double[] out = new double[9];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                out[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c];
            }
        }
        return out;
    }

    /** The transpose, which for a rotation is its inverse. */
    @NonNull
    static double[] transpose(@NonNull double[] a) {
        return new double[] {a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8]};
    }

    /** a times the vector v, into out, which may not be v. */
    static void apply(@NonNull double[] a, double x, double y, double z, @NonNull double[] out) {
        out[0] = a[0] * x + a[1] * y + a[2] * z;
        out[1] = a[3] * x + a[4] * y + a[5] * z;
        out[2] = a[6] * x + a[7] * y + a[8] * z;
    }

    /** The rotation by the vector w: about its direction, by its length in radians. */
    @NonNull
    static double[] exp(double wx, double wy, double wz) {
        double angle = Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (angle < 1e-12) {
            return new double[] {1, -wz, wy, wz, 1, -wx, -wy, wx, 1};
        }
        double x = wx / angle;
        double y = wy / angle;
        double z = wz / angle;
        double c = Math.cos(angle);
        double s = Math.sin(angle);
        double t = 1 - c;
        return new double[] {
                t * x * x + c, t * x * y - s * z, t * x * z + s * y,
                t * x * y + s * z, t * y * y + c, t * y * z - s * x,
                t * x * z - s * y, t * y * z + s * x, t * z * z + c};
    }

    /** The rotation vector of a rotation: the inverse of {@link #exp}. */
    @NonNull
    static double[] log(@NonNull double[] r) {
        double cos = Math.max(-1, Math.min(1, (r[0] + r[4] + r[8] - 1) / 2));
        double angle = Math.acos(cos);
        double x = r[7] - r[5];
        double y = r[2] - r[6];
        double z = r[3] - r[1];
        if (angle < 1e-9) {
            return new double[] {x / 2, y / 2, z / 2};
        }
        if (Math.PI - angle < 1e-6) {
            // Half a turn, where the skew part vanishes: the axis is the diagonal's largest column.
            int k = r[0] >= r[4] && r[0] >= r[8] ? 0 : r[4] >= r[8] ? 1 : 2;
            double[] axis = new double[3];
            axis[k] = Math.sqrt(Math.max(0, (r[k * 4] + 1) / 2));
            for (int i = 0; i < 3; i++) {
                if (i != k) {
                    axis[i] = (r[k * 3 + i] + r[i * 3 + k]) / (4 * axis[k]);
                }
            }
            return new double[] {axis[0] * angle, axis[1] * angle, axis[2] * angle};
        }
        double scale = angle / (2 * Math.sin(angle));
        return new double[] {x * scale, y * scale, z * scale};
    }

    /** The angle of a rotation, in radians. */
    static double angle(@NonNull double[] r) {
        return Math.acos(Math.max(-1, Math.min(1, (r[0] + r[4] + r[8] - 1) / 2)));
    }

    /** A float matrix as doubles. */
    @NonNull
    static double[] of(@NonNull float[] m) {
        double[] out = new double[9];
        for (int i = 0; i < 9; i++) {
            out[i] = m[i];
        }
        return out;
    }

    /**
     * The rotation that best takes each of a set of unit vectors a onto its partner b, by Horn's
     * closed form: the eigenvector of the largest eigenvalue of a symmetric 4x4 built from the
     * cross-covariance is the rotation's quaternion.
     *
     * @param a x, y, z of each vector, one after another
     * @param b their partners, likewise
     * @param weights how much each pair counts, or null for equally
     */
    @NonNull
    static double[] fit(@NonNull double[] a, @NonNull double[] b, int count,
            @Nullable double[] weights) {
        double sxx = 0;
        double sxy = 0;
        double sxz = 0;
        double syx = 0;
        double syy = 0;
        double syz = 0;
        double szx = 0;
        double szy = 0;
        double szz = 0;
        for (int i = 0; i < count; i++) {
            double w = weights == null ? 1 : weights[i];
            double ax = a[i * 3];
            double ay = a[i * 3 + 1];
            double az = a[i * 3 + 2];
            double bx = b[i * 3] * w;
            double by = b[i * 3 + 1] * w;
            double bz = b[i * 3 + 2] * w;
            sxx += ax * bx;
            sxy += ax * by;
            sxz += ax * bz;
            syx += ay * bx;
            syy += ay * by;
            syz += ay * bz;
            szx += az * bx;
            szy += az * by;
            szz += az * bz;
        }
        double[] n = {
                sxx + syy + szz, syz - szy, szx - sxz, sxy - syx,
                syz - szy, sxx - syy - szz, sxy + syx, szx + sxz,
                szx - sxz, sxy + syx, -sxx + syy - szz, syz + szy,
                sxy - syx, szx + sxz, syz + szy, -sxx - syy + szz};
        double[] q = largestEigenvector4(n);
        return fromQuaternion(q[0], q[1], q[2], q[3]);
    }

    /** The rotation of a unit quaternion w, x, y, z. */
    @NonNull
    static double[] fromQuaternion(double w, double x, double y, double z) {
        double norm = Math.sqrt(w * w + x * x + y * y + z * z);
        w /= norm;
        x /= norm;
        y /= norm;
        z /= norm;
        return new double[] {
                1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y),
                2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
                2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)};
    }

    /** The eigenvector of a symmetric 4x4's largest eigenvalue, by Jacobi rotations. */
    @NonNull
    private static double[] largestEigenvector4(@NonNull double[] matrix) {
        double[] a = matrix.clone();
        double[] v = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        for (int sweep = 0; sweep < 50; sweep++) {
            double off = 0;
            for (int p = 0; p < 4; p++) {
                for (int q = p + 1; q < 4; q++) {
                    off += a[p * 4 + q] * a[p * 4 + q];
                }
            }
            if (off < 1e-24) {
                break;
            }
            for (int p = 0; p < 4; p++) {
                for (int q = p + 1; q < 4; q++) {
                    double apq = a[p * 4 + q];
                    if (Math.abs(apq) < 1e-300) {
                        continue;
                    }
                    double theta = (a[q * 4 + q] - a[p * 4 + p]) / (2 * apq);
                    double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                    if (theta == 0) {
                        t = 1;
                    }
                    double c = 1 / Math.sqrt(t * t + 1);
                    double s = t * c;
                    for (int k = 0; k < 4; k++) {
                        double akp = a[k * 4 + p];
                        double akq = a[k * 4 + q];
                        a[k * 4 + p] = c * akp - s * akq;
                        a[k * 4 + q] = s * akp + c * akq;
                    }
                    for (int k = 0; k < 4; k++) {
                        double apk = a[p * 4 + k];
                        double aqk = a[q * 4 + k];
                        a[p * 4 + k] = c * apk - s * aqk;
                        a[q * 4 + k] = s * apk + c * aqk;
                    }
                    for (int k = 0; k < 4; k++) {
                        double vkp = v[k * 4 + p];
                        double vkq = v[k * 4 + q];
                        v[k * 4 + p] = c * vkp - s * vkq;
                        v[k * 4 + q] = s * vkp + c * vkq;
                    }
                }
            }
        }
        int best = 0;
        for (int i = 1; i < 4; i++) {
            if (a[i * 5] > a[best * 5]) {
                best = i;
            }
        }
        return new double[] {v[best], v[4 + best], v[8 + best], v[12 + best]};
    }
}
