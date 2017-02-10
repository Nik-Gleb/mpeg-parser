package ru.nikitenkogleb.mpegparser;

import android.graphics.SurfaceTexture;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

/**
 * Holds state associated with a Surface used for MediaCodec decoder output.
 * <p>
 * The constructor for this class will prepare GL, create a SurfaceTexture,
 * and then create a Surface for that SurfaceTexture.  The Surface can be passed to
 * MediaCodec.configure() to receive decoder output.  When a frame arrives, we latch the
 * texture with updateTexImage(), then render the texture with GL to a pBuffer.
 * <p>
 * By default, the Surface will be using a BufferQueue in asynchronous mode, so we
 * can potentially drop frames.
 *
 * @author Nikitenko Gleb
 * @since 1.0, 09/02/2017
 */
final class CodecOutputSurface implements SurfaceTexture.OnFrameAvailableListener {

    /** The log-cat tag. */
    private static final String TAG = "CodecOutputSurface";

    /** The surface. */
    final Surface mSurface;

    /** The pixel buffer. */
    final ByteBuffer mPixelBuf;

    /** The lock monitor. */
    private final Object mFrameSyncObject = new Object(); // guards mFrameAvailable

    /** The open gl instance */
    private final EGL10 mEgl = (EGL10) EGLContext.getEGL();

    /** The configs. */
    private final EGLConfig[] mConfigs = new EGLConfig[1];

    /** The display. */
    private final EGLDisplay mEGLDisplay = createEglDisplay(mEgl, mConfigs);

    /** The open gl context. */
    private final EGLContext mEGLContext = createEglContext(mEgl, mEGLDisplay, mConfigs[0]);

    /** The surface. */
    private final EGLSurface mEGLSurface;

    /** The texture render. */
    private final TextureRender mTextureRender;

    /** The surface texture */
    private final SurfaceTexture mSurfaceTexture;

    /** Frame available flag. */
    private boolean mFrameAvailable;

    /**
     * Creates a CodecOutputSurface backed by a pBuffer with the specified dimensions.  The
     * new EGL context and surface will be made current.  Creates a Surface that can be passed
     * to MediaCodec.configure().
     */
    CodecOutputSurface(int width, int height) {

        // Initialize OpenGL
        mEGLSurface = createSurface(mEgl, mEGLDisplay, mConfigs[0], width, height);

        int error;
        if ((error = mEgl.eglGetError()) != EGL10.EGL_SUCCESS) {
            throw new RuntimeException(
                    "eglCreatePBufferSurface: EGL error: 0x" + Integer.toHexString(error));
        }

        if (mEGLSurface == null) {
            throw new RuntimeException("surface was null");
        }

        //Makes our EGL context and surface current.
        if (!mEgl.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }

        //Creates interconnected instances of TextureRender, SurfaceTexture, and Surface.
        mTextureRender = new TextureRender();

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "textureID=" + mTextureRender.textureID);
        }

        mSurfaceTexture = new SurfaceTexture(mTextureRender.textureID);

        // This doesn't work if this object is created on the thread that CTS started for
        // these test cases.
        //
        // The CTS-created thread has a Looper, and the SurfaceTexture constructor will
        // create a Handler that uses it.  The "frame available" message is delivered
        // there, but since we're not a Looper-based thread we'll never see it.  For
        // this to do anything useful, CodecOutputSurface must be created on a thread without
        // a Looper, so that SurfaceTexture uses the main application Looper instead.
        //
        // Java language note: passing "this" out of a constructor is generally unwise,
        // but we should be able to get away with it here.
        mSurfaceTexture.setOnFrameAvailableListener(this);

        mSurface = new Surface(mSurfaceTexture);

        mPixelBuf = ByteBuffer.allocateDirect(width * height * 4);
        mPixelBuf.order(ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Creating openGl display.
     *
     * @param egl10   open gl10
     * @param configs configs
     * @return the display
     */
    private static EGLDisplay createEglDisplay(@NonNull EGL10 egl10,
            @NonNull EGLConfig[] configs) {

        final EGLDisplay result = egl10.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);

        final int[] version = new int[2];
        if (!egl10.eglInitialize(result, version)) {
            if (Log.isLoggable(TAG, Log.WARN)) {
                Log.w(TAG, "Unable to initialize EGL14");
            }
            return null;
        }

        // Prepares EGL. We want a GLES 2.0 context and a surface that supports pBuffer.
        final int eglOpenGlEs2Bit = 0x0004;

        // Configure EGL for pBuffer and OpenGL ES 2.0, 24-bit RGB.
        final int[] attribList = {
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_RENDERABLE_TYPE, eglOpenGlEs2Bit,
                EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
                EGL10.EGL_NONE
        };

        if (!egl10.eglChooseConfig(result, attribList, configs, configs.length, new int[1])) {
            if (Log.isLoggable(TAG, Log.WARN)) {
                Log.w(TAG, "Unable to find RGB888+recordable ES2 EGL config");
            }
            return null;
        }

        return result;
    }

    /**
     * Construct a open gl context
     *
     * @param egl10      open gl instance
     * @param eglDisplay open gl display
     * @param eglConfig  open gl config
     * @return open gl context
     */
    private static EGLContext createEglContext(@NonNull EGL10 egl10,
            @Nullable EGLDisplay eglDisplay, @NonNull EGLConfig eglConfig) {

        if (eglDisplay == null) {
            return null;
        }

        // Prepares EGL. We want a GLES 2.0 context and a surface that supports pBuffer.
        final int eglContextClientVersion = 0x3098;
        // Configure context for OpenGL ES 2.0.
        final int[] attribListContext = {eglContextClientVersion, 2, EGL10.EGL_NONE};

        return egl10
                .eglCreateContext(eglDisplay, eglConfig, EGL10.EGL_NO_CONTEXT, attribListContext);
    }

    /**
     * Create Gl Surface.
     *
     * @param egl10      open gl instance
     * @param eglDisplay open gl display
     * @param eglConfig  open gl config
     * @param width      horizontal size
     * @param height     vertical size
     * @return the surface
     */
    private static EGLSurface createSurface(@NonNull EGL10 egl10,
            @Nullable EGLDisplay eglDisplay, @NonNull EGLConfig eglConfig,
            int width, int height) {

        if (eglDisplay == null) {
            return null;
        }

        // Create a pBuffer surface.
        final int[] surfaceAttrs = {EGL10.EGL_WIDTH, width, EGL10.EGL_HEIGHT, height,
                EGL10.EGL_NONE};
        return egl10.eglCreatePbufferSurface(eglDisplay, eglConfig, surfaceAttrs);
    }

    /** Discard all resources held by this class, notably the EGL context. */
    final void release() {

        if (mEGLDisplay != EGL10.EGL_NO_DISPLAY) {
            mEgl.eglDestroySurface(mEGLDisplay, mEGLSurface);
            mEgl.eglDestroyContext(mEGLDisplay, mEGLContext);
            //mEgl.eglReleaseThread();
            mEgl.eglMakeCurrent(mEGLDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE,
                    EGL10.EGL_NO_CONTEXT);
            mEgl.eglTerminate(mEGLDisplay);
        }

        mSurface.release();

        // this causes a bunch of warnings that appear harmless but might confuse someone:
        //  W BufferQueue: [unnamed-3997-2] cancelBuffer: BufferQueue has been abandoned!
        //mSurfaceTexture.release();

        mPixelBuf.clear();
    }

    /**
     * Latches the next buffer into the texture.  Must be called from the thread that created
     * the CodecOutputSurface object.  (More specifically, it must be called on the thread
     * with the EGLContext that contains the GL texture object used by SurfaceTexture.)
     */
    final void awaitNewImage() {
        final int TIMEOUT_MS = 2500;

        synchronized (mFrameSyncObject) {
            while (!mFrameAvailable) {
                try {
                    // Wait for onFrameAvailable() to signal us.  Use a timeout to avoid
                    // stalling the test if it doesn't arrive.
                    mFrameSyncObject.wait(TIMEOUT_MS);
                    if (!mFrameAvailable) {
                        // ======== if "spurious wakeup", continue while loop ==========
                        throw new RuntimeException("frame wait timed out");
                    }
                } catch (InterruptedException ie) {
                    // shouldn't happen
                    throw new RuntimeException(ie);
                }
            }
            mFrameAvailable = false;
        }

        // Latch the data.
        TextureRender.checkGlError("before updateTexImage");

        mSurfaceTexture.updateTexImage();
    }

    /** Draws the data from SurfaceTexture onto the current EGL surface. */
    final void drawImage() {
        // if set, render the image with Y inverted (0,0 in top left)
        mTextureRender.drawFrame(mSurfaceTexture);
    }

    /** {@inheritDoc} */
    @Override
    public final void onFrameAvailable(SurfaceTexture st) {

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "new frame available");
        }

        synchronized (mFrameSyncObject) {

            if (mFrameAvailable) {
                throw new RuntimeException("mFrameAvailable already set, frame could be dropped");
            }

            mFrameAvailable = true;
            mFrameSyncObject.notifyAll();
        }
    }

}
