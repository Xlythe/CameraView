package com.xlythe.view.camera.viewer;

import android.graphics.Bitmap;
import android.opengl.Matrix;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Draws what the renderer would, one pixel at a time and without a GPU: its patches, through its
 * matrix, sampling the textures it uploaded. A test asks what colour lands at a point of the
 * screen, which is the same question a device test asks the GPU.
 *
 * <p>Each triangle is projected as GL projects it, and the texture coordinates interpolated with
 * perspective correction as GL interpolates them. Later patches cover earlier ones, as they do
 * when drawn in order.</p>
 */
final class SoftwareSphere {
    /** One projected patch. */
    private static final class Projected {
        /** x and y in normalized device coordinates, and w, for each vertex. */
        final float[] ndc;
        /** s and t for each vertex. */
        final float[] st;
        final Bitmap texture;
        final int vertexCount;

        Projected(float[] ndc, float[] st, Bitmap texture, int vertexCount) {
            this.ndc = ndc;
            this.st = st;
            this.texture = texture;
            this.vertexCount = vertexCount;
        }
    }

    private final List<Projected> mPatches = new ArrayList<>();

    /**
     * @param textures what each texture name was uploaded with
     */
    SoftwareSphere(@NonNull SphereRenderer renderer, @NonNull Map<Integer, Bitmap> textures) {
        float[] mvp = renderer.getMatrix();
        renderer.visitPatches((mesh, texture, base) -> {
            float[] vertices = new float[mesh.vertexCount * SphereMesh.FLOATS_PER_VERTEX];
            mesh.vertices.position(0);
            mesh.vertices.get(vertices);
            float[] ndc = new float[mesh.vertexCount * 3];
            float[] st = new float[mesh.vertexCount * 2];
            float[] clip = new float[4];
            for (int i = 0; i < mesh.vertexCount; i++) {
                int o = i * SphereMesh.FLOATS_PER_VERTEX;
                Matrix.multiplyMV(clip, 0, mvp, 0,
                        new float[] {vertices[o], vertices[o + 1], vertices[o + 2], 1}, 0);
                ndc[i * 3] = clip[0] / clip[3];
                ndc[i * 3 + 1] = clip[1] / clip[3];
                ndc[i * 3 + 2] = clip[3];
                st[i * 2] = vertices[o + 3];
                st[i * 2 + 1] = vertices[o + 4];
            }
            mPatches.add(new Projected(ndc, st, textures.get(texture), mesh.vertexCount));
        });
    }

    /**
     * The colour drawn at a point of the screen.
     *
     * @param x across, from -1 at the left edge to 1 at the right
     * @param y up, from -1 at the bottom to 1 at the top
     * @return the colour, or null where nothing is drawn and the clear colour shows
     */
    @Nullable
    Integer colorAt(float x, float y) {
        Integer color = null;
        for (Projected patch : mPatches) {
            Integer drawn = colorIn(patch, x, y);
            if (drawn != null) {
                color = drawn;
            }
        }
        return color;
    }

    @Nullable
    private static Integer colorIn(@NonNull Projected patch, float x, float y) {
        float[] p = patch.ndc;
        for (int i = 0; i < patch.vertexCount; i += 3) {
            int a = i * 3;
            int b = a + 3;
            int c = a + 6;
            // Behind the eye, or so far round it that GL would clip it: not drawn.
            if (p[a + 2] < 1e-3f || p[b + 2] < 1e-3f || p[c + 2] < 1e-3f) {
                continue;
            }
            float denominator = (p[b + 1] - p[c + 1]) * (p[a] - p[c])
                    + (p[c] - p[b]) * (p[a + 1] - p[c + 1]);
            if (Math.abs(denominator) < 1e-12f) {
                continue;
            }
            float wa = ((p[b + 1] - p[c + 1]) * (x - p[c]) + (p[c] - p[b]) * (y - p[c + 1]))
                    / denominator;
            float wb = ((p[c + 1] - p[a + 1]) * (x - p[c]) + (p[a] - p[c]) * (y - p[c + 1]))
                    / denominator;
            float wc = 1 - wa - wb;
            if (wa < -1e-6f || wb < -1e-6f || wc < -1e-6f) {
                continue;
            }
            // Perspective-correct: interpolate s/w, t/w and 1/w, then divide.
            float ia = wa / p[a + 2];
            float ib = wb / p[b + 2];
            float ic = wc / p[c + 2];
            float sum = ia + ib + ic;
            int sa = i * 2;
            float s = (ia * patch.st[sa] + ib * patch.st[sa + 2] + ic * patch.st[sa + 4]) / sum;
            float t = (ia * patch.st[sa + 1] + ib * patch.st[sa + 3] + ic * patch.st[sa + 5])
                    / sum;
            Bitmap texture = patch.texture;
            int px = Math.max(0, Math.min(texture.getWidth() - 1,
                    (int) Math.floor(s * texture.getWidth())));
            int py = Math.max(0, Math.min(texture.getHeight() - 1,
                    (int) Math.floor(t * texture.getHeight())));
            return texture.getPixel(px, py);
        }
        return null;
    }
}
