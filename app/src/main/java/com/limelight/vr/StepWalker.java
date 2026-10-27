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
//
// Diagnóstico (VrLog, linhas PASSO): cada passo com o pico acima da gravidade e o intervalo desde o anterior; cada
// "quase passo" (pico entre 60% e 100% do limiar, ou bom mas perto demais do passo anterior) que ficou sem virar passo;
// W descendo/subindo com a duração e a cadência. Um resumo por segundo só aparece se houve pico no período.
public class StepWalker implements SensorEventListener {
    private static final short KEY_W = (short) 0x8057;
    private static final long MIN_STEP_INTERVAL_NS = 280_000_000L;
    private static final long RELEASE_AFTER_MS = 650;
    private static final float NEAR_MISS_FRACTION = 0.6f;
    private static final long LOG_WINDOW_NS = 1_000_000_000L;

    private final SensorManager sensorManager;
    private final NvConnection conn;
    private final float thresholdMs2;

    private HandlerThread thread;
    private Handler handler;

    // Só usados na thread do sensor
    private float gravity = SensorManager.GRAVITY_EARTH;
    private long lastStepNs;
    private volatile boolean down;
    private long lastStepNsWall; // nanoTime do último passo, para a cadência
    private final Runnable release = this::releaseKey;

    // Diagnóstico, só na thread do sensor
    private boolean inBump, bumpStepped, bumpTooSoon;
    private float bumpPeak;
    private int walkSteps;
    private long walkStartNs;
    private long winStartNs, winEvents;
    private int winSteps, winNearMiss;
    private float winMaxExcess;

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
        boolean ok = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, handler);
        VrLog.i("PASSO", "iniciado ok=%b limiar=%.1fm/s2 intervalo_min=%dms solta_apos=%dms atraso_min=%dus",
                ok, thresholdMs2, MIN_STEP_INTERVAL_NS / 1_000_000, RELEASE_AFTER_MS, sensor.getMinDelay());
        return ok;
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

    public boolean isWalking() {
        return down;
    }

    private void releaseKey() {
        if (down) {
            down = false;
            conn.sendKeyboardInput(KEY_W, KeyboardPacket.KEY_UP, (byte) 0, (byte) 0);
            long now = System.nanoTime();
            long walked = Math.max(1, lastStepNsWall - walkStartNs);
            VrLog.i("PASSO", "W subiu passos=%d andou=%.1fs cadencia=%.2f passos/s (solta %dms depois do ultimo)",
                    walkSteps, (now - walkStartNs) / 1e9, walkSteps > 1 ? (walkSteps - 1) * 1e9 / walked : 0.0,
                    RELEASE_AFTER_MS);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float x = event.values[0], y = event.values[1], z = event.values[2];
        float mag = (float) Math.sqrt(x * x + y * y + z * z);
        gravity += 0.05f * (mag - gravity);
        float excess = mag - gravity;
        boolean step = false;
        if (excess > thresholdMs2 && event.timestamp - lastStepNs > MIN_STEP_INTERVAL_NS) {
            step = true;
            long sinceMs = lastStepNs == 0 ? -1 : (event.timestamp - lastStepNs) / 1_000_000;
            lastStepNs = event.timestamp;
            lastStepNsWall = System.nanoTime();
            if (!down) {
                down = true;
                walkSteps = 0;
                walkStartNs = lastStepNsWall;
                conn.sendKeyboardInput(KEY_W, KeyboardPacket.KEY_DOWN, (byte) 0, (byte) 0);
                VrLog.i("PASSO", "W desceu");
            }
            walkSteps++;
            winSteps++;
            handler.removeCallbacks(release);
            handler.postDelayed(release, RELEASE_AFTER_MS);
            VrLog.i("PASSO", "passo pico=%.2fm/s2 limiar=%.1f intervalo=%dms", excess, thresholdMs2, sinceMs);
        }
        // Pico bom, mas dentro dos 280 ms do passo anterior
        trackBump(excess, step, !step && excess > thresholdMs2, event.timestamp);
    }

    // Agrupa as amostras acima de 60% do limiar em "solavancos": se nenhum virou passo, é um quase passo
    // (perdido por sensibilidade ou por intervalo curto). Os picos guiam o ajuste de "step sensitivity".
    private void trackBump(float excess, boolean step, boolean tooSoon, long ts) {
        winEvents++;
        winMaxExcess = Math.max(winMaxExcess, excess);
        float floor = thresholdMs2 * NEAR_MISS_FRACTION;
        if (excess > floor) {
            if (!inBump) {
                inBump = true;
                bumpPeak = 0;
                bumpStepped = false;
                bumpTooSoon = false;
            }
            bumpPeak = Math.max(bumpPeak, excess);
            bumpStepped |= step;
            bumpTooSoon |= tooSoon;
        }
        else if (inBump) {
            inBump = false;
            if (!bumpStepped) {
                winNearMiss++;
                VrLog.i("PASSO", "quase passo pico=%.2fm/s2 limiar=%.1f motivo=%s", bumpPeak, thresholdMs2,
                        bumpTooSoon ? "perto_do_passo_anterior" : "abaixo_do_limiar");
            }
        }

        if (winStartNs == 0) {
            winStartNs = ts;
        }
        long span = ts - winStartNs;
        if (span >= LOG_WINDOW_NS) {
            if (winSteps > 0 || winNearMiss > 0 || winMaxExcess > floor) {
                VrLog.i("PASSO", "resumo amostras=%.0f/s passos=%d quase=%d pico_max=%.2fm/s2 gravidade=%.2f andando=%b",
                        winEvents * 1e9 / span, winSteps, winNearMiss, winMaxExcess, gravity, down);
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
