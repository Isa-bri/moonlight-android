package com.limelight.vr;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;

// Walking in place (phone on the head) = hold the W key: each step makes the accelerometer spike above
// gravity; while the steps continue W stays pressed, and it is released if you stand still.
//
// Diagnostics (VrLog, STEP lines): each step with its peak above gravity and the interval since the previous one; each
// "near step" (peak between 60% and 100% of the threshold, or good but too close to the previous step) that did not become a step;
// W going down/up with the duration and the cadence. A per-second summary only appears if there was a peak in the period.
public class StepWalker implements SensorEventListener {
    private static final short KEY_W = (short) 0x8057;
    private static final long MIN_STEP_INTERVAL_NS = 380_000_000L;
    private static final long ARM_WINDOW_NS = 1_200_000_000L;
    private static final long RELEASE_AFTER_NS = 900_000_000L;
    private static final long LOG_WINDOW_NS = 1_000_000_000L;

    private final SensorManager sensorManager;
    private final NvConnection conn;
    private final float thresholdMs2;
    private final MoveStick stickSink; // non-null = walking becomes the analog stick forward (instead of the W key)
    private final StepPipeline pipeline;

    private HandlerThread thread;
    private Handler handler;

    // Diagnostics, only on the sensor thread
    private long lastStepNsWall; // nanoTime of the last step, for the cadence
    private long winStartNs, winEvents;
    private int winSteps, winNearMiss;
    private float winMaxExcess;
    private long lastSampleNs;

    public StepWalker(Context context, NvConnection conn, float thresholdMs2, MoveStick stickSink) {
        this.stickSink = stickSink;
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.conn = conn;
        this.thresholdMs2 = thresholdMs2;
        this.pipeline = new StepPipeline(thresholdMs2, MIN_STEP_INTERVAL_NS, ARM_WINDOW_NS, RELEASE_AFTER_NS,
                stickSink != null, new MoveOutput() {
            @Override
            public void key(char key, boolean down) {
                conn.sendKeyboardInput(KEY_W, down ? KeyboardPacket.KEY_DOWN : KeyboardPacket.KEY_UP, (byte) 0, (byte) 0);
            }

            @Override
            public void stick(float x, float y) {
                StepWalker.this.stickSink.setStick(x, y);
            }
        });
    }

    public boolean start() {
        Sensor sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (sensor == null) {
            return false;
        }
        thread = new HandlerThread("DeckVR-StepWalker");
        thread.start();
        handler = new Handler(thread.getLooper());
        boolean ok = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, handler);
        VrLog.i("STEP", "started ok=%b threshold=%.1fm/s2 interval_min=%dms release_after=%dms confirm_window=%dms delay_min=%dus",
                ok, thresholdMs2, MIN_STEP_INTERVAL_NS / 1_000_000, RELEASE_AFTER_NS / 1_000_000, ARM_WINDOW_NS / 1_000_000,
                sensor.getMinDelay());
        return ok;
    }

    public void stop() {
        sensorManager.unregisterListener(this);
        if (handler != null) {
            final long ts = lastSampleNs;
            handler.post(() -> pipeline.stop(ts));
            thread.quitSafely();
            handler = null;
            thread = null;
        }
    }

    public boolean isWalking() {
        return pipeline.isWalking();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float x = event.values[0], y = event.values[1], z = event.values[2];
        lastSampleNs = event.timestamp;
        VrTrace.acc(event.timestamp, x, y, z);
        boolean step = pipeline.onSample(x, y, z, event.timestamp);
        StepDetector detector = pipeline.detector();
        if (step) {
            lastStepNsWall = System.nanoTime();
            winSteps++;
            if (pipeline.pressedNow) {
                VrLog.i("STEP", "%s down (second consecutive step)", stickSink != null ? "analog" : "W");
            }
            VrLog.i("STEP", "step peak=%.2fm/s2 threshold=%.1f interval=%dms%s", detector.excess, thresholdMs2,
                    detector.sinceLastStepMs, detector.walking ? "" : " (isolated, waiting for the second)");
        }
        if (pipeline.releasedNow) {
            long walked = Math.max(1, lastStepNsWall - pipeline.walkStartNs);
            VrLog.i("STEP", "%s up steps=%d walked=%.1fs cadence=%.2f steps/s (releases %dms after the last)",
                    stickSink != null ? "analog" : "W", pipeline.walkSteps,
                    (event.timestamp - pipeline.walkStartNs) / 1e9,
                    pipeline.walkSteps > 1 ? (pipeline.walkSteps - 1) * 1e9 / Math.max(1, (event.timestamp - pipeline.walkStartNs - RELEASE_AFTER_NS)) : 0.0,
                    RELEASE_AFTER_NS / 1_000_000);
        }
        if (detector.bumpWasMiss) {
            winNearMiss++;
            VrLog.i("STEP", "near step peak=%.2fm/s2 threshold=%.1f reason=%s", detector.missPeak, thresholdMs2,
                    detector.missTooSoon ? "near_previous_step" : "below_threshold");
        }
        logWindow(detector.excess, event.timestamp);
    }

    private void logWindow(float excess, long ts) {
        winEvents++;
        winMaxExcess = Math.max(winMaxExcess, excess);
        if (winStartNs == 0) {
            winStartNs = ts;
        }
        long span = ts - winStartNs;
        if (span >= LOG_WINDOW_NS) {
            if (winSteps > 0 || winNearMiss > 0 || winMaxExcess > thresholdMs2 * StepDetector.NEAR_MISS_FRACTION) {
                VrLog.i("STEP", "summary samples=%.0f/s steps=%d near=%d peak_max=%.2fm/s2 gravity=%.2f walking=%b",
                        winEvents * 1e9 / span, winSteps, winNearMiss, winMaxExcess, pipeline.detector().gravity(),
                        pipeline.isWalking());
            }
            winStartNs = ts;
            winEvents = 0;
            winSteps = winNearMiss = 0;
            winMaxExcess = 0;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
