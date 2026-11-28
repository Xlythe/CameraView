package com.xlythe.view.camera.pano;

/**
 * A made-up world to point a test camera at: patches of brightness two degrees across, scattered
 * at random but the same every time, so any two frames that see the same direction agree about it
 * and no two places look alike.
 */
final class TestScene {
    /** The size of a patch, in degrees. */
    private static final float PATCH_DEGREES = 2f;

    private TestScene() {}

    /** The brightness of the world in a direction, from 0 to 255. */
    static int brightness(float east, float north, float up) {
        float yaw = SphereMath.yaw(east, north);
        float pitch = SphereMath.pitch(east, north, up);
        int column = (int) Math.floor((yaw + 180f) / PATCH_DEGREES);
        int row = (int) Math.floor((pitch + 90f) / PATCH_DEGREES);
        int hash = column * 73856093 ^ row * 19349663;
        hash ^= hash >>> 13;
        hash *= 0x5bd1e995;
        hash ^= hash >>> 15;
        return 30 + (hash & 0xFF) * 200 / 255;
    }

    /** What a camera at this pose sees, as ARGB. */
    static ArgbImage render(CameraPose pose) {
        int[] pixels = new int[pose.width * pose.height];
        float[] direction = new float[3];
        for (int y = 0; y < pose.height; y++) {
            for (int x = 0; x < pose.width; x++) {
                pose.unproject(x + 0.5f, y + 0.5f, direction);
                int level = brightness(direction[0], direction[1], direction[2]);
                pixels[y * pose.width + x] = 0xFF000000 | (level << 16) | (level << 8) | level;
            }
        }
        return new ArgbImage(pixels, pose.width, pose.height);
    }

    /** What a camera at this pose sees, as the NV21 a legacy camera would hand over. */
    static byte[] renderNv21(CameraPose pose) {
        byte[] nv21 = new byte[pose.width * pose.height * 3 / 2];
        float[] direction = new float[3];
        for (int y = 0; y < pose.height; y++) {
            for (int x = 0; x < pose.width; x++) {
                pose.unproject(x + 0.5f, y + 0.5f, direction);
                nv21[y * pose.width + x] =
                        (byte) brightness(direction[0], direction[1], direction[2]);
            }
        }
        for (int i = pose.width * pose.height; i < nv21.length; i++) {
            nv21[i] = (byte) 128;
        }
        return nv21;
    }
}
