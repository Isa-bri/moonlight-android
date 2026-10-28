package com.limelight.vr;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Build;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Surface;
import android.view.WindowManager;

// Head orientation (phone in the headset, in landscape) from TYPE_GAME_ROTATION_VECTOR.
//
// World: Z up, the gaze direction at the moment of recentering becomes +Y ("forward").
// Eye space: X to the right, Y up, looking toward -Z (OpenGL convention).
//
// The sensor arrives at ~200 Hz, but the screen only needs the most recent reading once per draw:
// the callback only copies the vector and the matrices are computed in getWorldToEye(), on the GL thread.
//
// Diagnostics (VrLog, SENSOR[label] line once per second): sensor events/s, largest gap between events,
// age of the sample at read time (how far the gaze lags behind the sensor) and how many reads
// repeated the previous sample (a reader faster than the sensor gets a zero step and then a doubled step).
public class HeadTracker implements SensorEventListener {
    private static final long LOG_WINDOW_NS = 1_000_000_000L;

    private final Context context;
    private final SensorManager sensorManager;
    private final WindowManager windowManager;
    private final String label;
    private volatile Runnable sampleListener;

    private HandlerThread thread;

    private final Object lock = new Object();
    private final float[] rawVector = new float[5];
    private int rawLength;
    private boolean hasRaw;
    private boolean recenterRequested = true;

    // Statistics (under `lock`)
    private long sampleSeq, readSeq;
    private long lastEventNs, prevEventNs;
    private boolean lastReadFresh = true;
    private long lastReadAgeNs;
    private long winStartNs, winEvents, winMaxGapNs;
    private long winReads, winStale, winAgeSumNs, winAgeMaxNs;
    private int accuracy = -1;

    // Only used in getWorldToEye(), always from the same thread
    private final float[] vector = new float[5];
    private final float[] rotationRow = new float[16];
    private final float[] remappedRow = new float[16];
    private final float[] rotationCol = new float[16];
    private final float[] yawFix = new float[16];
    private final float[] eyeToWorld = new float[16];
    private float yaw;

    public HeadTracker(Context context) {
        this(context, "GL");
    }

    // `label` says whose log line it is (each owner logs its own sensor): MOUSE, STRAFE, GL
    public HeadTracker(Context context, String label) {
        this.context = context;
        this.label = label;
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public boolean start() {
        Sensor sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (sensor == null) {
            return false;
        }

        thread = new HandlerThread("DeckVR-HeadTracker", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
        thread.start();
        synchronized (lock) {
            sampleSeq = readSeq = 0;
            winStartNs = 0;
            prevEventNs = 0;
            winEvents = winMaxGapNs = winReads = winStale = winAgeSumNs = winAgeMaxNs = 0;
        }
        boolean ok = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST,
                new Handler(thread.getLooper()));
        // Without HIGH_SAMPLING_RATE_SENSORS (Android 12+) the system caps at 200 Hz; the log shows whether the cap applies
        boolean highRate = Build.VERSION.SDK_INT < 31
                || context.checkSelfPermission("android.permission.HIGH_SAMPLING_RATE_SENSORS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        VrLog.i("SENSOR", "[%s] started ok=%b name=\"%s\" delay_min=%dus delay_max=%dus fifo=%d high_rate=%b",
                label, ok, sensor.getName(), sensor.getMinDelay(), sensor.getMaxDelay(),
                sensor.getFifoMaxEventCount(), highRate);
        return ok;
    }

    public void stop() {
        sensorManager.unregisterListener(this);
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
        synchronized (lock) {
            hasRaw = false;
            recenterRequested = true;
        }
    }

    // The next draw becomes the "forward"
    public void recenter() {
        synchronized (lock) {
            recenterRequested = true;
        }
        VrLog.i("SENSOR", "[%s] recenter requested", label);
    }

    // Called on the sensor thread for each new sample (for callers that want to react to the sensor instead of polling on a timer)
    public void setSampleListener(Runnable listener) {
        sampleListener = listener;
    }

    // Did the last getWorldToEye() read bring a new sensor sample? (same thread as getWorldToEye)
    public boolean lastReadFresh() {
        synchronized (lock) {
            return lastReadFresh;
        }
    }

    // Age of the sample used in the last read, in ns (same thread as getWorldToEye)
    public long lastReadAgeNs() {
        synchronized (lock) {
            return lastReadAgeNs;
        }
    }

    // Fills the world->eye matrix (16 floats, column-major, rotation only) with the most recent reading.
    // false if there is no reading yet. Always call from the same thread (the GL thread).
    public boolean getWorldToEye(float[] out) {
        boolean recenterNow;
        synchronized (lock) {
            if (!hasRaw) {
                return false;
            }
            System.arraycopy(rawVector, 0, vector, 0, rawLength);
            recenterNow = recenterRequested;
            recenterRequested = false;

            lastReadFresh = sampleSeq != readSeq;
            readSeq = sampleSeq;
            lastReadAgeNs = SystemClock.elapsedRealtimeNanos() - lastEventNs;
            winReads++;
            if (!lastReadFresh) winStale++;
            winAgeSumNs += lastReadAgeNs;
            winAgeMaxNs = Math.max(winAgeMaxNs, lastReadAgeNs);
        }

        SensorManager.getRotationMatrixFromVector(rotationRow, vector);

        // Device axes -> screen axes at the current rotation (the headset uses landscape)
        int axisX, axisY;
        switch (windowManager.getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90:
                axisX = SensorManager.AXIS_Y;
                axisY = SensorManager.AXIS_MINUS_X;
                break;
            case Surface.ROTATION_180:
                axisX = SensorManager.AXIS_MINUS_X;
                axisY = SensorManager.AXIS_MINUS_Y;
                break;
            case Surface.ROTATION_270:
                axisX = SensorManager.AXIS_MINUS_Y;
                axisY = SensorManager.AXIS_X;
                break;
            default:
                axisX = SensorManager.AXIS_X;
                axisY = SensorManager.AXIS_Y;
                break;
        }
        SensorManager.remapCoordinateSystem(rotationRow, axisX, axisY, remappedRow);

        // SensorManager delivers row-major; android.opengl.Matrix is column-major
        Matrix.transposeM(rotationCol, 0, remappedRow, 0);

        if (recenterNow) {
            // Gaze in the world = R * (0, 0, -1) = -(third column)
            yaw = (float) Math.atan2(-rotationCol[8], -rotationCol[9]);
        }

        // Rotate the world around Z so the initial gaze points toward +Y
        Matrix.setRotateM(yawFix, 0, (float) Math.toDegrees(yaw), 0, 0, 1);
        Matrix.multiplyMM(eyeToWorld, 0, yawFix, 0, rotationCol, 0);
        Matrix.transposeM(out, 0, eyeToWorld, 0);
        return true;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        long events = 0, maxGap = 0, reads = 0, stale = 0, ageSum = 0, ageMax = 0, windowNs = 0;
        int acc = 0;
        boolean logNow;
        synchronized (lock) {
            rawLength = Math.min(event.values.length, rawVector.length);
            System.arraycopy(event.values, 0, rawVector, 0, rawLength);
            hasRaw = true;

            sampleSeq++;
            lastEventNs = event.timestamp;
            if (prevEventNs != 0) {
                winMaxGapNs = Math.max(winMaxGapNs, event.timestamp - prevEventNs);
            }
            prevEventNs = event.timestamp;
            winEvents++;
            if (winStartNs == 0) {
                winStartNs = event.timestamp;
            }
            windowNs = event.timestamp - winStartNs;
            logNow = windowNs >= LOG_WINDOW_NS;
            if (logNow) {
                events = winEvents;
                maxGap = winMaxGapNs;
                reads = winReads;
                stale = winStale;
                ageSum = winAgeSumNs;
                ageMax = winAgeMaxNs;
                acc = accuracy;
                winStartNs = event.timestamp;
                winEvents = winMaxGapNs = winReads = winStale = winAgeSumNs = winAgeMaxNs = 0;
            }
        }
        Runnable listener = sampleListener;
        if (listener != null) {
            listener.run();
        }
        if (!logNow) {
            return;
        }
        VrLog.i("SENSOR", "[%s] events=%.0f/s gap_max=%.1fms reads=%d repeated=%d age_avg=%.2fms age_max=%.2fms accuracy=%d",
                label, events * 1e9 / windowNs, maxGap / 1e6, reads, stale,
                reads > 0 ? ageSum / 1e6 / reads : 0.0, ageMax / 1e6, acc);
    }

    // Low sensor accuracy (0-1) means drift: the camera moves on its own
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        synchronized (lock) {
            this.accuracy = accuracy;
        }
        VrLog.w("SENSOR", "[%s] accuracy changed to %d (0=unusable 1=low 2=medium 3=high)", label, accuracy);
    }
}
