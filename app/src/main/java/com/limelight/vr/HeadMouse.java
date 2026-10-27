package com.limelight.vr;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import com.limelight.nvstream.NvConnection;

// Cabeça = câmera: transforma a rotação da cabeça (yaw/pitch do olhar) em movimento relativo de mouse.
//
// Usa a mesma orientação do HeadTracker, mas só os deltas entre leituras; o recentramento do
// HeadTracker (rotação em torno de Z) não altera yaw/pitch relativos. Girar para a direita move o mouse
// para a direita; olhar para cima move o mouse para cima (y positivo é para baixo).
//
// Giro na borda: passando de `edgeDeg` graus para um lado do ponto central (onde a cabeça estava ao começar ou ao soltar
// a embreagem), a câmera continua girando sozinha, mais rápido quanto mais longe, para virar além do alcance do pescoço.
//
// Zona morta vertical ao andar: enquanto andar (W/A/S/D segurado por outra classe) o pitch só passa depois de sair de
// uma faixa de `walkPitchBandDeg` graus em volta da última posição, o que esconde o balanço da cabeça a cada passo.
public class HeadMouse {
    public interface WalkSource {
        boolean isWalking();
    }

    private static final long PERIOD_MS = 4; // ~250 Hz, acima do sensor (~200 Hz)
    private static final float DEADZONE_DEG = 0.005f;
    private static final float EDGE_RAMP_DEG = 15f;       // da borda até a velocidade máxima
    private static final float EDGE_MAX_DEG_PER_S = 120f; // velocidade máxima do giro na borda

    private final HeadTracker tracker;
    private final NvConnection conn;
    private final float countsPerDegree;
    private final float pitchCountsPerDegree;

    private HandlerThread thread;
    private Handler handler;
    private volatile boolean running;
    private volatile boolean toggledPause;
    private volatile boolean clutch;

    private final float[] worldToEye = new float[16];
    private boolean hasPrev;
    private float prevYaw, prevPitch;
    private float remX, remY;
    private float centerYaw;
    private long prevNs;
    private float anchorPitch;
    private boolean wasWalking;

    private volatile float edgeDeg;          // 0 = desligado
    private volatile float walkPitchBandDeg; // 0 = desligado
    private volatile WalkSource walkSource;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            poll();
            handler.postDelayed(this, PERIOD_MS);
        }
    };

    public HeadMouse(Context context, NvConnection conn, float countsPerDegree, float pitchScale) {
        this.tracker = new HeadTracker(context);
        this.conn = conn;
        this.countsPerDegree = countsPerDegree;
        this.pitchCountsPerDegree = countsPerDegree * pitchScale;
    }

    public void setEdgeTurn(float edgeDeg) {
        this.edgeDeg = edgeDeg;
    }

    public void setWalkPitchBand(float bandDeg, WalkSource source) {
        this.walkPitchBandDeg = bandDeg;
        this.walkSource = source;
    }

    public boolean start() {
        if (!tracker.start()) {
            return false;
        }
        thread = new HandlerThread("DeckVR-HeadMouse", Process.THREAD_PRIORITY_URGENT_DISPLAY);
        thread.start();
        handler = new Handler(thread.getLooper());
        running = true;
        handler.post(tick);
        return true;
    }

    public void stop() {
        running = false;
        tracker.stop();
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
    }

    // Liga/desliga o envio; ao voltar, o movimento parte da pose atual (sem pulo)
    public boolean togglePause() {
        toggledPause = !toggledPause;
        return toggledPause;
    }

    // Embreagem: enquanto segurada, a cabeça gira sem mexer a câmera (como levantar o mouse da mesa)
    public void setClutch(boolean held) {
        clutch = held;
    }

    private void poll() {
        if (!tracker.getWorldToEye(worldToEye)) {
            return;
        }

        if (toggledPause || clutch) {
            hasPrev = false;
            return;
        }

        // Olhar no mundo = -(terceira coluna de eyeToWorld) = -(terceira linha de worldToEye)
        float gx = -worldToEye[2];
        float gy = -worldToEye[6];
        float gz = -worldToEye[10];
        float yaw = (float) Math.toDegrees(Math.atan2(gx, gy));
        float pitch = (float) Math.toDegrees(Math.asin(Math.max(-1f, Math.min(1f, gz))));

        long nowNs = System.nanoTime();
        if (!hasPrev) {
            prevYaw = yaw;
            prevPitch = pitch;
            centerYaw = yaw;
            anchorPitch = pitch;
            wasWalking = false;
            prevNs = nowNs;
            hasPrev = true;
            return;
        }
        float dt = Math.min((nowNs - prevNs) / 1e9f, 0.05f);
        prevNs = nowNs;

        float dYaw = yaw - prevYaw;
        if (dYaw > 180f) dYaw -= 360f;
        else if (dYaw < -180f) dYaw += 360f;
        float dPitch = pitch - prevPitch;
        prevYaw = yaw;
        prevPitch = pitch;

        // Ao andar, o pitch só segue quando sai da faixa em volta da âncora
        WalkSource source = walkSource;
        boolean walking = walkPitchBandDeg > 0 && source != null && source.isWalking();
        if (walking) {
            if (!wasWalking) {
                anchorPitch = pitch - dPitch;
            }
            float band = walkPitchBandDeg;
            float before = anchorPitch;
            if (pitch > anchorPitch + band) anchorPitch = pitch - band;
            else if (pitch < anchorPitch - band) anchorPitch = pitch + band;
            dPitch = anchorPitch - before;
        }
        wasWalking = walking;

        // Giro na borda: soma uma rotação contínua de yaw quando a cabeça passa do limite
        float edgeYaw = 0;
        float edge = edgeDeg;
        if (edge > 0) {
            float rel = yaw - centerYaw;
            if (rel > 180f) rel -= 360f;
            else if (rel < -180f) rel += 360f;
            float excess = Math.abs(rel) - edge;
            if (excess > 0) {
                float speed = Math.min(excess / EDGE_RAMP_DEG, 1f) * EDGE_MAX_DEG_PER_S;
                edgeYaw = Math.signum(rel) * speed * dt;
            }
        }

        if (Math.abs(dYaw) < DEADZONE_DEG) dYaw = 0;
        if (Math.abs(dPitch) < DEADZONE_DEG) dPitch = 0;

        // Guarda a fração que não cabe num inteiro para não perder movimento lento
        remX += (dYaw + edgeYaw) * countsPerDegree;
        remY += -dPitch * pitchCountsPerDegree;
        int mx = (int) remX;
        int my = (int) remY;
        remX -= mx;
        remY -= my;

        if (mx != 0 || my != 0) {
            conn.sendMouseMove((short) Math.max(-32768, Math.min(32767, mx)),
                    (short) Math.max(-32768, Math.min(32767, my)));
        }
    }
}
