package com.limelight.vr;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import com.limelight.nvstream.NvConnection;

// Head = camera: turns the head rotation (gaze yaw/pitch) into relative mouse movement.
//
// Uses the same orientation as HeadTracker, but only the deltas between readings; HeadTracker's
// recentering (rotation around Z) does not change the relative yaw/pitch. Turning right moves the mouse
// right; looking up moves the mouse up (positive y is down).
//
// The math (continuous or stepped edge turn, vertical deadzone while walking, freezing the yaw when looking straight
// up/down) is in HeadMotion, without Android. The sensor, the thread, sending and logging live here.
//
// Pace: by default one tick every 4 ms (~250 Hz, above the ~200 Hz sensor, so ~13% of ticks re-read the same
// sample). With `syncToSensor` there is no tick: each new sensor sample triggers a poll, with no repeated reading.
//
// Diagnostics (VrLog, HM line once per second + events): the counters live only on the tick thread; nothing is
// printed per tick. Fields of the HM line:
//   ticks / period_max / late         pace of the 4 ms loop (late = more than 8 ms since the previous tick)
//   stale / age_max                   ticks that re-read the same sensor sample; age of the oldest sample used
//   sent / sum / max / ones           mouse packets; sum and largest |count| in x,y per packet; 1-count packets
//   flips                             x sign changes between consecutive packets (jitter)
//   jumps                             ticks with a turn larger than 3° (sensor jump or a very late tick)
//   deadzone / clamp                  turns discarded by the deadzone; packets clipped to 16 bits
//   path                              degrees of yaw,pitch actually traveled (sum/path = counts per degree)
//   send_avg / send_max               time spent inside sendMouseMove
//   edge / walking / band             ms with edge turn, ms walking, degrees of pitch swallowed by the vertical deadzone
public class HeadMouse {
    public interface WalkSource {
        boolean isWalking();
    }

    private static final long PERIOD_MS = 4; // ~250 Hz, above the sensor (~200 Hz)

    private static final long LOG_WINDOW_NS = 1_000_000_000L;
    private static final long LATE_TICK_NS = 2 * PERIOD_MS * 1_000_000L;
    private static final int VERBOSE_SPIKE_LIMIT = 10; // spike lines per second in verbose mode

    private final HeadTracker tracker;
    private final NvConnection conn;
    private final HeadMotion motion;

    private HandlerThread thread;
    private Handler handler;
    private volatile boolean running;
    private volatile boolean toggledPause;
    private volatile boolean clutch;
    private volatile boolean syncToSensor;
    private volatile WalkSource walkSource;

    private final float[] worldToEye = new float[16];

    // Diagnostics, poll thread only
    private long winStartNs, lastPollNs;
    private int sTicks, sLate, sStale, sPaused, sSent, sOnes, sFlips, sJumps, sDeadzone, sClamp, sGimbal, sSpikeLogs;
    private long sMaxPeriodNs, sAgeMaxNs, sSendSumNs, sSendMaxNs, sEdgeNs, sWalkNs;
    private long sSumX, sSumY;
    private int sMaxX, sMaxY, lastSignX;
    private float sYawPath, sPitchPath, sBandAbsorbed;
    private long pausedSinceNs;
    private float pausedYaw, pausedPitch;
    private boolean pausedByClutch;
    private boolean inEdge;

    // Broadcast tests only: adds a fixed offset and a pitch oscillation to the sensor reading and can pretend to walk.
    // Zeroing the offsets recenters (otherwise the edge-turn center stays at the old offset and the mouse moves on its own).
    private volatile float testYaw, testPitch, testBobAmp, testBobHz;
    private volatile boolean testWalking;
    private volatile boolean testChanged;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            poll();
            handler.postDelayed(this, PERIOD_MS);
        }
    };

    private final Runnable pollOnce = new Runnable() {
        @Override
        public void run() {
            if (running) {
                poll();
            }
        }
    };

    public HeadMouse(Context context, NvConnection conn, float countsPerDegree, float pitchScale) {
        this.tracker = new HeadTracker(context, "MOUSE");
        this.conn = conn;
        this.motion = new HeadMotion(countsPerDegree, pitchScale);
    }

    public void setEdgeTurn(float edgeDeg) {
        motion.edgeDeg = edgeDeg;
    }

    // With the edge turn on, it turns in fixed steps instead of continuously
    public void setSnapTurn(boolean snap) {
        motion.snapTurn = snap;
    }

    // One poll per sensor sample, instead of a 4 ms tick (call before start())
    public void setSyncToSensor(boolean sync) {
        syncToSensor = sync;
    }

    public void setTestOffsets(float yaw, float pitch, float bobAmp, float bobHz, boolean walking) {
        testYaw = yaw;
        testPitch = pitch;
        testBobAmp = bobAmp;
        testBobHz = bobHz;
        testWalking = walking;
        testChanged = yaw == 0 && pitch == 0 && bobAmp == 0 && !walking;
    }

    public void setWalkPitchBand(float bandDeg, WalkSource source) {
        motion.walkPitchBandDeg = bandDeg;
        this.walkSource = source;
    }

    public boolean start() {
        thread = new HandlerThread("DeckVR-HeadMouse", Process.THREAD_PRIORITY_URGENT_DISPLAY);
        thread.start();
        handler = new Handler(thread.getLooper());
        if (syncToSensor) {
            tracker.setSampleListener(() -> handler.post(pollOnce));
        }
        if (!tracker.start()) {
            thread.quitSafely();
            thread = null;
            return false;
        }
        running = true;
        if (!syncToSensor) {
            handler.post(tick);
        }
        return true;
    }

    public void stop() {
        running = false;
        VrLog.i("HM", "stopped");
        tracker.setSampleListener(null);
        tracker.stop();
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
    }

    // Sets the pause explicitly (idempotent), for callers that need to guarantee the state (the test suite on the Deck)
    public void setPaused(boolean paused) {
        toggledPause = paused;
        VrLog.i("VOL", "pause %s (set)", paused ? "on" : "off");
        VrTrace.volume(paused ? "pause_on" : "pause_off");
    }

    // Turns sending on/off; on return, the movement starts from the current pose (no jump)
    public boolean togglePause() {
        toggledPause = !toggledPause;
        VrLog.i("VOL", "pause %s", toggledPause ? "on" : "off");
        VrTrace.volume(toggledPause ? "pause_on" : "pause_off");
        return toggledPause;
    }

    // Clutch: while held, the head turns without moving the camera (like lifting the mouse off the desk)
    public void setClutch(boolean held) {
        if (clutch != held) {
            VrLog.i("VOL", "clutch %s", held ? "held" : "released");
            VrTrace.volume(held ? "clutch_on" : "clutch_off");
        }
        clutch = held;
    }

    private void poll() {
        long nowNs = System.nanoTime();
        countTick(nowNs);

        if (!tracker.getWorldToEye(worldToEye)) {
            logWindow(nowNs);
            return;
        }
        if (!tracker.lastReadFresh()) sStale++;
        sAgeMaxNs = Math.max(sAgeMaxNs, tracker.lastReadAgeNs());

        // Gaze in the world = -(third column of eyeToWorld) = -(third row of worldToEye)
        float gx = -worldToEye[2];
        float gy = -worldToEye[6];
        float gz = -worldToEye[10];
        float yaw = (float) Math.toDegrees(Math.atan2(gx, gy));
        float pitch = (float) Math.toDegrees(Math.asin(Math.max(-1f, Math.min(1f, gz))));
        float horizLen = (float) Math.hypot(gx, gy);

        if (VrTrace.on()) {
            WalkSource traceSource = walkSource;
            VrTrace.hm(nowNs, yaw, pitch, horizLen, testWalking || (traceSource != null && traceSource.isWalking()),
                    toggledPause || clutch);
        }

        if (toggledPause || clutch) {
            if (pausedSinceNs == 0) {
                pausedSinceNs = nowNs;
                pausedYaw = yaw;
                pausedPitch = pitch;
                pausedByClutch = clutch;
                motion.endEdge(nowNs);
                logEdge(nowNs, "pause");
            }
            motion.invalidate();
            sPaused++;
            logWindow(nowNs);
            return;
        }
        if (pausedSinceNs != 0) {
            // The camera resumes from the current pose, with no jump: the turn made during the pause is what "lifting the mouse" repositioned
            VrLog.i("HM", "resumed reason=%s pause=%.1fs turn_during_pause yaw=%+.1f pitch=%+.1f deg",
                    pausedByClutch ? "clutch" : "pause", (nowNs - pausedSinceNs) / 1e9,
                    HeadMotion.wrap(yaw - pausedYaw), pitch - pausedPitch);
            pausedSinceNs = 0;
        }
        if (testChanged) {
            testChanged = false;
            motion.invalidate();
        }

        yaw += testYaw;
        // Broadcast test with the phone lying flat: the yaw would freeze (gaze straight down); pretend to look horizontally
        if (testYaw != 0 || testPitch != 0 || testBobAmp != 0) horizLen = 1f;
        pitch += testPitch + testBobAmp * (float) Math.sin(2 * Math.PI * testBobHz * nowNs / 1e9);

        WalkSource source = walkSource;
        boolean walking = testWalking || (source != null && source.isWalking());
        if (!motion.update(yaw, pitch, horizLen, nowNs, walking)) {
            logEdge(nowNs, "recentered");
            if (motion.walkEvent == HeadMotion.EVENT_ENDED) {
                VrLog.i("HM", "walking end (recentered) duration=%.1fs pitch_swallowed=%.1f deg", motion.eventSeconds, motion.eventAbsorbed);
            }
            VrLog.i("HM", "center yaw=%+.1f pitch=%+.1f", yaw, pitch);
            logWindow(nowNs);
            return;
        }

        if (motion.jump) {
            sJumps++;
            spike("jump turn yaw=%+.2f pitch=%+.2f in %.1fms age=%.1fms", motion.rawDYaw, motion.rawDPitch,
                    motion.dtNs / 1e6, tracker.lastReadAgeNs() / 1e6);
        }
        if (motion.gimbalFrozen) sGimbal++;
        if (motion.walkEvent == HeadMotion.EVENT_ENDED) {
            VrLog.i("HM", "walking end duration=%.1fs pitch_swallowed=%.1f deg band=%.1f",
                    motion.eventSeconds, motion.eventAbsorbed, motion.walkPitchBandDeg);
        }
        sBandAbsorbed += motion.absorbedPitch;
        if (motion.walkPitchBandDeg > 0 && walking) sWalkNs += motion.dtNs;
        if (motion.edgeEvent == HeadMotion.EVENT_STARTED) {
            inEdge = true;
            VrLog.i("HM", "edge entered rel=%+.1f limit=%.0f deg mode=%s", motion.eventRel, motion.edgeDeg,
                    motion.snapTurn ? "steps" : "continuous");
        }
        else {
            logEdge(nowNs, "returned");
        }
        if (inEdge) sEdgeNs += motion.dtNs;

        sDeadzone += motion.deadzoneHits;
        sYawPath += Math.abs(motion.dYaw);
        sPitchPath += Math.abs(motion.dPitch);

        int mx = motion.mx, my = motion.my;
        if (mx != 0 || my != 0) {
            if (Math.abs(mx) > 32767 || Math.abs(my) > 32767) sClamp++;
            long t0 = System.nanoTime();
            conn.sendMouseMove((short) Math.max(-32768, Math.min(32767, mx)),
                    (short) Math.max(-32768, Math.min(32767, my)));
            long sendNs = System.nanoTime() - t0;

            sSent++;
            sSendSumNs += sendNs;
            sSendMaxNs = Math.max(sSendMaxNs, sendNs);
            int ax = Math.abs(mx), ay = Math.abs(my);
            sSumX += ax;
            sSumY += ay;
            sMaxX = Math.max(sMaxX, ax);
            sMaxY = Math.max(sMaxY, ay);
            if (ax + ay == 1) sOnes++;
            if (mx != 0) {
                int sign = mx > 0 ? 1 : -1;
                if (lastSignX != 0 && sign != lastSignX) sFlips++;
                lastSignX = sign;
            }
            if (ax > 60 || ay > 60) {
                spike("big step mouse=%d,%d age=%.1fms", mx, my, tracker.lastReadAgeNs() / 1e6);
            }
        }
        logWindow(nowNs);
    }

    // Logs the end of the edge turn (after motion.update() or motion.endEdge())
    private void logEdge(long nowNs, String why) {
        if (motion.edgeEvent == HeadMotion.EVENT_ENDED) {
            inEdge = false;
            VrLog.i("HM", "edge left (%s) duration=%.1fs turned=%.0f deg", why, motion.eventSeconds, motion.eventTurned);
            motion.edgeEvent = HeadMotion.EVENT_NONE;
        }
    }

    // Period between consecutive polls: in timed mode it should stay near 4 ms; in synchronized mode, near 5 ms (200 Hz)
    private void countTick(long nowNs) {
        sTicks++;
        if (lastPollNs != 0) {
            long period = nowNs - lastPollNs;
            sMaxPeriodNs = Math.max(sMaxPeriodNs, period);
            if (period > LATE_TICK_NS) {
                sLate++;
                spike("late tick period=%.1fms", period / 1e6);
            }
        }
        lastPollNs = nowNs;
    }

    // Isolated spikes only in verbose mode, at most VERBOSE_SPIKE_LIMIT per second
    private void spike(String format, Object... args) {
        if (VrLog.verbose() && sSpikeLogs < VERBOSE_SPIKE_LIMIT) {
            sSpikeLogs++;
            VrLog.v("HM", format, args);
        }
    }

    private void logWindow(long nowNs) {
        if (winStartNs == 0) {
            winStartNs = nowNs;
            return;
        }
        long span = nowNs - winStartNs;
        if (span < LOG_WINDOW_NS) {
            return;
        }
        VrLog.i("HM", "ticks=%d period_max=%.1fms late=%d stale=%d age_max=%.1fms sent=%d paused=%d "
                        + "sum=%d,%d max=%d,%d ones=%d flips=%d jumps=%d deadzone=%d clamp=%d path=%.1f,%.1f "
                        + "send_avg=%.3fms send_max=%.2fms edge=%d walking=%d band=%.2f frozen=%d sync=%b",
                sTicks, sMaxPeriodNs / 1e6, sLate, sStale, sAgeMaxNs / 1e6, sSent, sPaused,
                sSumX, sSumY, sMaxX, sMaxY, sOnes, sFlips, sJumps, sDeadzone, sClamp, sYawPath, sPitchPath,
                sSent > 0 ? sSendSumNs / 1e6 / sSent : 0.0, sSendMaxNs / 1e6,
                sEdgeNs / 1_000_000, sWalkNs / 1_000_000, sBandAbsorbed, sGimbal, syncToSensor);
        winStartNs = nowNs;
        sTicks = sLate = sStale = sPaused = sSent = sOnes = sFlips = sJumps = sDeadzone = sClamp = sGimbal = sSpikeLogs = 0;
        sMaxPeriodNs = sAgeMaxNs = sSendSumNs = sSendMaxNs = sEdgeNs = sWalkNs = 0;
        sSumX = sSumY = 0;
        sMaxX = sMaxY = 0;
        sYawPath = sPitchPath = sBandAbsorbed = 0;
    }
}
