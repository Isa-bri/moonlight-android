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
public class HeadMouse {
    private static final long PERIOD_MS = 4; // ~250 Hz, acima do sensor (~200 Hz)
    private static final float DEADZONE_DEG = 0.005f;

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

        if (!hasPrev) {
            prevYaw = yaw;
            prevPitch = pitch;
            hasPrev = true;
            return;
        }

        float dYaw = yaw - prevYaw;
        if (dYaw > 180f) dYaw -= 360f;
        else if (dYaw < -180f) dYaw += 360f;
        float dPitch = pitch - prevPitch;
        prevYaw = yaw;
        prevPitch = pitch;

        if (Math.abs(dYaw) < DEADZONE_DEG) dYaw = 0;
        if (Math.abs(dPitch) < DEADZONE_DEG) dPitch = 0;

        // Guarda a fração que não cabe num inteiro para não perder movimento lento
        remX += dYaw * countsPerDegree;
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
