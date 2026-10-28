package com.limelight.vr;

// Step detector without Android (testable on the JVM): receives accelerometer samples and says when a step happened.
//
// Gravity is estimated by a low-pass filter; a peak of |a| above it (`threshold`), at least `minIntervalNs` after the
// previous step, counts as a step. Samples above 60% of the threshold form a "bump": if none of them became a step,
// it is a near step (missed because of sensitivity, or too close to the previous step).
//
// Walking confirmation: an isolated step (the phone being bumped as it is picked up or put down) does not count. Walking is
// only confirmed on the second step, as long as it comes within `armWindowNs` of the first; after that each step inside
// that window keeps the confirmation, and a pause longer than the window resets everything.
public class StepDetector {
    public static final float NEAR_MISS_FRACTION = 0.6f;

    private final float threshold;
    private final long minIntervalNs;
    private final long armWindowNs;

    private float gravity = 9.80665f;
    private long lastStepNs;
    private boolean confirmed;
    private boolean inBump, bumpStepped, bumpTooSoon;
    private float bumpPeak;

    // Result of the last sample
    public float excess;            // |a| above gravity
    public long sinceLastStepMs;    // only valid when the sample was a step; -1 on the first
    public boolean bumpEnded;       // a bump ended on this sample...
    public boolean bumpWasMiss;     // ...and did not become a step (near step)
    public float missPeak;          // peak of the near step
    public boolean missTooSoon;     // near step because it is close to the previous step
    public boolean walking;         // walking is confirmed (holds after the second consecutive step)

    public StepDetector(float threshold, long minIntervalNs, long armWindowNs) {
        this.threshold = threshold;
        this.minIntervalNs = minIntervalNs;
        this.armWindowNs = armWindowNs;
    }

    public float gravity() {
        return gravity;
    }

    // true if this sample is a step
    public boolean onSample(float x, float y, float z, long timestampNs) {
        float mag = (float) Math.sqrt(x * x + y * y + z * z);
        gravity += 0.05f * (mag - gravity);
        excess = mag - gravity;
        bumpEnded = bumpWasMiss = missTooSoon = false;

        boolean step = false;
        if (excess > threshold && timestampNs - lastStepNs > minIntervalNs) {
            step = true;
            sinceLastStepMs = lastStepNs == 0 ? -1 : (timestampNs - lastStepNs) / 1_000_000;
            // A second step inside the window confirms; a step after a long pause starts over (without confirming)
            confirmed = lastStepNs != 0 && timestampNs - lastStepNs <= armWindowNs;
            lastStepNs = timestampNs;
        }
        walking = confirmed && lastStepNs != 0 && timestampNs - lastStepNs <= armWindowNs;
        boolean tooSoon = !step && excess > threshold;

        float floor = threshold * NEAR_MISS_FRACTION;
        if (excess > floor) {
            if (!inBump) {
                inBump = true;
                bumpPeak = 0;
                bumpStepped = false;
                bumpTooSoon = false;
            }
            bumpPeak = Math.max(bumpPeak, excess);
            bumpStepped |= step;
            bumpTooSoon |= tooSoon;
        }
        else if (inBump) {
            inBump = false;
            bumpEnded = true;
            if (!bumpStepped) {
                bumpWasMiss = true;
                missPeak = bumpPeak;
                missTooSoon = bumpTooSoon;
            }
        }
        return step;
    }
}
