package com.xlythe.view.camera.pano;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Small dense linear systems, for the few hundred unknowns stitching has. */
final class Linear {
    private Linear() {}

    /**
     * Solves a x = b by Gaussian elimination with partial pivoting.
     *
     * @param a n by n, row-major; left as it was
     * @return x, or null if a is singular
     */
    @Nullable
    static double[] solve(@NonNull double[] a, @NonNull double[] b, int n) {
        double[] m = a.clone();
        double[] x = b.clone();
        for (int column = 0; column < n; column++) {
            int pivot = column;
            for (int row = column + 1; row < n; row++) {
                if (Math.abs(m[row * n + column]) > Math.abs(m[pivot * n + column])) {
                    pivot = row;
                }
            }
            if (Math.abs(m[pivot * n + column]) < 1e-12) {
                return null;
            }
            if (pivot != column) {
                for (int k = 0; k < n; k++) {
                    double t = m[column * n + k];
                    m[column * n + k] = m[pivot * n + k];
                    m[pivot * n + k] = t;
                }
                double t = x[column];
                x[column] = x[pivot];
                x[pivot] = t;
            }
            for (int row = column + 1; row < n; row++) {
                double factor = m[row * n + column] / m[column * n + column];
                if (factor == 0) {
                    continue;
                }
                for (int k = column; k < n; k++) {
                    m[row * n + k] -= factor * m[column * n + k];
                }
                x[row] -= factor * x[column];
            }
        }
        for (int row = n - 1; row >= 0; row--) {
            double sum = x[row];
            for (int k = row + 1; k < n; k++) {
                sum -= m[row * n + k] * x[k];
            }
            x[row] = sum / m[row * n + row];
        }
        return x;
    }
}
