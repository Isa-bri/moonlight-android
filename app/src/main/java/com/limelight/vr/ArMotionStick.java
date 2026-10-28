package com.limelight.vr;

// Real walking through ARCore as a controller's (left) analog stick, for games that only accept an analog stick (no WASD):
// direction and intensity come from the speed over the last `windowNs` (no Android, testable on the JVM).
//
// - Deadzone: movement in the window below `deadzoneM` does not move the stick (body sway, tracking jitter).
// - Above it, the intensity rises linearly to 1 at `fullM` meters in the window (default: 4x the deadzone, ~0.8 m/s).
// - Multidirectional: the (side, forward) vector keeps its angle; a diagonal step becomes a diagonal on the stick.
// - Light smoothing (rises and falls in ~3 frames) so the stick does not jitter; stopping releases quickly.
// - A pose jump (see ArMotionKeys.JUMP_M) clears the history and zeroes the stick.
//
// Positive `forward` = ahead, positive `side` = right. Output: x (right +) and y (forward +), each in [-1, 1].
public class ArMotionStick {
    private static final int CAPACITY = 128;
    private static final float SMOOTH_UP = 0.5f;   // fraction of the way covered per frame when rising
    private static final float SMOOTH_DOWN = 0.7f; // when falling (stopping has to be fast)

    private final float deadzone;
    private final float full;
    private final long windowNs;

    private final long[] times = new long[CAPACITY];
    private final float[] forwards = new float[CAPACITY];
    private final float[] sides = new float[CAPACITY];
    private int head, size;

    public float x, y;          // analog stick (already smoothed)
    public float magnitude;     // |(x, y)| from 0 to 1
    public boolean jumped;
    public float lastForwardDelta, lastSideDelta;

    public ArMotionStick(float deadzoneM, float fullM, long windowNs) {
        this.deadzone = deadzoneM;
        this.full = Math.max(fullM, deadzoneM + 0.01f);
        this.windowNs = windowNs;
    }

    public void reset() {
        size = 0;
        head = 0;
        x = y = magnitude = 0;
    }

    public void update(float forward, float side, long nowNs) {
        jumped = false;
        if (size > 0) {
            int last = (head + size - 1) % CAPACITY;
            if (Math.hypot(forward - forwards[last], side - sides[last]) > ArMotionKeys.JUMP_M) {
                jumped = true;
                reset();
            }
        }
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
        while (size > 1 && times[(head + 1) % CAPACITY] <= nowNs - windowNs) {
            head = (head + 1) % CAPACITY;
            size--;
        }
        lastForwardDelta = forward - forwards[head];
        lastSideDelta = side - sides[head];

        float dist = (float) Math.hypot(lastSideDelta, lastForwardDelta);
        float target = dist <= deadzone ? 0f : Math.min(1f, (dist - deadzone) / (full - deadzone));
        float targetX = dist > 0 ? lastSideDelta / dist * target : 0f;
        float targetY = dist > 0 ? lastForwardDelta / dist * target : 0f;

        x = smooth(x, targetX);
        y = smooth(y, targetY);
        magnitude = (float) Math.min(1.0, Math.hypot(x, y));
    }

    private static float smooth(float current, float target) {
        // Falling = the magnitude decreases (or the sign flips): follows faster than when rising
        float k = Math.abs(target) < Math.abs(current) ? SMOOTH_DOWN : SMOOTH_UP;
        return current + (target - current) * k;
    }
}
