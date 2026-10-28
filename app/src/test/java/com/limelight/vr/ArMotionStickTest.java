package com.limelight.vr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class ArMotionStickTest {
    private static final long FRAME_NS = 33_333_333L;

    private ArMotionStick stick;
    private long t;

    @Before
    public void setUp() {
        stick = new ArMotionStick(0.10f, 0.40f, 500_000_000L); // deadzone 10 cm and full at 40 cm, in 0.5 s
        t = 1_000_000_000L;
    }

    // Walks `frames` frames at velocity (forward, side) in m/s; returns the final position
    private float[] walk(float vForward, float vSide, int frames, float f0, float s0) {
        float f = f0, s = s0;
        for (int i = 0; i < frames; i++) {
            f += vForward * FRAME_NS / 1e9f;
            s += vSide * FRAME_NS / 1e9f;
            t += FRAME_NS;
            stick.update(f, s, t);
        }
        return new float[]{f, s};
    }

    @Test
    public void standingStillDoesNotMoveStick() {
        walk(0, 0, 60, 0, 0);
        assertEquals(0f, stick.magnitude, 0.001f);
        for (int i = 0; i < 60; i++) { // sway of 1 cm
            t += FRAME_NS;
            stick.update(0.01f * (i % 2), 0.01f * (i % 3), t);
        }
        assertEquals(0f, stick.magnitude, 0.001f);
    }

    @Test
    public void walkingFastForwardReachesNearMax() {
        walk(1.0f, 0, 45, 0, 0); // 1 m/s: 50 cm in the window, above the 40 cm
        assertEquals(1f, stick.y, 0.05f);
        assertEquals(0f, stick.x, 0.05f);
    }

    @Test
    public void intensityGrowsWithSpeed() {
        walk(0.30f, 0, 45, 0, 0);  // 15 cm in the window: just above the deadzone
        float slow = stick.y;
        stick.reset();
        walk(0.60f, 0, 45, 0, 0);  // 30 cm
        float medium = stick.y;
        stick.reset();
        walk(1.0f, 0, 45, 0, 0);
        float fast = stick.y;
        assertTrue(slow > 0 && slow < medium && medium < fast);
    }

    @Test
    public void backAndSidesHaveTheRightSign() {
        walk(-0.8f, 0, 45, 0, 0);
        assertTrue(stick.y < -0.5f);
        stick.reset();
        walk(0, 0.8f, 45, 0, 0);
        assertTrue(stick.x > 0.5f);
        stick.reset();
        walk(0, -0.8f, 45, 0, 0);
        assertTrue(stick.x < -0.5f);
    }

    @Test
    public void diagonalKeepsAngleAndIntensityNearOne() {
        walk(0.7f, 0.7f, 45, 0, 0); // 45° a ~1 m/s
        assertEquals(stick.x, stick.y, 0.05f);
        assertTrue(stick.magnitude > 0.8f && stick.magnitude <= 1f);
    }

    @Test
    public void stoppingReleasesQuicklyEvenFarFromOrigin() {
        float[] p = walk(1.0f, 0, 45, 0, 0);
        assertTrue(stick.magnitude > 0.8f);
        walk(0, 0, 25, p[0], p[1]); // stopped for ~0.8 s
        assertEquals(0f, stick.magnitude, 0.02f);
    }

    @Test
    public void poseJumpZeroesTheStick() {
        walk(0, 0, 30, 0, 0);
        t += FRAME_NS;
        stick.update(0.31f, -0.21f, t);
        assertTrue(stick.jumped);
        assertEquals(0f, stick.magnitude, 0f);
        for (int i = 0; i < 30; i++) {
            t += FRAME_NS;
            stick.update(0.31f, -0.21f, t);
            assertEquals(0f, stick.magnitude, 0.02f);
        }
    }
}
