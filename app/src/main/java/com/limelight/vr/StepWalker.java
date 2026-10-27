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
    private static final long MIN_STEP_INTERVAL_NS = 380_000_000L;
    private static final long ARM_WINDOW_NS = 1_200_000_000L;
    private static final long RELEASE_AFTER_MS = 900;
    private static final long LOG_WINDOW_NS = 1_000_000_000L;

    private final SensorManager sensorManager;
    private final NvConnection conn;
    private final float thresholdMs2;

    private HandlerThread thread;
    private Handler handler;

    // Só usados na thread do sensor
    private volatile boolean down;
    private long lastStepNsWall; // nanoTime do último passo, para a cadência
    private final Runnable release = this::releaseKey;

    // Diagnóstico, só na thread do sensor
    private final StepDetector detector;
    private int walkSteps;
    private long walkStartNs;
    private long winStartNs, winEvents;
    private int winSteps, winNearMiss;
    private float winMaxExcess;

    public StepWalker(Context context, NvConnection conn, float thresholdMs2) {
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.conn = conn;
        this.thresholdMs2 = thresholdMs2;
        this.detector = new StepDetector(thresholdMs2, MIN_STEP_INTERVAL_NS, ARM_WINDOW_NS);
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
        VrLog.i("PASSO", "iniciado ok=%b limiar=%.1fm/s2 intervalo_min=%dms solta_apos=%dms janela_confirma=%dms atraso_min=%dus",
                ok, thresholdMs2, MIN_STEP_INTERVAL_NS / 1_000_000, RELEASE_AFTER_MS, ARM_WINDOW_NS / 1_000_000,
                sensor.getMinDelay());
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
        boolean step = detector.onSample(event.values[0], event.values[1], event.values[2], event.timestamp);
        if (step) {
            lastStepNsWall = System.nanoTime();
            winSteps++;
            if (detector.walking) {
                if (!down) {
                    down = true;
                    walkSteps = 0;
                    walkStartNs = lastStepNsWall;
                    conn.sendKeyboardInput(KEY_W, KeyboardPacket.KEY_DOWN, (byte) 0, (byte) 0);
                    VrLog.i("PASSO", "W desceu (segundo passo seguido)");
                }
                walkSteps++;
                handler.removeCallbacks(release);
                handler.postDelayed(release, RELEASE_AFTER_MS);
            }
            VrLog.i("PASSO", "passo pico=%.2fm/s2 limiar=%.1f intervalo=%dms%s", detector.excess, thresholdMs2,
                    detector.sinceLastStepMs, detector.walking ? "" : " (isolado, espera o segundo)");
        }
        if (detector.bumpWasMiss) {
            winNearMiss++;
            VrLog.i("PASSO", "quase passo pico=%.2fm/s2 limiar=%.1f motivo=%s", detector.missPeak, thresholdMs2,
                    detector.missTooSoon ? "perto_do_passo_anterior" : "abaixo_do_limiar");
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
                VrLog.i("PASSO", "resumo amostras=%.0f/s passos=%d quase=%d pico_max=%.2fm/s2 gravidade=%.2f andando=%b",
                        winEvents * 1e9 / span, winSteps, winNearMiss, winMaxExcess, detector.gravity(), down);
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
