package com.limelight.vr;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;

// Tilting the head toward the shoulder (roll) holds A or D: left = A, right = D. Roll is not used
// for aiming, so it does not conflict with head = camera. 5° of hysteresis so the key does not flicker.
//
// Diagnostics (VrLog, STRAFE lines): each key change with the roll at that moment and how long the previous one was
// held (a change in under 250 ms is flagged "JITTER": threshold or hysteresis too tight for the head's sway);
// once per second, the minimum/maximum roll, which only appears when it reached half the threshold or a key is held.
public class HeadStrafe {
    static final short KEY_A = (short) 0x8041;
    static final short KEY_D = (short) 0x8044;
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
    private short held; // 0, KEY_A or KEY_D

    // Diagnostics, tick thread only
    private long heldSinceNs, lastChangeNs, winStartNs;
    private float winMinRoll = Float.MAX_VALUE, winMaxRoll = -Float.MAX_VALUE;
    private int winChanges;

    public HeadStrafe(Context context, NvConnection conn, float thresholdDeg) {
        this.conn = conn;
        this.tracker = new HeadTracker(context, "STRAFE");
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
        VrLog.i("STRAFE", "started threshold=%.0f deg hysteresis=%.0f deg step=%dms", thresholdDeg, HYSTERESIS_DEG, TICK_MS);
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
            // The eye's "right" axis in the world = row 0 of worldToEye; its height is the sine of the roll
            // (positive = right ear up = head tilted to the left)
            float roll = (float) Math.toDegrees(Math.asin(Math.max(-1f, Math.min(1f, worldToEye[8]))));
            short want = nextKey(roll, held, thresholdDeg, HYSTERESIS_DEG);
            set(want, roll);
            logRoll(roll);
        }
        if (handler != null) {
            handler.postDelayed(this::tick, TICK_MS);
        }
    }

    // Key to hold for this roll (positive = tilted to the left): crossing the threshold turns it on; it only releases
    // after coming back `hysteresis` degrees inside it
    static short nextKey(float roll, short held, float thresholdDeg, float hysteresis) {
        float release = thresholdDeg - hysteresis;
        if (roll > thresholdDeg) return KEY_A;
        if (roll < -thresholdDeg) return KEY_D;
        if (held == KEY_A && roll < release) return 0;
        if (held == KEY_D && roll > -release) return 0;
        return held;
    }

    private void logRoll(float roll) {
        long now = System.nanoTime();
        if (winStartNs == 0) {
            winStartNs = now;
        }
        winMinRoll = Math.min(winMinRoll, roll);
        winMaxRoll = Math.max(winMaxRoll, roll);
        if (now - winStartNs >= LOG_WINDOW_NS) {
            // Idle and far from the threshold: nothing to say
            if (held != 0 || Math.max(winMaxRoll, -winMinRoll) > thresholdDeg / 2) {
                VrLog.i("STRAFE", "roll min=%+.1f max=%+.1f threshold=%.0f switches=%d key=%s",
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
        VrLog.i("STRAFE", "%s -> %s roll=%+.1f held=%.2fs%s", name(held), name(key), roll,
                held != 0 ? (now - heldSinceNs) / 1e9 : 0.0, chatter ? " JITTER" : "");
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
