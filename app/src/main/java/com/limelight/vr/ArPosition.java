package com.limelight.vr;

import android.content.Context;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import com.google.ar.core.CameraConfig;
import com.google.ar.core.CameraConfigFilter;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;

import java.util.EnumSet;
import java.util.List;

// Head position through ARCore (rear camera + sensors) for real walking: the logic (body axes, recenter, pose jump,
// "only while moving", joystick and analog modes) lives in ArPipeline, without Android; this class handles the ARCore session,
// sending the keys and logging. recenter() moves the origin to where the head is now.
//
// Diagnostics (VrLog, AR lines): frames/s and the largest gap between frames, minimum and maximum forward/side displacement per
// second, each key that goes down/up with the displacement at that moment and how long it was held, tracking losses with their
// duration, pose jumps between consecutive frames and the origin drift at each recenter.
//
// ARCore needs an OES texture for the camera image, so this class has its own EGL context
// (1x1 surface) on a dedicated thread, unrelated to StereoGLView.
public class ArPosition {
    private static final long LOG_WINDOW_MS = 1000;

    private static final short KEY_W = (short) 0x8057;
    private static final short KEY_A = (short) 0x8041;
    private static final short KEY_S = (short) 0x8053;
    private static final short KEY_D = (short) 0x8044;

    private final Context context;
    private final NvConnection conn;
    private final float thresholdM;
    private final boolean holdMode; // true = joystick (holds while away from the origin); false = only while moving
    private final MoveStick stickSink; // non-null = movement becomes the left analog stick instead of W/A/S/D
    private final ArPipeline pipeline;

    // Broadcast tests only: adds to the measured displacement one that grows at the requested speed (m/s);
    // while active, lost tracking does not interfere (it uses the last pose), so the test does not depend on the camera seeing anything
    private volatile float testVF, testVS;
    private volatile boolean testActive;
    private long lastTestNs;
    private float lastX, lastZ, lastHx, lastHz = -1;

    private Thread thread;
    private volatile boolean running;

    private long lastLog;

    // Diagnostics, ARCore thread only
    private long heldFwdNs, heldBackNs, heldLeftNs, heldRightNs;
    private long lostSinceNs;
    private long lastFrameNs, winMaxGapNs;
    private int winFrames, winLost, winJumps;
    private float winMinFwd = Float.MAX_VALUE, winMaxFwd = -Float.MAX_VALUE;
    private float winMinSide = Float.MAX_VALUE, winMaxSide = -Float.MAX_VALUE;
    private long lastPoseNs;

    public ArPosition(Context context, NvConnection conn, float thresholdM, boolean holdMode, MoveStick stickSink) {
        this.context = context;
        this.conn = conn;
        this.thresholdM = thresholdM;
        this.holdMode = holdMode;
        this.stickSink = stickSink;
        this.pipeline = new ArPipeline(thresholdM, holdMode, stickSink != null, new MoveOutput() {
            @Override
            public void key(char key, boolean down) {
                sendKey(key, down);
            }

            @Override
            public void stick(float x, float y) {
                ArPosition.this.stickSink.setStick(x, y);
            }
        });
    }

    public boolean start() {
        if (Build.VERSION.SDK_INT < 24) {
            return false;
        }
        running = true;
        thread = new Thread(this::run, "DeckVR-ArPosition");
        thread.start();
        return true;
    }

    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(2000);
            } catch (InterruptedException ignored) {
            }
            thread = null;
        }
    }

    public boolean isWalking() {
        return pipeline.isWalking();
    }

    // Broadcast test: fake speed (m/s) forward and to the right; 0,0 stops the growth (the displacement stays)
    public void setTestVelocity(float forwardMps, float sideMps) {
        testVF = forwardMps;
        testVS = sideMps;
        testActive = true;
    }

    // The next reading becomes the origin
    public void recenter() {
        pipeline.requestRecenter();
    }

    private void run() {
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        Session session = null;
        try {
            int[] version = new int[2];
            EGL14.eglInitialize(display, version, 0, version, 1);
            int[] attribs = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] num = new int[1];
            EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0);
            eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                    new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
            EGL14.eglMakeCurrent(display, surface, surface, eglContext);

            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0]);

            session = new Session(context);

            // Lowest resolution at 30 fps: only the position matters, and this saves heat
            CameraConfigFilter filter = new CameraConfigFilter(session)
                    .setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30));
            List<CameraConfig> cameraConfigs = session.getSupportedCameraConfigs(filter);
            CameraConfig smallest = null;
            for (CameraConfig c : cameraConfigs) {
                if (smallest == null || c.getImageSize().getWidth() * c.getImageSize().getHeight()
                        < smallest.getImageSize().getWidth() * smallest.getImageSize().getHeight()) {
                    smallest = c;
                }
            }
            if (smallest != null) {
                session.setCameraConfig(smallest);
            }

            Config config = new Config(session);
            config.setPlaneFindingMode(Config.PlaneFindingMode.DISABLED);
            config.setLightEstimationMode(Config.LightEstimationMode.DISABLED);
            config.setUpdateMode(Config.UpdateMode.BLOCKING);
            session.configure(config);
            session.setCameraTextureName(tex[0]);
            session.setDisplayGeometry(Surface.ROTATION_90, 1, 1);
            session.resume();
            VrLog.i("AR", "started mode=%s threshold=%.0fcm side=%.0fcm resolution=%s",
                    stickSink != null ? "analog" : holdMode ? "joystick(holds while away from the origin)" : "only_while_moving",
                    thresholdM * 100, thresholdM * ArPipeline.SIDE_FACTOR * 100,
                    smallest != null ? smallest.getImageSize().getWidth() + "x" + smallest.getImageSize().getHeight() : "?");

            TrackingState last = null;
            while (running) {
                Frame frame = session.update();
                long frameNs = System.nanoTime();
                if (lastFrameNs != 0) {
                    winMaxGapNs = Math.max(winMaxGapNs, frameNs - lastFrameNs);
                }
                lastFrameNs = frameNs;
                winFrames++;
                com.google.ar.core.Camera camera = frame.getCamera();
                TrackingState state = camera.getTrackingState();
                boolean tracking = state == TrackingState.TRACKING;
                if (state != last) {
                    VrLog.w("AR", "tracking %s reason=%s", state, camera.getTrackingFailureReason());
                    if (tracking) {
                        if (lostSinceNs != 0) {
                            VrLog.i("AR", "tracking back after %.1fs", (frameNs - lostSinceNs) / 1e9);
                        }
                        lostSinceNs = 0;
                    }
                    else if (lostSinceNs == 0) {
                        lostSinceNs = frameNs;
                        winLost++;
                    }
                    last = state;
                }

                float x, z, hx, hz;
                if (tracking) {
                    Pose pose = camera.getPose();
                    x = pose.tx();
                    z = pose.tz();
                    float[] zAxis = pose.getZAxis(); // points out of the screen; the gaze is -Z
                    hx = -zAxis[0];
                    hz = -zAxis[2];
                    lastX = x;
                    lastZ = z;
                    lastHx = hx;
                    lastHz = hz;
                }
                else {
                    x = lastX;
                    z = lastZ;
                    hx = lastHx;
                    hz = lastHz;
                }
                VrTrace.ar(frameNs, x, z, hx, hz, tracking);
                // In a test, lost tracking is ignored (the camera may be facing an empty table)
                boolean effective = tracking || testActive;

                if (lastTestNs != 0 && testActive) {
                    float dt = (frameNs - lastTestNs) / 1e9f;
                    pipeline.testForward += testVF * dt;
                    pipeline.testSide += testVS * dt;
                }
                lastTestNs = frameNs;

                pipeline.onFrame(frameNs, x, z, hx, hz, effective);
                if (!effective) {
                    logWindow();
                    continue;
                }
                if (pipeline.recentered) {
                    // How far the old origin was from where the head ended up: it is the drift (or the real displacement)
                    VrLog.i("AR", "recentered offset_before forward=%+.2fm side=%+.2fm",
                            pipeline.recenterBeforeForward, pipeline.recenterBeforeSide);
                }
                if (pipeline.poseJumped) {
                    winJumps++;
                    VrLog.w("AR", "pose jump between frames (%.0fms): ignored, no key", (frameNs - lastPoseNs) / 1e6);
                }
                lastPoseNs = frameNs;

                winMinFwd = Math.min(winMinFwd, pipeline.curForward);
                winMaxFwd = Math.max(winMaxFwd, pipeline.curForward);
                winMinSide = Math.min(winMinSide, pipeline.curSide);
                winMaxSide = Math.max(winMaxSide, pipeline.curSide);
                logWindow();
            }
        } catch (Throwable t) {
            if (running) {
                Log.e(VrLog.TAG, "AR ARCore failed", t);
            }
        } finally {
            pipeline.releaseAll();
            if (session != null) {
                try {
                    session.close();
                } catch (Throwable ignored) {
                }
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (surface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, surface);
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, eglContext);
            }
            EGL14.eglTerminate(display);
        }
    }

    private void sendKey(char key, boolean down) {
        short code = key == 'W' ? KEY_W : key == 'S' ? KEY_S : key == 'A' ? KEY_A : KEY_D;
        conn.sendKeyboardInput(code, down ? KeyboardPacket.KEY_DOWN : KeyboardPacket.KEY_UP, (byte) 0, (byte) 0);
        long now = System.nanoTime();
        if (down) {
            setHeldSince(key, now);
            VrLog.i("AR", "%c down forward=%+.2fm side=%+.2fm", key, pipeline.curForward, pipeline.curSide);
        }
        else {
            VrLog.i("AR", "%c up forward=%+.2fm side=%+.2fm held=%.1fs", key, pipeline.curForward, pipeline.curSide,
                    (now - heldSince(key)) / 1e9);
        }
    }

    private long heldSince(char key) {
        return key == 'W' ? heldFwdNs : key == 'S' ? heldBackNs : key == 'A' ? heldLeftNs : heldRightNs;
    }

    private void setHeldSince(char key, long ns) {
        if (key == 'W') heldFwdNs = ns;
        else if (key == 'S') heldBackNs = ns;
        else if (key == 'A') heldLeftNs = ns;
        else heldRightNs = ns;
    }

    private void logWindow() {
        long now = System.currentTimeMillis();
        if (lastLog == 0) {
            lastLog = now;
            return;
        }
        if (now - lastLog < LOG_WINDOW_MS) {
            return;
        }
        VrLog.i("AR", "frames=%.0f/s gap_max=%.0fms forward=%+.2f..%+.2fm side=%+.2f..%+.2fm keys=%s%s%s%s analog=%+.2f,%+.2f losses=%d jumps=%d",
                winFrames * 1000.0 / (now - lastLog), winMaxGapNs / 1e6,
                winMinFwd == Float.MAX_VALUE ? 0f : winMinFwd, winMaxFwd == -Float.MAX_VALUE ? 0f : winMaxFwd,
                winMinSide == Float.MAX_VALUE ? 0f : winMinSide, winMaxSide == -Float.MAX_VALUE ? 0f : winMaxSide,
                pipeline.fwdHeld ? "W" : "", pipeline.backHeld ? "S" : "", pipeline.leftHeld ? "A" : "", pipeline.rightHeld ? "D" : "",
                pipeline.stickX, pipeline.stickY, winLost, winJumps);
        lastLog = now;
        winFrames = winLost = winJumps = 0;
        winMaxGapNs = 0;
        winMinFwd = winMinSide = Float.MAX_VALUE;
        winMaxFwd = winMaxSide = -Float.MAX_VALUE;
    }
}
