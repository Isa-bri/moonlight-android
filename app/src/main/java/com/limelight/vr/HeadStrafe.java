package com.limelight.vr;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;

// Inclinar a cabeça para o ombro (rolagem) segura A ou D: esquerda = A, direita = D. A rolagem não é usada
// para mirar, então não briga com a cabeça = câmera. Histerese de 5° para a tecla não ficar piscando.
public class HeadStrafe {
    private static final short KEY_A = (short) 0x8041;
    private static final short KEY_D = (short) 0x8044;
    private static final float HYSTERESIS_DEG = 5f;
    private static final long TICK_MS = 20;

    private final NvConnection conn;
    private final HeadTracker tracker;
    private final float thresholdDeg;

    private HandlerThread thread;
    private Handler handler;
    private final float[] worldToEye = new float[16];
    private short held; // 0, KEY_A ou KEY_D

    public HeadStrafe(Context context, NvConnection conn, float thresholdDeg) {
        this.conn = conn;
        this.tracker = new HeadTracker(context);
        this.thresholdDeg = thresholdDeg;
    }

    public boolean start() {
        if (!tracker.start()) {
            return false;
        }
        thread = new HandlerThread("DeckVR-HeadStrafe");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::tick);
        return true;
    }

    public void stop() {
        tracker.stop();
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
            handler.post(() -> set((short) 0));
            thread.quitSafely();
            handler = null;
            thread = null;
        }
    }

    private void tick() {
        if (tracker.getWorldToEye(worldToEye)) {
            // Eixo "direita" do olho no mundo = linha 0 de worldToEye; sua altura é o seno da rolagem
            // (positivo = orelha direita para cima = cabeça inclinada para a esquerda)
            float roll = (float) Math.toDegrees(Math.asin(Math.max(-1f, Math.min(1f, worldToEye[8]))));
            short want = held;
            float release = thresholdDeg - HYSTERESIS_DEG;
            if (roll > thresholdDeg) want = KEY_A;
            else if (roll < -thresholdDeg) want = KEY_D;
            else if (held == KEY_A && roll < release) want = 0;
            else if (held == KEY_D && roll > -release) want = 0;
            set(want);
        }
        if (handler != null) {
            handler.postDelayed(this::tick, TICK_MS);
        }
    }

    private void set(short key) {
        if (key == held) {
            return;
        }
        if (held != 0) {
            conn.sendKeyboardInput(held, KeyboardPacket.KEY_UP, (byte) 0, (byte) 0);
        }
        if (key != 0) {
            conn.sendKeyboardInput(key, KeyboardPacket.KEY_DOWN, (byte) 0, (byte) 0);
        }
        held = key;
    }
}
