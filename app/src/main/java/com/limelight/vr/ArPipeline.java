package com.limelight.vr;

// Real walking through ARCore, without Android (testable on the JVM and in replay): receives the camera pose frame by frame and
// decides the W/A/S/D keys or the analog stick. Everything that used to live inside ArPosition (projection onto the body axes,
// recentering, pose jump, joystick-mode hysteresis, "only while moving" and analog modes) lives here, so replaying a recorded
// trace exercises the same code that runs on the phone.
//
// Per-frame input: x, z of the camera position in the ARCore world (Y up), (hx, hz) = horizontal components of the gaze
// (-zAxis of the pose; only the direction matters), and whether tracking is active. `requestRecenter()` marks the next pose as the origin.
public class ArPipeline {
    public static final float JUMP_M = 0.25f;     // pose jump between two frames that is not human movement
    public static final float SIDE_FACTOR = 2f;   // sideways moves ask for more displacement: the head sways unintentionally
    public static final long WINDOW_NS = 500_000_000L;

    private final float thresholdM, releaseM, sideThresholdM, sideReleaseM;
    private final boolean holdMode;   // true = joystick (holds while away from the origin)
    private final boolean analog;     // true = analog stick instead of keys
    private final MoveOutput out;
    private final ArMotionKeys motion;
    private final ArMotionStick stick;

    private volatile boolean recenterRequested = true;
    private float originX, originZ;
    private float fwdX, fwdZ = -1;     // current gaze
    private float bodyX, bodyZ = -1;   // body forward, fixed at the origin
    private float prevX, prevZ;
    private boolean hasPrevPose;
    private float lastStickX, lastStickY;

    public boolean fwdHeld, backHeld, leftHeld, rightHeld;
    public volatile boolean stickWalking;

    // Result of the last onFrame(), for the caller's log
    public float curForward, curSide;      // displacement on the body axes (already including the test displacement)
    public boolean poseJumped, recentered;
    public float recenterBeforeForward, recenterBeforeSide;
    public float stickX, stickY;

    // Tests only: added to the measured displacement; zeroed on recenter
    public float testForward, testSide;

    public ArPipeline(float thresholdM, boolean holdMode, boolean analog, MoveOutput out) {
        this.thresholdM = thresholdM;
        this.releaseM = thresholdM * 0.6f;
        this.sideThresholdM = thresholdM * SIDE_FACTOR;
        this.sideReleaseM = sideThresholdM * 0.6f;
        this.holdMode = holdMode;
        this.analog = analog;
        this.out = out;
        this.motion = new ArMotionKeys(thresholdM, SIDE_FACTOR, WINDOW_NS);
        this.stick = new ArMotionStick(thresholdM, thresholdM * 4f, WINDOW_NS);
    }

    public void requestRecenter() {
        recenterRequested = true;
    }

    public boolean isWalking() {
        return fwdHeld || backHeld || leftHeld || rightHeld || stickWalking;
    }

    // false if tracking is not active (nothing is decided; everything is released)
    public boolean onFrame(long tNs, float x, float z, float hx, float hz, boolean tracking) {
        poseJumped = false;
        recentered = false;
        if (!tracking) {
            hasPrevPose = false;
            motion.reset();
            releaseAll();
            return false;
        }

        if (hasPrevPose && Math.hypot(x - prevX, z - prevZ) > JUMP_M) {
            poseJumped = true;
            // The origin follows the jump, so the displacement stays continuous (applies to joystick mode)
            originX += x - prevX;
            originZ += z - prevZ;
        }
        prevX = x;
        prevZ = z;
        hasPrevPose = true;

        float hLen = (float) Math.hypot(hx, hz);
        if (hLen > 0.3f) { // looking almost straight up/down: keep the last forward
            fwdX = hx / hLen;
            fwdZ = hz / hLen;
        }

        if (recenterRequested) {
            recenterRequested = false;
            recentered = true;
            recenterBeforeForward = curForward;
            recenterBeforeSide = curSide;
            originX = x;
            originZ = z;
            bodyX = fwdX;
            bodyZ = fwdZ;
            motion.reset();
            testForward = testSide = 0;
            releaseAll();
        }

        float dx = x - originX, dz = z - originZ;
        // right = (-bodyZ, bodyX) in the ARCore system (Y up, looking toward -Z)
        float forward = dx * bodyX + dz * bodyZ + testForward;
        float side = dx * -bodyZ + dz * bodyX + testSide;
        curForward = forward;
        curSide = side;

        if (analog) {
            if (poseJumped) {
                stick.reset();
            }
            else {
                stick.update(forward, side, tNs);
            }
            sendStick(stick.x, stick.y);
            return true;
        }

        boolean wantFwd, wantBack, wantRight, wantLeft;
        if (poseJumped) {
            motion.reset();
            wantFwd = wantBack = wantRight = wantLeft = false;
        }
        else if (holdMode) {
            wantFwd = fwdHeld ? forward > releaseM : forward > thresholdM;
            wantBack = backHeld ? -forward > releaseM : -forward > thresholdM;
            wantRight = rightHeld ? side > sideReleaseM : side > sideThresholdM;
            wantLeft = leftHeld ? -side > sideReleaseM : -side > sideThresholdM;
        }
        else {
            motion.update(forward, side, tNs);
            wantFwd = motion.w;
            wantBack = motion.s;
            wantRight = motion.d;
            wantLeft = motion.a;
        }
        if (wantFwd != fwdHeld) { fwdHeld = wantFwd; out.key('W', wantFwd); }
        if (wantBack != backHeld) { backHeld = wantBack; out.key('S', wantBack); }
        if (wantRight != rightHeld) { rightHeld = wantRight; out.key('D', wantRight); }
        if (wantLeft != leftHeld) { leftHeld = wantLeft; out.key('A', wantLeft); }
        return true;
    }

    // Release everything (tracking lost, recenter, shutdown)
    public void releaseAll() {
        if (analog) {
            stick.reset();
            sendStick(0f, 0f);
            return;
        }
        if (fwdHeld) { fwdHeld = false; out.key('W', false); }
        if (backHeld) { backHeld = false; out.key('S', false); }
        if (rightHeld) { rightHeld = false; out.key('D', false); }
        if (leftHeld) { leftHeld = false; out.key('A', false); }
    }

    // Send the stick when it changes enough (1/256) and whenever it returns to zero
    private void sendStick(float x, float y) {
        boolean zero = x == 0 && y == 0;
        if (zero ? (lastStickX == 0 && lastStickY == 0)
                : Math.abs(x - lastStickX) < 1 / 256f && Math.abs(y - lastStickY) < 1 / 256f) {
            return;
        }
        lastStickX = x;
        lastStickY = y;
        stickX = x;
        stickY = y;
        stickWalking = Math.hypot(x, y) > 0.1;
        out.stick(x, y);
    }
}
