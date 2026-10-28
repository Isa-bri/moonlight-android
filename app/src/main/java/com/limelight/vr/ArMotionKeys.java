package com.limelight.vr;

// Real walking through ARCore, "only while moving" (no Android, testable on the JVM): looks at the displacement (forward, side)
// over the last `windowNs`; if the head moved more than the threshold in that window, the key is held; once it stops, the key
// is released within about one window.
// Standing still far from the starting point does not hold a key (unlike "joystick" mode, which measures the displacement from the origin).
//
// Positive `forward` = ahead, positive `side` = right (body axes, already computed by ArPosition). Sideways moves ask for
// `sideFactor` times the threshold (the head sways sideways unintentionally). Release at 60% of the threshold (hysteresis).
//
// Pose jumps: when tracking is recovered, ARCore may reposition the camera all at once (~0.3 m between two frames, which
// at 30 fps would be 9 m/s). That is not human movement: a jump larger than JUMP_M between consecutive samples clears the history
// and releases the keys, instead of turning into a W/S/A/D tap (seen in the 2026-10-27 logs: `jumps=1` and an S+D of ~0.4 s).
public class ArMotionKeys {
    private static final int CAPACITY = 128;
    private static final float RELEASE_FRACTION = 0.6f;
    public static final float JUMP_M = 0.25f; // displacement between two consecutive samples that is not human

    public boolean jumped; // the last sample was a pose jump

    private final float threshold;
    private final float sideThreshold;
    private final long windowNs;

    private final long[] times = new long[CAPACITY];
    private final float[] forwards = new float[CAPACITY];
    private final float[] sides = new float[CAPACITY];
    private int head, size;

    public boolean w, s, a, d;
    public float lastForwardDelta, lastSideDelta; // displacement over the window, for the log

    public ArMotionKeys(float thresholdM, float sideFactor, long windowNs) {
        this.threshold = thresholdM;
        this.sideThreshold = thresholdM * sideFactor;
        this.windowNs = windowNs;
    }

    // The origin changed (recenter) or tracking was lost: the history is no longer valid
    public void reset() {
        size = 0;
        head = 0;
        w = s = a = d = false;
    }

    public void update(float forward, float side, long nowNs) {
        jumped = false;
        if (size > 0) {
            int last = (head + size - 1) % CAPACITY;
            if (Math.hypot(forward - forwards[last], side - sides[last]) > JUMP_M) {
                jumped = true;
                reset();
            }
        }
        // Store the sample
        int idx = (head + size) % CAPACITY;
        if (size == CAPACITY) {
            head = (head + 1) % CAPACITY;
            idx = (head + size - 1) % CAPACITY;
        }
        else {
            size++;
        }
        times[idx] = nowNs;
        forwards[idx] = forward;
        sides[idx] = side;

        // Discard what left the window, but keep the oldest one inside it as the reference
        while (size > 1 && times[(head + 1) % CAPACITY] <= nowNs - windowNs) {
            head = (head + 1) % CAPACITY;
            size--;
        }
        lastForwardDelta = forward - forwards[head];
        lastSideDelta = side - sides[head];

        w = hold(w, lastForwardDelta, threshold);
        s = hold(s, -lastForwardDelta, threshold);
        d = hold(d, lastSideDelta, sideThreshold);
        a = hold(a, -lastSideDelta, sideThreshold);
    }

    private static boolean hold(boolean held, float delta, float limit) {
        return held ? delta > limit * RELEASE_FRACTION : delta > limit;
    }
}
