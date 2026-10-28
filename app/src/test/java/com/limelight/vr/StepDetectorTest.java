package com.limelight.vr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class StepDetectorTest {
    private static final long MS = 1_000_000L;
    private static final float G = 9.80665f;

    private StepDetector detector;
    private long t;

    @Before
    public void setUp() {
        detector = new StepDetector(2.0f, 280 * MS, 1200 * MS);
        t = 10_000 * MS;
        for (int i = 0; i < 200; i++) sample(G); // gravity settles
    }

    private boolean sample(float mag) {
        t += 10 * MS; // ~100 Hz
        return detector.onSample(0, 0, mag, t);
    }

    @Test
    public void peakAboveThresholdIsStep() {
        assertTrue(sample(G + 3f));
        assertEquals(3f, detector.excess, 0.3f);
    }

    @Test
    public void withinMinimumIntervalDoesNotCountAndBecomesNearStepCloseToPrevious() {
        assertTrue(sample(G + 3f));
        for (int i = 0; i < 5; i++) sample(G);
        assertFalse(sample(G + 3f)); // 60 ms later
        boolean saw = false;
        for (int i = 0; i < 10 && !saw; i++) {
            sample(G);
            saw = detector.bumpEnded && detector.bumpWasMiss && detector.missTooSoon;
        }
        assertTrue(saw);
    }

    @Test
    public void weakPeakBecomesNearStepBelowThreshold() {
        assertFalse(sample(G + 1.6f)); // 80% of the 2.0 threshold
        boolean seen = false;
        for (int i = 0; i < 10 && !seen; i++) {
            sample(G);
            if (detector.bumpEnded) {
                seen = true;
                assertTrue(detector.bumpWasMiss);
                assertFalse(detector.missTooSoon);
                assertEquals(1.6f, detector.missPeak, 0.2f);
            }
        }
        assertTrue(seen);
    }

    @Test
    public void smallNoiseDoesNothing() {
        for (int i = 0; i < 100; i++) {
            assertFalse(sample(G + (i % 2 == 0 ? 0.3f : -0.3f)));
            assertFalse(detector.bumpEnded);
        }
    }

    @Test
    public void spacedStepsAllCount() {
        int steps = 0;
        for (int i = 0; i < 5; i++) {
            if (sample(G + 3f)) steps++;
            for (int j = 0; j < 40; j++) sample(G); // 400 ms between steps
        }
        assertEquals(5, steps);
    }

    @Test
    public void isolatedStepDoesNotConfirmWalking() {
        assertTrue(sample(G + 3f));
        assertFalse(detector.walking);
        for (int i = 0; i < 150; i++) { // 1.5 s without another step
            sample(G);
            assertFalse(detector.walking);
        }
    }

    @Test
    public void secondConsecutiveStepConfirmsAndFollowingOnesKeep() {
        assertTrue(sample(G + 3f));
        assertFalse(detector.walking);
        for (int j = 0; j < 40; j++) sample(G);
        assertTrue(sample(G + 3f)); // 400 ms later
        assertTrue(detector.walking);
        for (int j = 0; j < 40; j++) sample(G);
        assertTrue(sample(G + 3f));
        assertTrue(detector.walking);
    }

    @Test
    public void longPauseUndoesTheConfirmation() {
        sample(G + 3f);
        for (int j = 0; j < 40; j++) sample(G);
        sample(G + 3f);
        assertTrue(detector.walking);
        for (int j = 0; j < 130; j++) sample(G); // 1.3 s standing still
        assertFalse(detector.walking);
        assertTrue(sample(G + 3f)); // a step after the pause starts over: isolated again
        assertFalse(detector.walking);
    }
}
