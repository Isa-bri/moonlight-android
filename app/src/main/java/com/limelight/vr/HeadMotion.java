package com.limelight.vr;

// Head = camera logic, without Android (testable on the JVM): receives the gaze (yaw/pitch in degrees) on each tick and returns how
// many mouse counts to send. HeadMouse handles the sensor, the thread, sending and logging.
//
// - Turn step per tick, with the 180° wrap-around handled and a deadzone of 0.005°.
// - Vertical deadzone while walking: the pitch only passes after leaving a band around the last position.
// - Edge turn: past `edgeDeg` degrees from the center, the camera keeps turning (continuous, faster the farther out)
//   or, with `snapTurn`, makes a fixed turn of SNAP_STEP_DEG and only turns again after the head returns near the center.
// - Looking almost straight up/down the yaw is unstable (singularity): below GIMBAL_HORIZ it does not turn the mouse.
public class HeadMotion {
    public static final float DEADZONE_DEG = 0.005f;
    public static final float EDGE_RAMP_DEG = 15f;        // from the edge up to the maximum speed
    public static final float EDGE_MAX_DEG_PER_S = 120f;  // maximum speed of the continuous turn
    public static final float SNAP_STEP_DEG = 30f;        // turn of each snap turn step
    public static final float SNAP_DEG_PER_S = 360f;      // speed of the step turn (~80 ms), fast to avoid nausea
    public static final float SNAP_REARM_DEG = 10f;       // how far to come back inside the edge before turning again
    public static final float GIMBAL_HORIZ = 0.0872f;     // cos(85°): horizontal component of the gaze below which the yaw freezes
    public static final float JUMP_DEG = 3f;              // turn per tick that is not from a human head

    public static final int EVENT_NONE = 0;
    public static final int EVENT_STARTED = 1;
    public static final int EVENT_ENDED = 2;

    private final float countsPerDegree;
    private final float pitchCountsPerDegree;

    // Parameters (can change while the app is running)
    public volatile float edgeDeg;          // 0 = no edge turn
    public volatile boolean snapTurn;
    public volatile float walkPitchBandDeg; // 0 = no vertical deadzone

    // State
    private boolean hasPrev;
    private float prevYaw, prevPitch, centerYaw, anchorPitch;
    private long prevNs;
    private float remX, remY;
    private boolean wasWalking;
    private long walkStartNs;
    private float walkAbsorbed;
    private boolean edgeActive, snapArmed = true;
    private long edgeStartNs;
    private float edgeTurned, snapRemaining;

    // Result of the last update()
    public int mx, my;
    public float rawDYaw, rawDPitch;   // turn of the tick before the deadzones
    public float dYaw, dPitch;         // turn that actually became mouse movement (without the edge part)
    public float edgeYaw;              // edge turn added on this tick
    public float absorbedPitch;        // pitch swallowed by the vertical deadzone on this tick
    public long dtNs;
    public int deadzoneHits;
    public boolean jump, gimbalFrozen;
    public int walkEvent, edgeEvent;
    public float eventRel;             // turn relative to the center when the edge started
    public float eventTurned;          // degrees turned by the edge (on the edge's EVENT_ENDED)
    public float eventSeconds;         // duration (on the EVENT_ENDED of the edge and of walking)
    public float eventAbsorbed;        // pitch swallowed over the whole walk (on the walking EVENT_ENDED)

    public HeadMotion(float countsPerDegree, float pitchScale) {
        this.countsPerDegree = countsPerDegree;
        this.pitchCountsPerDegree = countsPerDegree * pitchScale;
    }

    // The next update() only sets the center (no movement), as after a pause/clutch
    public void invalidate() {
        hasPrev = false;
    }

    public boolean hasCenter() {
        return hasPrev;
    }

    // Ends the edge turn in progress (pause); fills in edgeEvent for the caller to log
    public void endEdge(long nowNs) {
        edgeEvent = EVENT_NONE;
        if (edgeActive) {
            edgeActive = false;
            edgeEvent = EVENT_ENDED;
            eventSeconds = (nowNs - edgeStartNs) / 1e9f;
            eventTurned = edgeTurned;
        }
    }

    public float centerYaw() {
        return centerYaw;
    }

    // yaw/pitch in degrees; horizLen = horizontal component of the gaze vector (1 looking horizontally, 0 straight up/down).
    // false on the call that only set the center (there was no movement).
    public boolean update(float yaw, float pitch, float horizLen, long nowNs, boolean walking) {
        mx = my = 0;
        rawDYaw = rawDPitch = dYaw = dPitch = edgeYaw = absorbedPitch = 0;
        deadzoneHits = 0;
        jump = gimbalFrozen = false;
        walkEvent = edgeEvent = EVENT_NONE;

        if (!hasPrev) {
            // Recentering ends whatever was in progress (and tells the caller, so the log is not left with the edge "active")
            endEdge(nowNs);
            if (wasWalking) {
                walkEvent = EVENT_ENDED;
                eventSeconds = (nowNs - walkStartNs) / 1e9f;
                eventAbsorbed = walkAbsorbed;
            }
            prevYaw = yaw;
            prevPitch = pitch;
            centerYaw = yaw;
            anchorPitch = pitch;
            wasWalking = false;
            edgeActive = false;
            snapArmed = true;
            snapRemaining = 0;
            prevNs = nowNs;
            dtNs = 0;
            hasPrev = true;
            return false;
        }
        if (horizLen < GIMBAL_HORIZ) {
            // The yaw is noise here: it does not become mouse movement, but it becomes the new reference (and takes the center
            // along), so the camera does not jump when the gaze leaves the pole
            centerYaw += wrap(yaw - prevYaw);
            prevYaw = yaw;
            gimbalFrozen = true;
        }

        dtNs = nowNs - prevNs;
        float dt = Math.min(dtNs / 1e9f, 0.05f);
        prevNs = nowNs;

        float dY = wrap(yaw - prevYaw);
        float dP = pitch - prevPitch;
        prevYaw = yaw;
        prevPitch = pitch;
        rawDYaw = dY;
        rawDPitch = dP;
        jump = Math.abs(dY) > JUMP_DEG || Math.abs(dP) > JUMP_DEG;

        // While walking, the pitch only follows when it leaves the band around the anchor
        boolean walkingNow = walkPitchBandDeg > 0 && walking;
        if (walkingNow) {
            if (!wasWalking) {
                anchorPitch = pitch - dP;
                walkStartNs = nowNs;
                walkAbsorbed = 0;
                walkEvent = EVENT_STARTED;
            }
            float band = walkPitchBandDeg;
            float before = anchorPitch;
            if (pitch > anchorPitch + band) anchorPitch = pitch - band;
            else if (pitch < anchorPitch - band) anchorPitch = pitch + band;
            float passed = anchorPitch - before;
            absorbedPitch = Math.abs(dP - passed);
            walkAbsorbed += absorbedPitch;
            dP = passed;
        }
        else if (wasWalking) {
            walkEvent = EVENT_ENDED;
            eventSeconds = (nowNs - walkStartNs) / 1e9f;
            eventAbsorbed = walkAbsorbed;
        }
        wasWalking = walkingNow;

        // Edge turn
        float edge = edgeDeg;
        if (edge > 0) {
            float rel = wrap(yaw - centerYaw);
            float excess = Math.abs(rel) - edge;
            if (excess > 0) {
                if (snapTurn) {
                    if (snapArmed) {
                        snapArmed = false;
                        snapRemaining = Math.signum(rel) * SNAP_STEP_DEG;
                    }
                    float turn = Math.signum(snapRemaining) * Math.min(Math.abs(snapRemaining), SNAP_DEG_PER_S * dt);
                    snapRemaining -= turn;
                    edgeYaw = turn;
                }
                else {
                    float speed = Math.min(excess / EDGE_RAMP_DEG, 1f) * EDGE_MAX_DEG_PER_S;
                    edgeYaw = Math.signum(rel) * speed * dt;
                }
                if (!edgeActive) {
                    edgeActive = true;
                    edgeStartNs = nowNs;
                    edgeTurned = 0;
                    edgeEvent = EVENT_STARTED;
                    eventRel = rel;
                }
                edgeTurned += Math.abs(edgeYaw);
            }
            else {
                endEdge(nowNs);
            }
            // The snap turn only turns again after the head comes back inside the edge (with some margin)
            if (!snapArmed && Math.abs(rel) < edge - SNAP_REARM_DEG) {
                snapArmed = true;
                snapRemaining = 0;
            }
        }

        if (dY != 0 && Math.abs(dY) < DEADZONE_DEG) { dY = 0; deadzoneHits++; }
        if (dP != 0 && Math.abs(dP) < DEADZONE_DEG) { dP = 0; deadzoneHits++; }
        dYaw = dY;
        dPitch = dP;

        // Keep the fraction that does not fit in an integer so slow movement is not lost
        remX += (dY + edgeYaw) * countsPerDegree;
        remY += -dP * pitchCountsPerDegree;
        mx = (int) remX;
        my = (int) remY;
        remX -= mx;
        remY -= my;
        return true;
    }

    public static float wrap(float deg) {
        if (deg > 180f) return deg - 360f;
        if (deg < -180f) return deg + 360f;
        return deg;
    }
}
