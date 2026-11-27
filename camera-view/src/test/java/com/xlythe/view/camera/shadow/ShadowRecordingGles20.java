package com.xlythe.view.camera.shadow;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A GLES 2.0 that remembers what it was asked to do.
 *
 * <p>Robolectric's own shadow answers the few calls that would otherwise fail - a shader
 * compiles, a program links, a framebuffer is complete - and drops every other call. That is
 * enough to run a renderer and not enough to say anything about it: what a filter does is the
 * matrix it hands its shader, and what a background replacer does is the mask it uploads. This
 * gives the same answers and records the calls that carry those decisions, so a test can read
 * back what would have reached the GPU.</p>
 *
 * <p>Nothing here draws. A test can assert which matrix a frame was drawn with, at what size and
 * into which framebuffer, but not what the pixels came out as; that is for a device.</p>
 *
 * <p>Calls arrive on whichever thread owns the context, which in most tests is a render thread,
 * so every field is guarded by one lock and read back as a copy.</p>
 */
@Implements(GLES20.class)
public class ShadowRecordingGles20 {
    /** Guards every field below, since GL calls arrive on a render thread. */
    private static final Object sLock = new Object();

    /** The last name handed out, shared by textures, framebuffers, shaders and programs. */
    private static int sLastName;

    /** Whether a shader compiles, so a test can stand in for a driver that refuses one. */
    private static boolean sShadersCompile = true;

    /** What a framebuffer reports, so a test can stand in for one that cannot draw offscreen. */
    private static int sFramebufferStatus = GLES20.GL_FRAMEBUFFER_COMPLETE;

    /** Uniform locations by program and name, so the same name in two programs differs. */
    private static final Map<String, Integer> sLocations = new HashMap<>();

    /** The name each location was looked up under. */
    private static final Map<Integer, String> sLocationNames = new HashMap<>();

    /** The value last uploaded to each location. */
    private static final Map<Integer, float[]> sUniformValues = new HashMap<>();

    /** The value last uploaded under each name, whichever program it went to. */
    private static final Map<String, float[]> sLastUniformByName = new HashMap<>();

    /** Every texture upload, in order. */
    private static final List<TextureUpload> sUploads = new ArrayList<>();

    /** Every draw call, in order, with the state it was made in. */
    private static final List<Draw> sDraws = new ArrayList<>();

    /** Programs given back with {@code glDeleteProgram}. */
    private static final Set<Integer> sDeletedPrograms = new HashSet<>();

    /** Textures given back with {@code glDeleteTextures}. */
    private static final Set<Integer> sDeletedTextures = new HashSet<>();

    /** How many framebuffers have been made, which is how often a canvas was allocated. */
    private static int sFramebuffersCreated;

    /** The program last passed to {@code glUseProgram}. */
    private static int sProgramInUse;

    /** The framebuffer drawn into, 0 being the surface that is current. */
    private static int sFramebufferBound;

    /** The texture bound to {@code GL_TEXTURE_2D}, on whichever unit. */
    private static int sTexture2dBound;

    /** The texture bound to {@code GL_TEXTURE_EXTERNAL_OES}, on whichever unit. */
    private static int sExternalTextureBound;

    /** What was last passed to {@code glViewport}. */
    private static int[] sViewport = {0, 0, 0, 0};

    /** One {@code glTexImage2D}, and the texture it went to. */
    public static final class TextureUpload {
        /** The texture bound to {@code GL_TEXTURE_2D} when the upload was made. */
        public final int texture;
        /** The format the texture is stored in, such as {@code GL_ALPHA}. */
        public final int internalFormat;
        /** The width of what was uploaded, in texels. */
        public final int width;
        /** The height of what was uploaded, in texels. */
        public final int height;
        /** The bytes uploaded, or null for an allocation with no contents. */
        @Nullable public final byte[] pixels;

        private TextureUpload(int texture, int internalFormat, int width, int height,
                @Nullable byte[] pixels) {
            this.texture = texture;
            this.internalFormat = internalFormat;
            this.width = width;
            this.height = height;
            this.pixels = pixels;
        }
    }

    /** One {@code glDrawArrays}, and the state that decides what it draws. */
    public static final class Draw {
        /** The program in use. */
        public final int program;
        /** The framebuffer drawn into, 0 being the surface that is current. */
        public final int framebuffer;
        /** {@code {x, y, width, height}} as last passed to {@code glViewport}. */
        public final int[] viewport;
        /** The program's {@code uTextureMatrix}, or null if it was never given one. */
        @Nullable public final float[] textureMatrix;
        /** The texture bound to {@code GL_TEXTURE_2D}. */
        public final int texture2d;
        /** The texture bound to {@code GL_TEXTURE_EXTERNAL_OES}. */
        public final int externalTexture;

        private Draw(int program, int framebuffer, int[] viewport,
                @Nullable float[] textureMatrix, int texture2d, int externalTexture) {
            this.program = program;
            this.framebuffer = framebuffer;
            this.viewport = viewport;
            this.textureMatrix = textureMatrix;
            this.texture2d = texture2d;
            this.externalTexture = externalTexture;
        }
    }

    /** Makes every shader compile or none, for the path taken on a driver that refuses one. */
    public static void setShadersCompile(boolean compile) {
        synchronized (sLock) {
            sShadersCompile = compile;
        }
    }

    /** What {@code glCheckFramebufferStatus} reports from now on. */
    public static void setFramebufferStatus(int status) {
        synchronized (sLock) {
            sFramebufferStatus = status;
        }
    }

    /** The value last uploaded to a uniform of this name, or null if none was. */
    @Nullable
    public static float[] uniform(@NonNull String name) {
        synchronized (sLock) {
            float[] value = sLastUniformByName.get(name);
            return value == null ? null : value.clone();
        }
    }

    /** Every texture upload so far, in order. */
    @NonNull
    public static List<TextureUpload> uploads() {
        synchronized (sLock) {
            return new ArrayList<>(sUploads);
        }
    }

    /** Every draw call so far, in order. */
    @NonNull
    public static List<Draw> draws() {
        synchronized (sLock) {
            return new ArrayList<>(sDraws);
        }
    }

    public static boolean isProgramDeleted(int program) {
        synchronized (sLock) {
            return sDeletedPrograms.contains(program);
        }
    }

    public static boolean isTextureDeleted(int texture) {
        synchronized (sLock) {
            return sDeletedTextures.contains(texture);
        }
    }

    /** How many programs have been deleted, whichever they were. */
    public static int deletedProgramCount() {
        synchronized (sLock) {
            return sDeletedPrograms.size();
        }
    }

    public static int framebuffersCreated() {
        synchronized (sLock) {
            return sFramebuffersCreated;
        }
    }

    @Resetter
    public static void reset() {
        synchronized (sLock) {
            sLastName = 0;
            sShadersCompile = true;
            sFramebufferStatus = GLES20.GL_FRAMEBUFFER_COMPLETE;
            sLocations.clear();
            sLocationNames.clear();
            sUniformValues.clear();
            sLastUniformByName.clear();
            sUploads.clear();
            sDraws.clear();
            sDeletedPrograms.clear();
            sDeletedTextures.clear();
            sFramebuffersCreated = 0;
            sProgramInUse = 0;
            sFramebufferBound = 0;
            sTexture2dBound = 0;
            sExternalTextureBound = 0;
            sViewport = new int[] {0, 0, 0, 0};
        }
    }

    @Implementation
    protected static void glGenTextures(int n, int[] textures, int offset) {
        synchronized (sLock) {
            for (int i = 0; i < n; i++) {
                textures[offset + i] = ++sLastName;
            }
        }
    }

    @Implementation
    protected static void glGenFramebuffers(int n, int[] framebuffers, int offset) {
        synchronized (sLock) {
            for (int i = 0; i < n; i++) {
                framebuffers[offset + i] = ++sLastName;
                sFramebuffersCreated++;
            }
        }
    }

    @Implementation
    protected static int glCreateShader(int type) {
        synchronized (sLock) {
            return ++sLastName;
        }
    }

    @Implementation
    protected static int glCreateProgram() {
        synchronized (sLock) {
            return ++sLastName;
        }
    }

    @Implementation
    protected static void glGetShaderiv(int shader, int pname, int[] params, int offset) {
        synchronized (sLock) {
            if (pname == GLES20.GL_COMPILE_STATUS) {
                params[offset] = sShadersCompile ? GLES20.GL_TRUE : GLES20.GL_FALSE;
            }
        }
    }

    @Implementation
    protected static void glGetProgramiv(int program, int pname, int[] params, int offset) {
        if (pname == GLES20.GL_LINK_STATUS) {
            params[offset] = GLES20.GL_TRUE;
        }
    }

    @Implementation
    protected static int glCheckFramebufferStatus(int target) {
        synchronized (sLock) {
            return sFramebufferStatus;
        }
    }

    @Implementation
    protected static int glGetUniformLocation(int program, String name) {
        synchronized (sLock) {
            String key = program + "/" + name;
            Integer location = sLocations.get(key);
            if (location == null) {
                location = sLocations.size() + 1;
                sLocations.put(key, location);
                sLocationNames.put(location, name);
            }
            return location;
        }
    }

    @Implementation
    protected static void glUseProgram(int program) {
        synchronized (sLock) {
            sProgramInUse = program;
        }
    }

    @Implementation
    protected static void glBindTexture(int target, int texture) {
        synchronized (sLock) {
            if (target == GLES11Ext.GL_TEXTURE_EXTERNAL_OES) {
                sExternalTextureBound = texture;
            } else if (target == GLES20.GL_TEXTURE_2D) {
                sTexture2dBound = texture;
            }
        }
    }

    @Implementation
    protected static void glBindFramebuffer(int target, int framebuffer) {
        synchronized (sLock) {
            sFramebufferBound = framebuffer;
        }
    }

    @Implementation
    protected static void glViewport(int x, int y, int width, int height) {
        synchronized (sLock) {
            sViewport = new int[] {x, y, width, height};
        }
    }

    @Implementation
    protected static void glUniformMatrix4fv(int location, int count, boolean transpose,
            float[] value, int offset) {
        recordUniform(location, value, offset, 16);
    }

    @Implementation
    protected static void glUniform4fv(int location, int count, float[] v, int offset) {
        recordUniform(location, v, offset, 4);
    }

    @Implementation
    protected static void glUniform1f(int location, float x) {
        recordUniform(location, new float[] {x}, 0, 1);
    }

    @Implementation
    protected static void glUniform2f(int location, float x, float y) {
        recordUniform(location, new float[] {x, y}, 0, 2);
    }

    /** Answers the framebuffer bound and the viewport, which a renderer saves and restores. */
    @Implementation
    protected static void glGetIntegerv(int pname, int[] params, int offset) {
        synchronized (sLock) {
            if (pname == GLES20.GL_FRAMEBUFFER_BINDING) {
                params[offset] = sFramebufferBound;
            } else if (pname == GLES20.GL_VIEWPORT) {
                System.arraycopy(sViewport, 0, params, offset, sViewport.length);
            }
        }
    }

    @Implementation
    protected static void glTexImage2D(int target, int level, int internalformat, int width,
            int height, int border, int format, int type, Buffer pixels) {
        byte[] bytes = null;
        if (pixels instanceof ByteBuffer) {
            ByteBuffer copy = ((ByteBuffer) pixels).duplicate();
            copy.position(0);
            bytes = new byte[copy.remaining()];
            copy.get(bytes);
        }
        synchronized (sLock) {
            sUploads.add(new TextureUpload(sTexture2dBound, internalformat, width, height, bytes));
        }
    }

    @Implementation
    protected static void glDrawArrays(int mode, int first, int count) {
        synchronized (sLock) {
            Integer location = sLocations.get(sProgramInUse + "/uTextureMatrix");
            float[] matrix = location == null ? null : sUniformValues.get(location);
            sDraws.add(new Draw(sProgramInUse, sFramebufferBound, sViewport.clone(),
                    matrix == null ? null : matrix.clone(), sTexture2dBound,
                    sExternalTextureBound));
        }
    }

    @Implementation
    protected static void glDeleteProgram(int program) {
        synchronized (sLock) {
            sDeletedPrograms.add(program);
        }
    }

    @Implementation
    protected static void glDeleteTextures(int n, int[] textures, int offset) {
        synchronized (sLock) {
            for (int i = 0; i < n; i++) {
                sDeletedTextures.add(textures[offset + i]);
            }
        }
    }

    private static void recordUniform(int location, float[] source, int offset, int length) {
        float[] value = new float[length];
        System.arraycopy(source, offset, value, 0, length);
        synchronized (sLock) {
            sUniformValues.put(location, value);
            String name = sLocationNames.get(location);
            if (name != null) {
                sLastUniformByName.put(name, value);
            }
        }
    }
}
