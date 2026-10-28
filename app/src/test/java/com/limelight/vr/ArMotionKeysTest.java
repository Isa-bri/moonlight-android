package com.limelight.vr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class ArMotionKeysTest {
    private static final long FRAME_NS = 33_333_333L; // 30 fps

    private ArMotionKeys keys;
    private long t;

    @Before
    public void setUp() {
        keys = new ArMotionKeys(0.10f, 2f, 500_000_000L);
        t = 1_000_000_000L;
    }

    private void frame(float forward, float side) {
        t += FRAME_NS;
        keys.update(forward, side, t);
    }

    @Test
    public void standingStillPressesNothing() {
        for (int i = 0; i < 90; i++) frame(0.01f * (i % 2), 0.01f * (i % 3)); // sway of ~1-2 cm
        assertFalse(keys.w || keys.s || keys.a || keys.d);
    }

    @Test
    public void walkingForwardPressesWAndStoppingReleases() {
        float f = 0;
        for (int i = 0; i < 30; i++) { // 1 s at 0.6 m/s
            f += 0.02f;
            frame(f, 0);
        }
        assertTrue(keys.w);
        assertFalse(keys.s);
        for (int i = 0; i < 30; i++) frame(f, 0); // stopped for 1 s, far from the origin
        assertFalse("stopped: W must release even when displaced from the origin", keys.w);
    }

    @Test
    public void walkingBackPressesS() {
        float f = 0;
        for (int i = 0; i < 30; i++) {
            f -= 0.02f;
            frame(f, 0);
        }
        assertTrue(keys.s);
        assertFalse(keys.w);
    }

    @Test
    public void sidestepNeedsDoubledThreshold() {
        float x = 0;
        for (int i = 0; i < 30; i++) { // 0.6 m/s to the right: 0.3 m in 0.5 s, above 20 cm
            x += 0.02f;
            frame(0, x);
        }
        assertTrue(keys.d);
        assertFalse(keys.a);
        keys.reset();
        x = 0;
        for (int i = 0; i < 30; i++) { // short slide of 3 cm/frame... 0.05 m/s: does not count
            x -= 0.0015f;
            frame(0, x);
        }
        assertFalse(keys.a);
    }

    @Test
    public void smallLateralSwayDoesNotBecomeSide() {
        for (int i = 0; i < 60; i++) frame(0, (i % 10 < 5 ? 1 : -1) * 0.06f); // ±6 cm, below 20 cm
        assertFalse(keys.a || keys.d);
    }

    @Test
    public void resetClearsKeysAndHistory() {
        float f = 0;
        for (int i = 0; i < 30; i++) {
            f += 0.02f;
            frame(f, 0);
        }
        assertTrue(keys.w);
        keys.reset();
        assertFalse(keys.w);
        frame(5f, 5f); // origin changed: the first new sample cannot become a movement "jump"
        assertEquals(0f, keys.lastForwardDelta, 0f);
        assertFalse(keys.w || keys.d);
    }

    @Test
    public void poseJumpDoesNotBecomeKeyPress() {
        // Reproduces what the 2026-10-27 logs showed: standing still, the pose jumps ~0.3 m (tracking recovery)
        // and then comes back all at once; none of that is a person moving
        for (int i = 0; i < 30; i++) frame(0.03f, 0f);
        frame(0.31f, -0.21f);
        assertTrue(keys.jumped);
        assertFalse(keys.w || keys.s || keys.a || keys.d);
        for (int i = 0; i < 30; i++) {
            frame(0.31f, -0.21f);
            assertFalse(keys.w || keys.a);
        }
        frame(0.03f, 0f); // comes back all at once
        assertTrue(keys.jumped);
        assertFalse(keys.w || keys.s || keys.a || keys.d);
        for (int i = 0; i < 30; i++) frame(0.03f, 0f);
        assertFalse(keys.w || keys.s || keys.a || keys.d);
    }

    @Test
    public void normalFastStepIsNotMistakenForJump() {
        float f = 0;
        for (int i = 0; i < 30; i++) { // 1.5 m/s: 5 cm per frame, well below the 25 cm jump
            f += 0.05f;
            frame(f, 0);
            assertFalse(keys.jumped);
        }
        assertTrue(keys.w);
    }

    @Test
    public void sidestepDoesNotTriggerWOrS() {
        // Clean sidestep as in the logs (forward varies ±2 cm): only D
        float x = 0;
        for (int i = 0; i < 30; i++) {
            x += 0.02f;
            frame(0.02f * (i % 2 == 0 ? 1 : -1), x);
        }
        assertTrue(keys.d);
        assertFalse(keys.w || keys.s);
    }
}
