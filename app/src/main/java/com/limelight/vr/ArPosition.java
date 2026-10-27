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

// Posição da cabeça pelo ARCore (câmera traseira + sensores) como joystick: o deslocamento do ponto de origem,
// medido nos eixos do corpo (a direção do olhar quando a origem foi definida), segura W/S (frente/trás) e A/D (direita/esquerda). Dar um passo para trás segura S.
// Voltar ao ponto de origem solta as teclas. recenter() move a origem para onde a cabeça está agora.
//
// O ARCore precisa de uma textura OES para a imagem da câmera, então esta classe tem o próprio contexto EGL
// (superfície 1x1) numa thread dedicada, sem relação com o StereoGLView.
public class ArPosition {
    private static final String TAG = "DeckVR-ArPosition";

    private static final short KEY_W = (short) 0x8057;
    private static final short KEY_A = (short) 0x8041;
    private static final short KEY_S = (short) 0x8053;
    private static final short KEY_D = (short) 0x8044;

    private static final float SIDE_FACTOR = 2f;

    private final Context context;
    private final NvConnection conn;
    private final float thresholdM;
    private final float releaseM;
    private final float sideThresholdM;
    private final float sideReleaseM;

    private Thread thread;
    private volatile boolean running;
    private volatile boolean recenterRequested = true;

    // Só usados pela thread do ARCore
    private volatile boolean fwdHeld, backHeld, leftHeld, rightHeld;
    private long lastLog;

    public ArPosition(Context context, NvConnection conn, float thresholdM) {
        this.context = context;
        this.conn = conn;
        this.thresholdM = thresholdM;
        this.releaseM = thresholdM * 0.6f; // histerese
        // Os lados pedem mais deslocamento: a cabeça balança para os lados sem querer
        this.sideThresholdM = thresholdM * SIDE_FACTOR;
        this.sideReleaseM = sideThresholdM * 0.6f;
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
        return fwdHeld || backHeld || leftHeld || rightHeld;
    }

    // A próxima leitura vira a origem
    public void recenter() {
        recenterRequested = true;
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

            // Menor resolução a 30 fps: só a posição interessa, e isso poupa calor
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
            Log.i(TAG, "ARCore iniciado");

            float originX = 0, originZ = 0;
            float fwdX = 0, fwdZ = -1;       // olhar atual
            float bodyX = 0, bodyZ = -1;     // frente do corpo, fixada na origem
            TrackingState last = null;
            while (running) {
                Frame frame = session.update();
                com.google.ar.core.Camera camera = frame.getCamera();
                TrackingState state = camera.getTrackingState();
                if (state != last) {
                    Log.i(TAG, "rastreamento: " + state + " " + camera.getTrackingFailureReason());
                    last = state;
                }
                if (state != TrackingState.TRACKING) {
                    releaseAll();
                    continue;
                }

                Pose pose = camera.getPose();
                float x = pose.tx();
                float z = pose.tz();
                float[] zAxis = pose.getZAxis(); // sai da tela; o olhar é -Z
                float hx = -zAxis[0], hz = -zAxis[2];
                float hLen = (float) Math.hypot(hx, hz);
                if (hLen > 0.3f) { // olhando quase reto para cima/baixo: mantém a última frente
                    fwdX = hx / hLen;
                    fwdZ = hz / hLen;
                }

                if (recenterRequested) {
                    recenterRequested = false;
                    originX = x;
                    originZ = z;
                    bodyX = fwdX;
                    bodyZ = fwdZ;
                    releaseAll();
                }

                float dx = x - originX, dz = z - originZ;
                // direita = (-bodyZ, bodyX) no sistema do ARCore (Y para cima, olhando para -Z)
                float forward = dx * bodyX + dz * bodyZ;
                float side = dx * -bodyZ + dz * bodyX;

                boolean wantFwd = hold(fwdHeld, forward);
                boolean wantBack = hold(backHeld, -forward);
                boolean wantRight = holdSide(rightHeld, side);
                boolean wantLeft = holdSide(leftHeld, -side);
                fwdHeld = key(KEY_W, fwdHeld, wantFwd);
                backHeld = key(KEY_S, backHeld, wantBack);
                rightHeld = key(KEY_D, rightHeld, wantRight);
                leftHeld = key(KEY_A, leftHeld, wantLeft);

                long now = System.currentTimeMillis();
                if (now - lastLog > 1000) {
                    lastLog = now;
                    Log.i(TAG, String.format("frente %.2f m, lado %.2f m", forward, side));
                }
            }
        } catch (Throwable t) {
            if (running) {
                Log.e(TAG, "ARCore falhou", t);
            }
        } finally {
            releaseAll();
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

    // Liga acima do limiar; desliga só abaixo do limiar de soltura
    private boolean hold(boolean held, float offset) {
        return held ? offset > releaseM : offset > thresholdM;
    }

    private boolean holdSide(boolean held, float offset) {
        return held ? offset > sideReleaseM : offset > sideThresholdM;
    }

    private boolean key(short code, boolean was, boolean want) {
        if (want != was) {
            conn.sendKeyboardInput(code, want ? KeyboardPacket.KEY_DOWN : KeyboardPacket.KEY_UP, (byte) 0, (byte) 0);
        }
        return want;
    }

    private void releaseAll() {
        fwdHeld = key(KEY_W, fwdHeld, false);
        backHeld = key(KEY_S, backHeld, false);
        rightHeld = key(KEY_D, rightHeld, false);
        leftHeld = key(KEY_A, leftHeld, false);
    }
}
