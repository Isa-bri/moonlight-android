package com.limelight.vr;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;

// Inclinar a cabeça para o ombro (rolagem) segura A ou D: esquerda = A, direita = D. A rolagem não é usada
// para mirar, então não briga com a cabeça = câmera. Histerese de 5° para a tecla não ficar piscando.
//
// Diagnóstico (VrLog, linhas LADO): cada troca de tecla com a rolagem do momento e quanto tempo a anterior ficou
// segurada (troca em menos de 250 ms vira "TREME": limiar ou histerese apertados demais para o balanço da cabeça);
// por segundo, a rolagem mínima/máxima, que só aparece quando chegou a metade do limiar ou há tecla segurada.
public class HeadStrafe {
    private static final short KEY_A = (short) 0x8041;
    private static final short KEY_D = (short) 0x8044;
    private static final float HYSTERESIS_DEG = 5f;
    private static final long TICK_MS = 20;
    private static final long CHATTER_MS = 250;
    private static final long LOG_WINDOW_NS = 1_000_000_000L;

    private final NvConnection conn;
    private final HeadTracker tracker;
    private final float thresholdDeg;

    private HandlerThread thread;
    private Handler handler;
    private final float[] worldToEye = new float[16];
    private short held; // 0, KEY_A ou KEY_D

    // Diagnóstico, só na thread do tick
    private long heldSinceNs, lastChangeNs, winStartNs;
    private float winMinRoll = Float.MAX_VALUE, winMaxRoll = -Float.MAX_VALUE;
    private int winChanges;

    public HeadStrafe(Context context, NvConnection conn, float thresholdDeg) {
        this.conn = conn;
        this.tracker = new HeadTracker(context, "LADO");
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
        VrLog.i("LADO", "iniciado limiar=%.0f graus histerese=%.0f graus passo=%dms", thresholdDeg, HYSTERESIS_DEG, TICK_MS);
        return true;
    }

    public void stop() {
        tracker.stop();
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
            handler.post(() -> set((short) 0, 0f));
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
            set(want, roll);
            logRoll(roll);
        }
        if (handler != null) {
            handler.postDelayed(this::tick, TICK_MS);
        }
    }

    private void logRoll(float roll) {
        long now = System.nanoTime();
        if (winStartNs == 0) {
            winStartNs = now;
        }
        winMinRoll = Math.min(winMinRoll, roll);
        winMaxRoll = Math.max(winMaxRoll, roll);
        if (now - winStartNs >= LOG_WINDOW_NS) {
            // Ocioso e longe do limiar: nada a dizer
            if (held != 0 || Math.max(winMaxRoll, -winMinRoll) > thresholdDeg / 2) {
                VrLog.i("LADO", "rolagem min=%+.1f max=%+.1f limiar=%.0f trocas=%d tecla=%s",
                        winMinRoll, winMaxRoll, thresholdDeg, winChanges, name(held));
            }
            winStartNs = now;
            winMinRoll = Float.MAX_VALUE;
            winMaxRoll = -Float.MAX_VALUE;
            winChanges = 0;
        }
    }

    private static String name(short key) {
        return key == KEY_A ? "A" : key == KEY_D ? "D" : "-";
    }

    private void set(short key, float roll) {
        if (key == held) {
            return;
        }
        long now = System.nanoTime();
        boolean chatter = lastChangeNs != 0 && now - lastChangeNs < CHATTER_MS * 1_000_000L;
        VrLog.i("LADO", "%s -> %s rolagem=%+.1f segurou=%.2fs%s", name(held), name(key), roll,
                held != 0 ? (now - heldSinceNs) / 1e9 : 0.0, chatter ? " TREME" : "");
        winChanges++;
        lastChangeNs = now;
        heldSinceNs = now;
        if (held != 0) {
            conn.sendKeyboardInput(held, KeyboardPacket.KEY_UP, (byte) 0, (byte) 0);
        }
        if (key != 0) {
            conn.sendKeyboardInput(key, KeyboardPacket.KEY_DOWN, (byte) 0, (byte) 0);
        }
        held = key;
    }
}
