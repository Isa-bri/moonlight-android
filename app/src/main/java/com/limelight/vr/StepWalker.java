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
    private static final long RELEASE_AFTER_NS = 900_000_000L;
    private static final long LOG_WINDOW_NS = 1_000_000_000L;

    private final SensorManager sensorManager;
    private final NvConnection conn;
    private final float thresholdMs2;
    private final MoveStick stickSink; // não nulo = andar vira o analógico para a frente (em vez da tecla W)
    private final StepPipeline pipeline;

    private HandlerThread thread;
    private Handler handler;

    // Diagnóstico, só na thread do sensor
    private long lastStepNsWall; // nanoTime do último passo, para a cadência
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
        VrLog.i("PASSO", "iniciado ok=%b limiar=%.1fm/s2 intervalo_min=%dms solta_apos=%dms janela_confirma=%dms atraso_min=%dus",
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
                VrLog.i("PASSO", "%s desceu (segundo passo seguido)", stickSink != null ? "analogico" : "W");
            }
            VrLog.i("PASSO", "passo pico=%.2fm/s2 limiar=%.1f intervalo=%dms%s", detector.excess, thresholdMs2,
                    detector.sinceLastStepMs, detector.walking ? "" : " (isolado, espera o segundo)");
        }
        if (pipeline.releasedNow) {
            long walked = Math.max(1, lastStepNsWall - pipeline.walkStartNs);
            VrLog.i("PASSO", "%s subiu passos=%d andou=%.1fs cadencia=%.2f passos/s (solta %dms depois do ultimo)",
                    stickSink != null ? "analogico" : "W", pipeline.walkSteps,
                    (event.timestamp - pipeline.walkStartNs) / 1e9,
                    pipeline.walkSteps > 1 ? (pipeline.walkSteps - 1) * 1e9 / Math.max(1, (event.timestamp - pipeline.walkStartNs - RELEASE_AFTER_NS)) : 0.0,
                    RELEASE_AFTER_NS / 1_000_000);
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
