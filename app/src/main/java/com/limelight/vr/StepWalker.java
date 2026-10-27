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

// Andar no lugar (celular na cabeça) = segurar a tecla W: cada passo faz o acelerômetro dar um pico acima
// da gravidade; enquanto os passos continuam W fica pressionada, e é solta se ficar parado.
public class StepWalker implements SensorEventListener {
    private static final short KEY_W = (short) 0x8057;
    private static final long MIN_STEP_INTERVAL_NS = 280_000_000L;
    private static final long RELEASE_AFTER_MS = 650;

    private final SensorManager sensorManager;
    private final NvConnection conn;
    private final float thresholdMs2;

    private HandlerThread thread;
    private Handler handler;

    // Só usados na thread do sensor
    private float gravity = SensorManager.GRAVITY_EARTH;
    private long lastStepNs;
    private boolean down;
    private final Runnable release = this::releaseKey;

    public StepWalker(Context context, NvConnection conn, float thresholdMs2) {
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.conn = conn;
        this.thresholdMs2 = thresholdMs2;
    }

    public boolean start() {
        Sensor sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (sensor == null) {
            return false;
        }
        thread = new HandlerThread("DeckVR-StepWalker");
        thread.start();
        handler = new Handler(thread.getLooper());
        return sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, handler);
    }

    public void stop() {
        sensorManager.unregisterListener(this);
        if (handler != null) {
            handler.post(release);
            thread.quitSafely();
            handler = null;
            thread = null;
        }
    }

    private void releaseKey() {
        if (down) {
            down = false;
            conn.sendKeyboardInput(KEY_W, KeyboardPacket.KEY_UP, (byte) 0, (byte) 0);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float x = event.values[0], y = event.values[1], z = event.values[2];
        float mag = (float) Math.sqrt(x * x + y * y + z * z);
        gravity += 0.05f * (mag - gravity);
        if (mag - gravity > thresholdMs2 && event.timestamp - lastStepNs > MIN_STEP_INTERVAL_NS) {
            lastStepNs = event.timestamp;
            if (!down) {
                down = true;
                conn.sendKeyboardInput(KEY_W, KeyboardPacket.KEY_DOWN, (byte) 0, (byte) 0);
            }
            handler.removeCallbacks(release);
            handler.postDelayed(release, RELEASE_AFTER_MS);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
