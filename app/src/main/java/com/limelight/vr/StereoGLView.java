package com.limelight.vr;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;
import android.view.Choreographer;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

// VR mode: MediaCodec decodes into a SurfaceTexture (OES texture) and this view draws the
// same texture twice, one in each half of the screen (left/right eye).
//
// - Without headTracking (step 2): the video takes the largest rectangle with the original aspect ratio in each half.
// - With headTracking (step 3, "cinema"): the video is a virtual screen fixed in the world, at a fixed distance,
//   and the gyroscope (HeadTracker) moves the camera. Still without lens distortion.
public class StereoGLView extends GLSurfaceView implements GLSurfaceView.Renderer,
        SurfaceTexture.OnFrameAvailableListener {

    public interface SurfaceListener {
        // Called on the GL thread as soon as the decoder Surface exists
        void onVideoSurfaceReady(Surface surface);
    }

    private static final String VERTEX_SHADER =
            "uniform mat4 uTexMatrix;\n" +
            "uniform mat4 uMvp;\n" +
            "attribute vec2 aPos;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "  gl_Position = uMvp * vec4(aPos, 0.0, 1.0);\n" +
            "  vTex = (uTexMatrix * vec4(aPos * 0.5 + 0.5, 0.0, 1.0)).xy;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES uTex;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(uTex, vTex);\n" +
            "}\n";

    // Distortion pass: reads the eye scene (2D texture) and warps it into a "barrel" to cancel the
    // lens pincushion. s = p * (1 + k1 r^2), with p in [-1, 1] centered on the lens
    private static final String DIST_VERTEX_SHADER =
            "attribute vec2 aPos;\n" +
            "varying vec2 vPos;\n" +
            "void main() {\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "  vPos = aPos;\n" +
            "}\n";

    private static final String DIST_FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "uniform sampler2D uScene;\n" +
            "uniform float uK1;\n" +
            "uniform float uCa;\n" +
            "varying vec2 vPos;\n" +
            "// One color at a time: the lens refracts each wavelength a bit differently\n" +
            "vec3 sampleAt(float k, float r2, float channel) {\n" +
            "  vec2 s = vPos * (1.0 + k * r2) * 0.5 + 0.5;\n" +
            "  if (s.x < 0.0 || s.x > 1.0 || s.y < 0.0 || s.y > 1.0) {\n" +
            "    return vec3(0.0);\n" +
            "  }\n" +
            "  return texture2D(uScene, s).rgb * vec3(channel == 0.0 ? 1.0 : 0.0, channel == 1.0 ? 1.0 : 0.0, channel == 2.0 ? 1.0 : 0.0);\n" +
            "}\n" +
            "void main() {\n" +
            "  float r2 = dot(vPos, vPos);\n" +
            "  vec3 c = sampleAt(uK1 * (1.0 - uCa), r2, 0.0) + sampleAt(uK1, r2, 1.0) + sampleAt(uK1 * (1.0 + uCa), r2, 2.0);\n" +
            "  gl_FragColor = vec4(c, 1.0);\n" +
            "}\n";

    private static final float[] QUAD = {-1, -1, 1, -1, -1, 1, 1, 1};

    // Vertical field of view per eye and distance between the eyes (provisional values until step 4).
    // 80 degrees: with 40 mm lenses and ~45 mm focal length (an assumption for the Shinecon G13), half of
    // the S23 Ultra screen (~78 x 73 mm) covers about 80 x 79 degrees
    private static final float FOV_Y_DEGREES = 80.0f;

    private final int videoWidth;
    private final int videoHeight;
    private final boolean measureLatency;
    private final boolean headTracking;
    private final float lensK1;
    private final float chromatic;
    private final float ipd; // meters
    private final float pixelsPerMeter;
    private final HeadTracker headTracker;
    private final SurfaceListener listener;
    private final FloatBuffer quad;
    private final float[] texMatrix = new float[16];
    private final float[] identity = new float[16];
    private final float[] screenModel = new float[16];
    private final float[] projection = new float[16];
    private final float[] worldToEye = new float[16];
    private final float[] eyeView = new float[16];
    private final float[] eyeShift = new float[16];
    private final float[] mvp = new float[16];

    private SurfaceTexture surfaceTexture;
    private Surface videoSurface;
    private int program;
    private int texId;
    private int posLoc;
    private int texMatrixLoc;
    private int mvpLoc;
    private int distProgram;
    private int distPosLoc;
    private int distSceneLoc;
    private int distK1Loc;
    private int distCaLoc;
    private int fbo;
    private int fboTex;
    private int fboWidth;
    private int fboHeight;
    private int viewWidth;
    private int viewHeight;
    private volatile boolean frameAvailable;

    // Extra latency of the GL path: from onFrameAvailable to the end of drawing (with glFinish,
    // only when measureLatency). It does not include the swap/compositing nor the time until the photons.
    private volatile long frameAvailableNs;
    private long statsStartNs;
    private int statsFrames;
    private long statsSumNs;
    private long statsMaxNs;
    private long statsLastFrameNs;
    private long statsMaxGapNs;
    private int statsSlowGaps;

    // Mouse latency test (debug): reads 1 pixel from the center of the left eye on every new frame and
    // records the instant the luminance changes (the host flashes the screen when the mouse moves)
    private volatile boolean probe;
    private final java.nio.ByteBuffer probePixel = java.nio.ByteBuffer.allocateDirect(4);
    private int probeLuma = -1;

    // screenDistance and screenHeight (meters) define the virtual screen of cinema mode; the width comes from the video aspect ratio
    public StereoGLView(Context context, int videoWidth, int videoHeight, boolean measureLatency,
                        boolean headTracking, float screenDistance, float screenHeight, float lensK1, float chromatic, float ipd, SurfaceListener listener) {
        super(context);
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
        this.measureLatency = measureLatency;
        this.listener = listener;
        this.lensK1 = lensK1;
        this.chromatic = chromatic;
        this.ipd = ipd;
        this.pixelsPerMeter = context.getResources().getDisplayMetrics().xdpi / 0.0254f;

        Matrix.setIdentityM(identity, 0);

        // Quad (x, y) in [-1, 1] -> vertical plane screenDistance ahead (+Y), centered at eye height
        float halfWidth = screenHeight * videoWidth / videoHeight / 2;
        float halfHeight = screenHeight / 2;
        System.arraycopy(new float[] {
                halfWidth, 0, 0, 0,
                0, 0, halfHeight, 0,
                0, 1, 0, 0,
                0, screenDistance, 0, 1
        }, 0, screenModel, 0, 16);

        this.headTracker = new HeadTracker(context);
        this.headTracking = headTracking;

        quad = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(QUAD).position(0);

        // Touches and focus keep going to the StreamView underneath
        setClickable(false);
        setFocusable(false);

        setEGLContextClientVersion(2);
        setRenderer(this);
        // Draws when a video frame arrives and, with head tracking, also on every vsync (see startVsync)
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (headTracking) {
            if (headTracker.start()) {
                startVsync();
            }
            else {
                VrLog.w("GL:", "No TYPE_GAME_ROTATION_VECTOR on this device; using the fixed screen");
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopVsync();
        headTracker.stop();
        super.onDetachedFromWindow();
    }

    // One draw request per vsync, so the head moves even between video frames. Runs on its own
    // thread (each Looper has its own Choreographer) so it does not depend on the main thread.
    private HandlerThread vsyncThread;
    private volatile boolean vsyncRunning;

    private void startVsync() {
        vsyncThread = new HandlerThread("DeckVR-Vsync", Process.THREAD_PRIORITY_URGENT_DISPLAY);
        vsyncThread.start();
        vsyncRunning = true;
        new Handler(vsyncThread.getLooper()).post(() -> {
            Choreographer choreographer = Choreographer.getInstance();
            choreographer.postFrameCallback(new Choreographer.FrameCallback() {
                @Override
                public void doFrame(long frameTimeNanos) {
                    if (vsyncRunning) {
                        requestRender();
                        choreographer.postFrameCallback(this);
                    }
                }
            });
        });
    }

    private void stopVsync() {
        vsyncRunning = false;
        if (vsyncThread != null) {
            vsyncThread.quitSafely();
            vsyncThread = null;
        }
    }

    // Recenters the virtual screen on the current gaze direction
    public void recenter() {
        headTracker.recenter();
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        // Asynchronous swap: the new frame replaces the one not yet shown, so no buffer queue
        // builds up ahead of vsync (each draw reaches the screen on the next vsync) and
        // eglSwapBuffers does not block the GL thread
        EGL14.eglSwapInterval(EGL14.eglGetCurrentDisplay(), 0);
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        posLoc = GLES20.glGetAttribLocation(program, "aPos");
        texMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix");
        mvpLoc = GLES20.glGetUniformLocation(program, "uMvp");

        distProgram = buildProgram(DIST_VERTEX_SHADER, DIST_FRAGMENT_SHADER);
        distPosLoc = GLES20.glGetAttribLocation(distProgram, "aPos");
        distSceneLoc = GLES20.glGetUniformLocation(distProgram, "uScene");
        distK1Loc = GLES20.glGetUniformLocation(distProgram, "uK1");
        distCaLoc = GLES20.glGetUniformLocation(distProgram, "uCa");
        fbo = 0;
        fboTex = 0;
        fboWidth = fboHeight = 0;

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        texId = tex[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // The texture (and the Surface) is recreated if the GL context is lost
        if (videoSurface != null) {
            videoSurface.release();
            surfaceTexture.release();
        }
        surfaceTexture = new SurfaceTexture(texId);
        surfaceTexture.setDefaultBufferSize(videoWidth, videoHeight);
        surfaceTexture.setOnFrameAvailableListener(this);
        videoSurface = new Surface(surfaceTexture);

        listener.onVideoSurfaceReady(videoSurface);
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        viewWidth = width;
        viewHeight = height;
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        frameAvailableNs = System.nanoTime();
        frameAvailable = true;
        requestRender();
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        boolean newFrame = frameAvailable;
        if (newFrame) {
            frameAvailable = false;
            surfaceTexture.updateTexImage();
            surfaceTexture.getTransformMatrix(texMatrix);
        }

        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // The center of each eye sits ±IPD/2 from the screen center (the headset lenses are adjusted
        // to the IPD); the image width is limited so it does not cross the half of the screen
        int offset = Math.round(ipd * pixelsPerMeter / 2);
        int eyeWidth = Math.max(1, Math.min(viewWidth / 2, 2 * Math.min(viewWidth / 2 - offset, offset)));
        boolean tracked = headTracking && headTracker.getWorldToEye(worldToEye);
        if (tracked) {
            Matrix.perspectiveM(projection, 0, FOV_Y_DEGREES, (float) eyeWidth / viewHeight, 0.1f, 20f);
        }
        boolean distort = lensK1 > 0 && ensureFbo(eyeWidth, viewHeight);

        for (int eye = 0; eye < 2; eye++) {
            int centerX = viewWidth / 2 + (eye == 0 ? -offset : offset);
            int x = centerX - eyeWidth / 2;
            if (distort) {
                // Eye scene into the texture; then it is warped into the eye region
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                drawEye(eye, 0, eyeWidth, tracked);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
                GLES20.glScissor(eye * (viewWidth / 2), 0, viewWidth / 2, viewHeight);
                drawDistorted(x, eyeWidth);
            }
            else {
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
                GLES20.glScissor(eye * (viewWidth / 2), 0, viewWidth / 2, viewHeight);
                drawEye(eye, x, eyeWidth, tracked);
            }
        }
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);

        if (probe && newFrame) {
            GLES20.glReadPixels(viewWidth / 2 - offset, viewHeight / 2, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, probePixel);
            int luma = ((probePixel.get(0) & 0xFF) + (probePixel.get(1) & 0xFF) + (probePixel.get(2) & 0xFF)) / 3;
            if (probeLuma >= 0 && Math.abs(luma - probeLuma) > 60) {
                Log.i("DeckVR", "PROBE changed " + probeLuma + "->" + luma + " t=" + System.nanoTime());
            }
            probeLuma = luma;
        }

        // Only measures draws that consumed a new frame (not the rotation/resize ones)
        if (measureLatency && newFrame) {
            GLES20.glFinish();
            recordLatency(System.nanoTime() - frameAvailableNs);
        }
    }

    // Scene of one eye in the region [x, x + width) x [0, viewHeight) of the current framebuffer
    private void drawEye(int eye, int x, int width, boolean tracked) {
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, texMatrix, 0);
        GLES20.glEnableVertexAttribArray(posLoc);
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, quad);

        if (tracked) {
            // Virtual screen in the world; each eye sees from its own side (±IPD/2)
            float shift = (eye == 0 ? 1 : -1) * ipd / 2;
            Matrix.setIdentityM(eyeShift, 0);
            Matrix.translateM(eyeShift, 0, shift, 0, 0);
            Matrix.multiplyMM(eyeView, 0, eyeShift, 0, worldToEye, 0);
            Matrix.multiplyMM(mvp, 0, eyeView, 0, screenModel, 0);
            Matrix.multiplyMM(mvp, 0, projection, 0, mvp, 0);

            GLES20.glViewport(x, 0, width, viewHeight);
            GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvp, 0);
        }
        else {
            // The video takes the largest rectangle with the original aspect ratio (black bars elsewhere)
            float videoAspect = (float) videoWidth / videoHeight;
            int w = width;
            int h = (int) (w / videoAspect);
            if (h > viewHeight) {
                h = viewHeight;
                w = (int) (h * videoAspect);
            }
            GLES20.glViewport(x + (width - w) / 2, (viewHeight - h) / 2, w, h);
            GLES20.glUniformMatrix4fv(mvpLoc, 1, false, identity, 0);
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(posLoc);
    }

    private void drawDistorted(int x, int width) {
        GLES20.glUseProgram(distProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex);
        GLES20.glUniform1i(distSceneLoc, 0);
        GLES20.glUniform1f(distK1Loc, lensK1);
        GLES20.glUniform1f(distCaLoc, chromatic);
        GLES20.glEnableVertexAttribArray(distPosLoc);
        GLES20.glVertexAttribPointer(distPosLoc, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glViewport(x, 0, width, viewHeight);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(distPosLoc);
    }

    // Framebuffer with a texture the size of one eye; false if the device does not accept it
    private boolean ensureFbo(int width, int height) {
        if (fbo != 0 && fboWidth == width && fboHeight == height) {
            return true;
        }
        int[] id = new int[1];
        if (fboTex == 0) {
            GLES20.glGenTextures(1, id, 0);
            fboTex = id[0];
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        if (fbo == 0) {
            GLES20.glGenFramebuffers(1, id, 0);
            fbo = id[0];
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, fboTex, 0);
        boolean ok = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (!ok) {
            VrLog.w("GL:", "Incomplete framebuffer; no distortion correction");
            return false;
        }
        fboWidth = width;
        fboHeight = height;
        return true;
    }

    public void setProbe(boolean on) {
        probeLuma = -1;
        probe = on;
    }

    private void recordLatency(long ns) {
        long now = System.nanoTime();
        if (statsFrames == 0) {
            statsStartNs = now;
        }
        statsFrames++;
        statsSumNs += ns;
        statsMaxNs = Math.max(statsMaxNs, ns);
        // Gap between drawn video frames: the image "stalls" when it exceeds ~2 frames (33 ms at 60 fps)
        if (statsLastFrameNs != 0) {
            long gap = now - statsLastFrameNs;
            statsMaxGapNs = Math.max(statsMaxGapNs, gap);
            if (gap > 33_000_000L) statsSlowGaps++;
        }
        statsLastFrameNs = now;

        if (now - statsStartNs >= 2_000_000_000L) {
            VrLog.i("GL:", "%d frames in %.1f s, frame->draw delay avg %.2f ms, max %.2f ms, max gap %.1f ms, stalls(>33ms) %d",
                    statsFrames, (now - statsStartNs) / 1e9, statsSumNs / 1e6 / statsFrames, statsMaxNs / 1e6,
                    statsMaxGapNs / 1e6, statsSlowGaps);
            statsFrames = 0;
            statsSumNs = 0;
            statsMaxNs = 0;
            statsMaxGapNs = 0;
            statsSlowGaps = 0;
        }
    }

    private static int buildProgram(String vertexSource, String fragmentSource) {
        int vs = compile(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, vs);
        GLES20.glAttachShader(prog, fs);
        GLES20.glLinkProgram(prog);
        int[] status = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            throw new IllegalStateException("Failed to link the GL program: " + GLES20.glGetProgramInfoLog(prog));
        }
        return prog;
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            throw new IllegalStateException("Failed to compile the GL shader: " + GLES20.glGetShaderInfoLog(shader));
        }
        return shader;
    }
}
