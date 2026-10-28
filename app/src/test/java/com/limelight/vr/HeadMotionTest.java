package com.limelight.vr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class HeadMotionTest {
    private static final long TICK_NS = 4_000_000L;

    private HeadMotion motion;
    private long now;

    @Before
    public void setUp() {
        motion = new HeadMotion(10f, 1f); // 10 counts per degree
        now = 1_000_000_000L;
    }

    private void tick(float yaw, float pitch, boolean walking) {
        now += TICK_NS;
        motion.update(yaw, pitch, 1f, now, walking);
    }

    private void center(float yaw, float pitch) {
        assertFalse("the first call only sets the center", motion.update(yaw, pitch, 1f, now, false));
    }

    @Test
    public void slowTurnBecomesProportionalCounts() {
        center(0, 0);
        int sumX = 0;
        for (int i = 1; i <= 250; i++) { // 10° in 1 s
            tick(i * 0.04f, 0, false);
            sumX += motion.mx;
        }
        assertEquals(100, sumX, 1);
    }

    @Test
    public void lookingUpMovesMouseUp() {
        center(0, 0);
        int sumY = 0;
        for (int i = 1; i <= 100; i++) {
            tick(0, i * 0.1f, false); // 10° up
            sumY += motion.my;
        }
        assertEquals(-100, sumY, 1); // positive y is down
    }

    @Test
    public void wrapAround180DoesNotBecomeJump() {
        center(179.98f, 0);
        tick(-179.98f, 0, false); // 0.04° to the right
        assertEquals(0.04f, motion.rawDYaw, 0.001f);
        assertFalse(motion.jump);
    }

    @Test
    public void noiseBelowDeadzoneDoesNotMoveMouse() {
        center(0, 0);
        float yaw = 0;
        int sumX = 0;
        for (int i = 0; i < 500; i++) {
            yaw += (i % 2 == 0 ? 0.004f : -0.004f);
            tick(yaw, 0, false);
            sumX += Math.abs(motion.mx);
        }
        assertEquals(0, sumX);
        assertTrue(motion.deadzoneHits > 0 || sumX == 0);
    }

    @Test
    public void invalidateRecentersWithoutJump() {
        center(0, 0);
        tick(1, 0, false);
        motion.invalidate();
        assertFalse(motion.update(90f, 0, 1f, now + TICK_NS, false)); // head turned during the pause: only redefines the center
        assertEquals(0, motion.mx);
        tick(90.04f, 0, false);
        assertEquals(0.04f, motion.rawDYaw, 0.001f);
    }

    @Test
    public void continuousEdgeTurnReachesMaxSpeed() {
        motion.edgeDeg = 45;
        center(0, 0);
        tick(60, 0, false); // the head turn up to 60° does not count in this measurement
        boolean entered = motion.edgeEvent == HeadMotion.EVENT_STARTED;
        int sumX = 0;
        for (int i = 0; i < 250; i++) { // 1 s with the head at 60° (15° past the edge = full ramp)
            tick(60, 0, false);
            entered |= motion.edgeEvent == HeadMotion.EVENT_STARTED;
            sumX += motion.mx;
        }
        assertTrue(entered);
        assertEquals(1200, sumX, 15); // 120°/s * 10 counts/°
    }

    @Test
    public void edgeTurnStopsWhenReturningInside() {
        motion.edgeDeg = 45;
        center(0, 0);
        for (int i = 0; i < 50; i++) tick(60, 0, false);
        boolean left = false;
        int sumX = 0;
        for (int i = 0; i < 50; i++) {
            tick(30, 0, false);
            left |= motion.edgeEvent == HeadMotion.EVENT_ENDED;
            sumX += motion.edgeYaw != 0 ? 1 : 0;
        }
        assertTrue(left);
        assertEquals(0, sumX);
    }

    @Test
    public void snapTurnTurnsOneStepAndWaitsToReturnInside() {
        motion.edgeDeg = 45;
        motion.snapTurn = true;
        center(0, 0);
        float total = 0;
        for (int i = 0; i < 500; i++) { // 2 s looking past the edge
            tick(60, 0, false);
            total += motion.edgeYaw;
        }
        assertEquals(HeadMotion.SNAP_STEP_DEG, total, 0.5f); // just one step, even when holding

        for (int i = 0; i < 20; i++) tick(30, 0, false); // goes back near the center (re-arms)
        float second = 0;
        for (int i = 0; i < 500; i++) {
            tick(60, 0, false);
            second += motion.edgeYaw;
        }
        assertEquals(HeadMotion.SNAP_STEP_DEG, second, 0.5f);
    }

    @Test
    public void snapTurnToTheNegativeSide() {
        motion.edgeDeg = 45;
        motion.snapTurn = true;
        center(0, 0);
        float total = 0;
        for (int i = 0; i < 500; i++) {
            tick(-60, 0, false);
            total += motion.edgeYaw;
        }
        assertEquals(-HeadMotion.SNAP_STEP_DEG, total, 0.5f);
    }

    @Test
    public void verticalDeadzoneSwallowsSwayButNotRealLooking() {
        motion.walkPitchBandDeg = 1.5f;
        center(0, 0);
        int sumY = 0;
        for (int i = 0; i < 500; i++) { // sway of ±1° at 2 Hz while walking
            tick(0, (float) Math.sin(2 * Math.PI * 2 * (i * 0.004)), true);
            sumY += Math.abs(motion.my);
        }
        assertEquals(0, sumY);

        int look = 0;
        for (int i = 1; i <= 100; i++) { // deliberately looking 10° up
            tick(0, i * 0.1f, true);
            look += motion.my;
        }
        // everything gets through except the band (1.5°) the deadzone swallows
        assertEquals(-85, look, 10);
    }

    @Test
    public void withoutWalkingPitchPassesWhole() {
        motion.walkPitchBandDeg = 1.5f;
        center(0, 0);
        int sumY = 0;
        for (int i = 1; i <= 100; i++) {
            tick(0, i * 0.01f, false); // 1° up, not walking
            sumY += motion.my;
        }
        assertEquals(-10, sumY, 1);
    }

    @Test
    public void lookingStraightDownYawGetsFrozen() {
        center(0, -89.5f);
        now += TICK_NS;
        motion.update(50f, -89.5f, 0.01f, now, false); // unstable yaw near the pole
        assertTrue(motion.gimbalFrozen);
        assertEquals(0, motion.mx);
        assertEquals(0f, motion.rawDYaw, 0.0001f);
        now += TICK_NS;
        motion.update(50.04f, -85f, 0.5f, now, false); // left the pole: counts again
        assertFalse(motion.gimbalFrozen);
        assertEquals(0.04f, motion.rawDYaw, 0.001f);
    }

    @Test
    public void sensorJumpIsMarked() {
        center(0, 0);
        tick(5f, 0, false);
        assertTrue(motion.jump);
    }

    @Test
    public void recenteringWithActiveEdgeReportsItsEnd() {
        motion.edgeDeg = 45;
        center(0, 0);
        for (int i = 0; i < 50; i++) tick(60, 0, false);
        motion.invalidate();
        now += TICK_NS;
        assertFalse(motion.update(60f, 0, 1f, now, false));
        assertEquals(HeadMotion.EVENT_ENDED, motion.edgeEvent);
        assertTrue(motion.eventTurned > 0);
        tick(60.0f, 0, false); // with the new center at 60°, the edge is no longer active
        assertEquals(0f, motion.edgeYaw, 0f);
    }
}
