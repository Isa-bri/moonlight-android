package com.limelight.vr;

// Walking in place without Android (testable on the JVM and in replay): accelerometer samples come in with the sensor timestamp
// and the W key (or the analog stick forward) goes down and up based only on those timestamps: no Handler/postDelayed, so the
// replay reproduces exactly what runs on the phone. The release is checked on every sample (~100 Hz, so the precision is ~10 ms).
public class StepPipeline {
    private final StepDetector detector;
    private final long releaseNs;
    private final MoveOutput out;
    private final boolean analog;

    private boolean down;
    private long lastStepNs;
    public int walkSteps;
    public long walkStartNs, lastReleaseNs;
    public boolean pressedNow, releasedNow; // events of the last sample, for the caller's log

    public StepPipeline(float threshold, long minIntervalNs, long armWindowNs, long releaseNs, boolean analog, MoveOutput out) {
        this.detector = new StepDetector(threshold, minIntervalNs, armWindowNs);
        this.releaseNs = releaseNs;
        this.analog = analog;
        this.out = out;
    }

    public StepDetector detector() {
        return detector;
    }

    public boolean isWalking() {
        return down;
    }

    // true if this sample was a step
    public boolean onSample(float x, float y, float z, long tsNs) {
        pressedNow = releasedNow = false;
        boolean step = detector.onSample(x, y, z, tsNs);
        if (step) {
            lastStepNs = tsNs;
            if (detector.walking) {
                if (!down) {
                    down = true;
                    walkSteps = 0;
                    walkStartNs = tsNs;
                    pressedNow = true;
                    press();
                }
                walkSteps++;
            }
        }
        if (down && tsNs - lastStepNs > releaseNs) {
            release(tsNs);
        }
        return step;
    }

    // Shutdown: releases if pressed
    public void stop(long tsNs) {
        if (down) {
            release(tsNs);
        }
    }

    private void press() {
        if (analog) {
            out.stick(0f, 1f);
        }
        else {
            out.key('W', true);
        }
    }

    private void release(long tsNs) {
        down = false;
        releasedNow = true;
        lastReleaseNs = tsNs;
        if (analog) {
            out.stick(0f, 0f);
        }
        else {
            out.key('W', false);
        }
    }
}
