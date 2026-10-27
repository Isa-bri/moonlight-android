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
// Diagnóstico (VrLog, linhas AR): quadros/s e maior intervalo entre quadros (o ARCore a 30 fps; atraso aqui é atraso do
// andar), deslocamento frente/lado mínimo e máximo por segundo, cada tecla que desce/sobe com o deslocamento do momento
// e quanto tempo ficou segurada, perdas de rastreamento com a duração, saltos de pose entre quadros seguidos (reposição
// do ARCore, que dispara tecla falsa) e a deriva da origem a cada recentrada.
//
// O ARCore precisa de uma textura OES para a imagem da câmera, então esta classe tem o próprio contexto EGL
// (superfície 1x1) numa thread dedicada, sem relação com o StereoGLView.
public class ArPosition {
    private static final float JUMP_M = 0.25f;     // salto de pose entre dois quadros que não é movimento de gente
    private static final long LOG_WINDOW_MS = 1000;

    private static final short KEY_W = (short) 0x8057;
    private static final short KEY_A = (short) 0x8041;
    private static final short KEY_S = (short) 0x8053;
    private static final short KEY_D = (short) 0x8044;

    private static final float SIDE_FACTOR = 2f;
    private static final long MOTION_WINDOW_NS = 500_000_000L; // modo "só enquanto se move": deslocamento nos últimos 0,5 s

    private final Context context;
    private final NvConnection conn;
    private final float thresholdM;
    private final float releaseM;
    private final float sideThresholdM;
    private final float sideReleaseM;
    private final boolean holdMode; // true = joystick (segura enquanto longe da origem); false = só enquanto se move
    private final ArMotionKeys motion;
    private final MoveStick stickSink;     // não nulo = movimento vira analógico esquerdo em vez de W/A/S/D
    private final ArMotionStick stick;
    private float lastStickX, lastStickY;
    private volatile boolean stickWalking;
    private float lastStickLogX, lastStickLogY;

    // Só para testes por broadcast: soma ao deslocamento medido um deslocamento que cresce na velocidade pedida (m/s)
    private volatile float testVF, testVS;
    private float testF, testS;
    private long lastTestNs;

    private Thread thread;
    private volatile boolean running;
    private volatile boolean recenterRequested = true;

    // Só usados pela thread do ARCore
    private volatile boolean fwdHeld, backHeld, leftHeld, rightHeld;
    private long lastLog;

    // Diagnóstico, só na thread do ARCore
    private float curForward, curSide;
    private long heldFwdNs, heldBackNs, heldLeftNs, heldRightNs;
    private long lostSinceNs;
    private long lastFrameNs, winMaxGapNs;
    private int winFrames, winLost, winJumps;
    private float winMinFwd = Float.MAX_VALUE, winMaxFwd = -Float.MAX_VALUE;
    private float winMinSide = Float.MAX_VALUE, winMaxSide = -Float.MAX_VALUE;
    private float prevX, prevZ;
    private boolean hasPrevPose;
    private long lastPoseNs;

    public ArPosition(Context context, NvConnection conn, float thresholdM, boolean holdMode, MoveStick stickSink) {
        this.context = context;
        this.conn = conn;
        this.holdMode = holdMode;
        this.stickSink = stickSink;
        this.stick = new ArMotionStick(thresholdM, thresholdM * 4f, MOTION_WINDOW_NS);
        this.motion = new ArMotionKeys(thresholdM, SIDE_FACTOR, MOTION_WINDOW_NS);
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
        return fwdHeld || backHeld || leftHeld || rightHeld || stickWalking;
    }

    // Teste por broadcast: velocidade falsa (m/s) para a frente e para a direita; 0,0 para de crescer (o deslocamento fica)
    public void setTestVelocity(float forwardMps, float sideMps) {
        testVF = forwardMps;
        testVS = sideMps;
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
            VrLog.i("AR", "iniciado modo=%s limiar=%.0fcm lado=%.0fcm soltura=%.0f%% resolucao=%s",
                    stickSink != null ? "analogico" : holdMode ? "joystick(segura longe da origem)" : "so_enquanto_se_move", thresholdM * 100, sideThresholdM * 100, releaseM / thresholdM * 100,
                    smallest != null ? smallest.getImageSize().getWidth() + "x" + smallest.getImageSize().getHeight() : "?");

            float originX = 0, originZ = 0;
            float fwdX = 0, fwdZ = -1;       // olhar atual
            float bodyX = 0, bodyZ = -1;     // frente do corpo, fixada na origem
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
                if (state != last) {
                    VrLog.w("AR", "rastreamento %s motivo=%s", state, camera.getTrackingFailureReason());
                    if (state == TrackingState.TRACKING) {
                        if (lostSinceNs != 0) {
                            VrLog.i("AR", "rastreamento voltou apos %.1fs", (frameNs - lostSinceNs) / 1e9);
                        }
                        lostSinceNs = 0;
                    }
                    else if (lostSinceNs == 0) {
                        lostSinceNs = frameNs;
                        winLost++;
                    }
                    last = state;
                }
                if (state != TrackingState.TRACKING) {
                    hasPrevPose = false;
                    motion.reset();
                    releaseAll("rastreamento perdido");
                    logWindow();
                    continue;
                }

                Pose pose = camera.getPose();
                float x = pose.tx();
                float z = pose.tz();
                boolean poseJumped = false;
                if (hasPrevPose && Math.hypot(x - prevX, z - prevZ) > JUMP_M) {
                    winJumps++;
                    poseJumped = true;
                    VrLog.w("AR", "salto de pose %.2fm entre quadros (%.0fms): ignorado, sem tecla", Math.hypot(x - prevX, z - prevZ),
                            (frameNs - lastPoseNs) / 1e6);
                    // A origem acompanha o salto, então o deslocamento fica contínuo (vale para o modo joystick)
                    originX += x - prevX;
                    originZ += z - prevZ;
                }
                prevX = x;
                prevZ = z;
                lastPoseNs = frameNs;
                hasPrevPose = true;
                float[] zAxis = pose.getZAxis(); // sai da tela; o olhar é -Z
                float hx = -zAxis[0], hz = -zAxis[2];
                float hLen = (float) Math.hypot(hx, hz);
                if (hLen > 0.3f) { // olhando quase reto para cima/baixo: mantém a última frente
                    fwdX = hx / hLen;
                    fwdZ = hz / hLen;
                }

                if (recenterRequested) {
                    recenterRequested = false;
                    // Quanto a origem antiga estava longe de onde a cabeça foi parar: é a deriva (ou o deslocamento real)
                    // que a recentrada corrige; um S ou A preso costuma vir daqui
                    VrLog.i("AR", "recentrou deslocamento_antes frente=%+.2fm lado=%+.2fm", curForward, curSide);
                    originX = x;
                    originZ = z;
                    bodyX = fwdX;
                    bodyZ = fwdZ;
                    motion.reset();
                    testF = testS = 0;
                    releaseAll("recentrou");
                }

                float dx = x - originX, dz = z - originZ;
                // direita = (-bodyZ, bodyX) no sistema do ARCore (Y para cima, olhando para -Z)
                float forward = dx * bodyX + dz * bodyZ;
                float side = dx * -bodyZ + dz * bodyX;
                if (lastTestNs != 0) {
                    float dtTest = (frameNs - lastTestNs) / 1e9f;
                    testF += testVF * dtTest;
                    testS += testVS * dtTest;
                }
                lastTestNs = frameNs;
                forward += testF;
                side += testS;

                curForward = forward;
                curSide = side;
                winMinFwd = Math.min(winMinFwd, forward);
                winMaxFwd = Math.max(winMaxFwd, forward);
                winMinSide = Math.min(winMinSide, side);
                winMaxSide = Math.max(winMaxSide, side);

                if (stickSink != null) {
                    if (poseJumped) {
                        stick.reset();
                    }
                    else {
                        stick.update(forward, side, frameNs);
                    }
                    sendStick(stick.x, stick.y);
                    logWindow();
                    continue;
                }

                boolean wantFwd, wantBack, wantRight, wantLeft;
                if (poseJumped) {
                    motion.reset();
                    wantFwd = wantBack = wantRight = wantLeft = false;
                }
                else if (holdMode) {
                    wantFwd = hold(fwdHeld, forward);
                    wantBack = hold(backHeld, -forward);
                    wantRight = holdSide(rightHeld, side);
                    wantLeft = holdSide(leftHeld, -side);
                }
                else {
                    motion.update(forward, side, frameNs);
                    wantFwd = motion.w;
                    wantBack = motion.s;
                    wantRight = motion.d;
                    wantLeft = motion.a;
                }
                fwdHeld = key(KEY_W, fwdHeld, wantFwd);
                backHeld = key(KEY_S, backHeld, wantBack);
                rightHeld = key(KEY_D, rightHeld, wantRight);
                leftHeld = key(KEY_A, leftHeld, wantLeft);

                logWindow();
            }
        } catch (Throwable t) {
            if (running) {
                Log.e(VrLog.TAG, "AR ARCore falhou", t);
            }
        } finally {
            releaseAll("encerrando");
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
            long now = System.nanoTime();
            if (want) {
                setHeldSince(code, now);
                VrLog.i("AR", "%s desceu frente=%+.2fm lado=%+.2fm", keyName(code), curForward, curSide);
            }
            else {
                VrLog.i("AR", "%s subiu frente=%+.2fm lado=%+.2fm segurou=%.1fs", keyName(code), curForward, curSide,
                        (now - heldSince(code)) / 1e9);
            }
        }
        return want;
    }

    // Manda o analógico quando muda o bastante (1/256) e sempre que volta a zero
    private void sendStick(float x, float y) {
        boolean zero = x == 0 && y == 0;
        if (zero ? (lastStickX == 0 && lastStickY == 0)
                : Math.abs(x - lastStickX) < 1 / 256f && Math.abs(y - lastStickY) < 1 / 256f) {
            return;
        }
        lastStickX = x;
        lastStickY = y;
        stickWalking = Math.hypot(x, y) > 0.1;
        stickSink.setStick(x, y);
    }

    private void releaseAll(String why) {
        if (stickSink != null) {
            if (lastStickX != 0 || lastStickY != 0) {
                VrLog.i("AR", "analogico zerado: %s", why);
            }
            stick.reset();
            sendStick(0f, 0f);
            return;
        }
        if (isWalking()) {
            VrLog.i("AR", "soltando tudo: %s", why);
        }
        fwdHeld = key(KEY_W, fwdHeld, false);
        backHeld = key(KEY_S, backHeld, false);
        rightHeld = key(KEY_D, rightHeld, false);
        leftHeld = key(KEY_A, leftHeld, false);
    }

    private static String keyName(short code) {
        return code == KEY_W ? "W" : code == KEY_S ? "S" : code == KEY_A ? "A" : "D";
    }

    private long heldSince(short code) {
        return code == KEY_W ? heldFwdNs : code == KEY_S ? heldBackNs : code == KEY_A ? heldLeftNs : heldRightNs;
    }

    private void setHeldSince(short code, long ns) {
        if (code == KEY_W) heldFwdNs = ns;
        else if (code == KEY_S) heldBackNs = ns;
        else if (code == KEY_A) heldLeftNs = ns;
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
        VrLog.i("AR", "quadros=%.0f/s gap_max=%.0fms frente=%+.2f..%+.2fm lado=%+.2f..%+.2fm teclas=%s%s%s%s analogico=%+.2f,%+.2f perdas=%d saltos=%d",
                winFrames * 1000.0 / (now - lastLog), winMaxGapNs / 1e6,
                winMinFwd == Float.MAX_VALUE ? 0f : winMinFwd, winMaxFwd == -Float.MAX_VALUE ? 0f : winMaxFwd,
                winMinSide == Float.MAX_VALUE ? 0f : winMinSide, winMaxSide == -Float.MAX_VALUE ? 0f : winMaxSide,
                fwdHeld ? "W" : "", backHeld ? "S" : "", leftHeld ? "A" : "", rightHeld ? "D" : "",
                lastStickX, lastStickY, winLost, winJumps);
        lastLog = now;
        winFrames = winLost = winJumps = 0;
        winMaxGapNs = 0;
        winMinFwd = winMinSide = Float.MAX_VALUE;
        winMaxFwd = winMaxSide = -Float.MAX_VALUE;
    }
}
