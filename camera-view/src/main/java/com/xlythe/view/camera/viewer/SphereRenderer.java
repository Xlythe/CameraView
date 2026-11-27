package com.xlythe.view.camera.viewer;

import static com.xlythe.view.camera.ICameraModule.TAG;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Draws a photo sphere: its patches, each a mesh with its own texture, seen from the middle of the
 * sphere through whatever matrix the view last set.
 *
 * <p>Textures arrive as bitmaps from a worker and are uploaded at the start of the next frame,
 * then recycled, so a bitmap lives only from its decode to its upload. The low-resolution copy
 * is drawn first and the tiles over it, until every tile is in and the copy is let go.</p>
 */
final class SphereRenderer implements GLSurfaceView.Renderer {
    /** Hears when a GL context is ready for textures, on the GL thread. */
    interface Callback {
        /**
         * A new context is ready, and every texture from before it is gone.
         *
         * @param context counts the contexts, so uploads meant for an old one are dropped
         * @param maxTextureSize the largest texture it takes, or 0 if it did not say
         */
        @AnyThread
        void onContextReady(int context, int maxTextureSize);
    }

    /** Puts a bitmap into the texture bound to GL_TEXTURE_2D. */
    interface Uploader {
        /**
         * @param texture the texture's name, which is the one bound
         */
        @AnyThread
        void upload(int texture, @NonNull Bitmap bitmap);
    }

    /** Hears that a video surface is ready to decode into, on the GL thread. */
    interface VideoListener {
        /**
         * A surface for the video's frames. A new context makes a new one, and calls again.
         */
        @AnyThread
        void onVideoTexture(@NonNull SurfaceTexture texture);
    }

    /** Sees each patch with a texture, for tests that draw them without a GPU. */
    @VisibleForTesting
    interface PatchVisitor {
        void visit(@NonNull SphereMesh mesh, int texture, boolean base);
    }

    private static final String VERTEX_SHADER =
            "uniform mat4 uMvp;\n"
            + "attribute vec4 aPosition;\n"
            + "attribute vec2 aTexCoord;\n"
            + "varying vec2 vTexCoord;\n"
            + "void main() {\n"
            + "  gl_Position = uMvp * aPosition;\n"
            + "  vTexCoord = aTexCoord;\n"
            + "}\n";

    private static final String FRAGMENT_SHADER =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
            + "precision highp float;\n"
            + "#else\n"
            + "precision mediump float;\n"
            + "#endif\n"
            + "uniform sampler2D uTexture;\n"
            + "varying vec2 vTexCoord;\n"
            + "void main() {\n"
            + "  gl_FragColor = texture2D(uTexture, vTexCoord);\n"
            + "}\n";

    private static final String VIDEO_VERTEX_SHADER =
            "uniform mat4 uMvp;\n"
            + "uniform mat4 uTexMatrix;\n"
            + "attribute vec4 aPosition;\n"
            + "attribute vec2 aTexCoord;\n"
            + "varying vec2 vTexCoord;\n"
            + "void main() {\n"
            + "  gl_Position = uMvp * aPosition;\n"
            // A video frame's coordinates count up from its bottom, a bitmap's from its top.
            + "  vTexCoord = (uTexMatrix * vec4(aTexCoord.x, 1.0 - aTexCoord.y, 0.0, 1.0)).xy;\n"
            + "}\n";

    private static final String VIDEO_FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;\n"
            + "uniform samplerExternalOES uTexture;\n"
            + "varying vec2 vTexCoord;\n"
            + "void main() {\n"
            + "  gl_FragColor = texture2D(uTexture, vTexCoord);\n"
            + "}\n";

    /** Bytes in a float, for strides. */
    private static final int BYTES_PER_FLOAT = 4;

    /** Puts bitmaps into textures; replaced in tests, which have no GPU. */
    @NonNull
    private Uploader mUploader;

    /** Hears about new contexts. */
    @NonNull
    private final Callback mCallback;

    /** Guards what the main thread and workers hand over. */
    private final Object mLock = new Object();

    /** The matrix to draw with, as last set. */
    @GuardedBy("mLock")
    private final float[] mMvp = new float[16];

    /** Textures waiting to be uploaded. */
    @GuardedBy("mLock")
    private final List<Pending> mPending = new ArrayList<>();

    /** Whether every texture is to be deleted at the next frame. */
    @GuardedBy("mLock")
    private boolean mClear;

    /** Whether the low-resolution copy is to be deleted at the next frame. */
    @GuardedBy("mLock")
    private boolean mDropBase;

    /** The context textures are going into, counted from 1. */
    @GuardedBy("mLock")
    private int mContext;

    /** The patch a video is drawn on, or null when there is no video. */
    @GuardedBy("mLock")
    @Nullable
    private SphereMesh mVideoMesh;

    /** Hears about the video's surface. */
    @GuardedBy("mLock")
    @Nullable
    private VideoListener mVideoListener;

    /** The video's texture, or 0. Only the GL thread uses this and those after it. */
    private int mVideoTexture;

    /** Where the video's frames arrive, or null. */
    @Nullable
    private SurfaceTexture mVideoSurface;

    /** The video program, or 0 if it did not build. */
    private int mVideoProgram;

    /** Where the video's matrix goes. */
    private int mVideoMvpHandle;

    /** Where the video frame's own transform goes. */
    private int mVideoTexMatrixHandle;

    /** Where each video vertex's position goes. */
    private int mVideoPositionHandle;

    /** Where each video vertex's texture coordinate goes. */
    private int mVideoTexCoordHandle;

    /** The video frame's own transform, read each frame. */
    private final float[] mVideoTexMatrix = new float[16];

    /** The matrix, copied out under the lock for each frame. Only the GL thread uses it. */
    private final float[] mFrameMvp = new float[16];

    /** The patches with textures, base first. Only the GL thread uses these. */
    private final List<Patch> mPatches = new ArrayList<>();

    /** The program, or 0 if it did not build. */
    private int mProgram;

    /** Where each vertex's position goes in the program. */
    private int mPositionHandle;

    /** Where each vertex's texture coordinate goes. */
    private int mTexCoordHandle;

    /** Where the matrix goes. */
    private int mMvpHandle;

    /** Where the texture unit goes. */
    private int mTextureHandle;

    /** A texture waiting to be uploaded. */
    private static final class Pending {
        /** The context it is for. */
        final int context;

        /** Where it goes. */
        @NonNull
        final SphereMesh mesh;

        /** What goes in it; recycled once uploaded. */
        @NonNull
        final Bitmap bitmap;

        /** Whether it is the low-resolution copy. */
        final boolean base;

        /** Runs once it is uploaded or dropped, which lets the next decode start. */
        @NonNull
        final Runnable done;

        Pending(int context, @NonNull SphereMesh mesh, @NonNull Bitmap bitmap, boolean base,
                @NonNull Runnable done) {
            this.context = context;
            this.mesh = mesh;
            this.bitmap = bitmap;
            this.base = base;
            this.done = done;
        }
    }

    /** A patch with its texture. */
    private static final class Patch {
        /** Where the patch is on the sphere. */
        @NonNull
        final SphereMesh mesh;

        /** The texture's name. */
        final int texture;

        /** Whether it is the low-resolution copy. */
        final boolean base;

        /** How many texels its texture holds. */
        final long texels;

        Patch(@NonNull SphereMesh mesh, int texture, boolean base, long texels) {
            this.mesh = mesh;
            this.texture = texture;
            this.base = base;
            this.texels = texels;
        }
    }

    SphereRenderer(@NonNull Callback callback, @NonNull Uploader uploader) {
        mCallback = callback;
        mUploader = uploader;
        Matrix.setIdentityM(mMvp, 0);
    }

    SphereRenderer(@NonNull Callback callback) {
        this(callback, new Uploader() {
            @Override
            public void upload(int texture, @NonNull Bitmap bitmap) {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            }
        });
    }

    @VisibleForTesting
    void setUploader(@NonNull Uploader uploader) {
        mUploader = uploader;
    }

    /** The matrix the next frame is drawn with. */
    @VisibleForTesting
    @NonNull
    float[] getMatrix() {
        synchronized (mLock) {
            return mMvp.clone();
        }
    }

    /** Sets the matrix the next frame is drawn with. */
    @AnyThread
    void setMatrix(@NonNull float[] mvp) {
        synchronized (mLock) {
            System.arraycopy(mvp, 0, mMvp, 0, 16);
        }
    }

    /**
     * Hands over a texture for a patch, to be uploaded at the next frame.
     *
     * @param context the context it was planned for; if that has gone, it is dropped
     * @param done runs once it is uploaded or dropped
     */
    @AnyThread
    void submit(int context, @NonNull SphereMesh mesh, @NonNull Bitmap bitmap, boolean base,
            @NonNull Runnable done) {
        synchronized (mLock) {
            mPending.add(new Pending(context, mesh, bitmap, base, done));
        }
    }

    /**
     * Draws a video on a patch instead of textures: a surface for it is made at the next frame
     * and handed to the listener.
     */
    @AnyThread
    void showVideo(@NonNull SphereMesh mesh, @NonNull VideoListener listener) {
        synchronized (mLock) {
            mVideoMesh = mesh;
            mVideoListener = listener;
        }
    }

    /** Stops drawing a video; its surface is let go at the next frame. */
    @AnyThread
    void clearVideo() {
        synchronized (mLock) {
            mVideoMesh = null;
            mVideoListener = null;
        }
    }

    /** Whether a video is being drawn, for tests. */
    @VisibleForTesting
    boolean hasVideo() {
        synchronized (mLock) {
            return mVideoMesh != null;
        }
    }

    /** Lets go of the low-resolution copy, once the tiles cover everything it showed. */
    @AnyThread
    void dropBase() {
        synchronized (mLock) {
            mDropBase = true;
        }
    }

    /** Deletes every texture at the next frame and drops whatever is waiting. */
    @AnyThread
    void clear() {
        List<Pending> dropped;
        synchronized (mLock) {
            mClear = true;
            mDropBase = false;
            dropped = new ArrayList<>(mPending);
            mPending.clear();
        }
        for (Pending pending : dropped) {
            pending.bitmap.recycle();
            pending.done.run();
        }
    }

    /** How many patches have textures, for tests. */
    @VisibleForTesting
    int getPatchCount() {
        return mPatches.size();
    }

    /** How many texels the textures hold, for tests. */
    @VisibleForTesting
    long getTexels(boolean includeBase) {
        long texels = 0;
        for (Patch patch : mPatches) {
            if (includeBase || !patch.base) {
                texels += patch.texels;
            }
        }
        return texels;
    }

    /** Sees each patch, base first, for tests. Call on the GL thread. */
    @VisibleForTesting
    void visitPatches(@NonNull PatchVisitor visitor) {
        for (Patch patch : mPatches) {
            visitor.visit(patch.mesh, patch.texture, patch.base);
        }
    }

    @Override
    public void onSurfaceCreated(@Nullable GL10 unused, @Nullable EGLConfig config) {
        // Whatever textures there were went with the old context.
        mPatches.clear();
        mProgram = link();
        if (mProgram != 0) {
            mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
            mTexCoordHandle = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
            mMvpHandle = GLES20.glGetUniformLocation(mProgram, "uMvp");
            mTextureHandle = GLES20.glGetUniformLocation(mProgram, "uTexture");
        }
        // The old video surface went with the old context; a new one is made when drawn.
        releaseVideo();
        mVideoProgram = link(VIDEO_VERTEX_SHADER, VIDEO_FRAGMENT_SHADER);
        if (mVideoProgram != 0) {
            mVideoMvpHandle = GLES20.glGetUniformLocation(mVideoProgram, "uMvp");
            mVideoTexMatrixHandle = GLES20.glGetUniformLocation(mVideoProgram, "uTexMatrix");
            mVideoPositionHandle = GLES20.glGetAttribLocation(mVideoProgram, "aPosition");
            mVideoTexCoordHandle = GLES20.glGetAttribLocation(mVideoProgram, "aTexCoord");
        }
        int[] maxTextureSize = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0);
        int context;
        List<Pending> stale;
        synchronized (mLock) {
            context = ++mContext;
            mClear = false;
            mDropBase = false;
            stale = new ArrayList<>(mPending);
            mPending.clear();
        }
        for (Pending pending : stale) {
            pending.bitmap.recycle();
            pending.done.run();
        }
        mCallback.onContextReady(context, maxTextureSize[0]);
    }

    @Override
    public void onSurfaceChanged(@Nullable GL10 unused, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(@Nullable GL10 unused) {
        List<Pending> pending;
        boolean clear;
        boolean dropBase;
        int context;
        synchronized (mLock) {
            System.arraycopy(mMvp, 0, mFrameMvp, 0, 16);
            pending = new ArrayList<>(mPending);
            mPending.clear();
            clear = mClear;
            mClear = false;
            dropBase = mDropBase;
            mDropBase = false;
            context = mContext;
        }
        if (clear) {
            for (Patch patch : mPatches) {
                delete(patch.texture);
            }
            mPatches.clear();
        }
        for (Pending upload : pending) {
            try {
                if (upload.context == context) {
                    mPatches.add(upload.base ? 0 : mPatches.size(),
                            new Patch(upload.mesh, upload(upload.bitmap), upload.base,
                                    (long) upload.bitmap.getWidth() * upload.bitmap.getHeight()));
                }
            } finally {
                upload.bitmap.recycle();
                upload.done.run();
            }
        }
        if (dropBase) {
            for (int i = mPatches.size() - 1; i >= 0; i--) {
                if (mPatches.get(i).base) {
                    delete(mPatches.remove(i).texture);
                }
            }
        }

        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        SphereMesh videoMesh;
        VideoListener videoListener;
        synchronized (mLock) {
            videoMesh = mVideoMesh;
            videoListener = mVideoListener;
        }
        if (videoMesh != null && videoListener != null) {
            drawVideo(videoMesh, videoListener);
            return;
        }
        releaseVideo();
        if (mProgram == 0 || mPatches.isEmpty()) {
            return;
        }
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glUseProgram(mProgram);
        GLES20.glUniformMatrix4fv(mMvpHandle, 1, false, mFrameMvp, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glUniform1i(mTextureHandle, 0);
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glEnableVertexAttribArray(mTexCoordHandle);
        for (Patch patch : mPatches) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, patch.texture);
            patch.mesh.vertices.position(0);
            GLES20.glVertexAttribPointer(mPositionHandle, 3, GLES20.GL_FLOAT, false,
                    SphereMesh.FLOATS_PER_VERTEX * BYTES_PER_FLOAT, patch.mesh.vertices);
            patch.mesh.vertices.position(3);
            GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false,
                    SphereMesh.FLOATS_PER_VERTEX * BYTES_PER_FLOAT, patch.mesh.vertices);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, patch.mesh.vertexCount);
        }
        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTexCoordHandle);
    }

    /** Draws the latest video frame on its patch, making its surface first if need be. */
    private void drawVideo(@NonNull SphereMesh mesh, @NonNull VideoListener listener) {
        if (mVideoProgram == 0) {
            return;
        }
        if (mVideoSurface == null) {
            int[] names = new int[1];
            GLES20.glGenTextures(1, names, 0);
            mVideoTexture = names[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mVideoTexture);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            mVideoSurface = new SurfaceTexture(mVideoTexture);
            listener.onVideoTexture(mVideoSurface);
            return;
        }
        try {
            mVideoSurface.updateTexImage();
        } catch (RuntimeException abandoned) {
            Log.w(TAG, "The video's surface was abandoned", abandoned);
            return;
        }
        mVideoSurface.getTransformMatrix(mVideoTexMatrix);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glUseProgram(mVideoProgram);
        GLES20.glUniformMatrix4fv(mVideoMvpHandle, 1, false, mFrameMvp, 0);
        GLES20.glUniformMatrix4fv(mVideoTexMatrixHandle, 1, false, mVideoTexMatrix, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mVideoTexture);
        GLES20.glEnableVertexAttribArray(mVideoPositionHandle);
        GLES20.glEnableVertexAttribArray(mVideoTexCoordHandle);
        mesh.vertices.position(0);
        GLES20.glVertexAttribPointer(mVideoPositionHandle, 3, GLES20.GL_FLOAT, false,
                SphereMesh.FLOATS_PER_VERTEX * BYTES_PER_FLOAT, mesh.vertices);
        mesh.vertices.position(3);
        GLES20.glVertexAttribPointer(mVideoTexCoordHandle, 2, GLES20.GL_FLOAT, false,
                SphereMesh.FLOATS_PER_VERTEX * BYTES_PER_FLOAT, mesh.vertices);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.vertexCount);
        GLES20.glDisableVertexAttribArray(mVideoPositionHandle);
        GLES20.glDisableVertexAttribArray(mVideoTexCoordHandle);
    }

    /** Lets go of the video's surface and texture, if there are any. */
    private void releaseVideo() {
        if (mVideoSurface != null) {
            mVideoSurface.release();
            mVideoSurface = null;
        }
        if (mVideoTexture != 0) {
            delete(mVideoTexture);
            mVideoTexture = 0;
        }
    }

    /** Puts a bitmap in a new texture and returns its name. */
    private int upload(@NonNull Bitmap bitmap) {
        int[] names = new int[1];
        GLES20.glGenTextures(1, names, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, names[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        // Clamped: a tile's edges are its borders, and a texture of any size may only clamp.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);
        mUploader.upload(names[0], bitmap);
        return names[0];
    }

    private static void delete(int texture) {
        GLES20.glDeleteTextures(1, new int[] {texture}, 0);
    }

    /** Builds the picture program, or returns 0 and says why. */
    private static int link() {
        return link(VERTEX_SHADER, FRAGMENT_SHADER);
    }

    /** Builds a program, or returns 0 and says why. */
    private static int link(@NonNull String vertexShader, @NonNull String fragmentShader) {
        int vertex = compile(GLES20.GL_VERTEX_SHADER, vertexShader);
        int fragment = compile(GLES20.GL_FRAGMENT_SHADER, fragmentShader);
        if (vertex == 0 || fragment == 0) {
            return 0;
        }
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        // Attached, so the program keeps them for as long as it needs them.
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            Log.e(TAG, "Unable to link the photo sphere program: "
                    + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            return 0;
        }
        return program;
    }

    private static int compile(int type, @NonNull String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "Unable to compile a photo sphere shader: "
                    + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
}
