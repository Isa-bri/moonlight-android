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

// Orientação da cabeça (celular no óculos, em paisagem) a partir do TYPE_GAME_ROTATION_VECTOR.
//
// Mundo: Z para cima, a direção do olhar na hora do recentramento vira +Y ("frente").
// Espaço do olho: X para a direita, Y para cima, olhando para -Z (convenção do OpenGL).
//
// O sensor chega a ~200 Hz, mas a tela só precisa da leitura mais recente uma vez por desenho:
// o callback só copia o vetor e as matrizes são calculadas em getWorldToEye(), na thread GL.
//
// Diagnóstico (VrLog, linha SENSOR[rótulo] uma vez por segundo): eventos/s do sensor, maior intervalo entre eventos,
// idade da amostra no momento da leitura (quanto o olhar está atrasado em relação ao sensor) e quantas leituras
// repetiram a amostra anterior (quem lê mais rápido que o sensor recebe passo zero e depois passo dobrado).
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

    // Estatísticas (sob `lock`)
    private long sampleSeq, readSeq;
    private long lastEventNs, prevEventNs;
    private boolean lastReadFresh = true;
    private long lastReadAgeNs;
    private long winStartNs, winEvents, winMaxGapNs;
    private long winReads, winStale, winAgeSumNs, winAgeMaxNs;
    private int accuracy = -1;

    // Só usados em getWorldToEye(), sempre da mesma thread
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

    // `label` diz de quem é a linha de log (cada dono registra o seu sensor): MOUSE, LADO, GL
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
        // Sem HIGH_SAMPLING_RATE_SENSORS (Android 12+) o sistema limita a 200 Hz; o log mostra se o limite pega
        boolean highRate = Build.VERSION.SDK_INT < 31
                || context.checkSelfPermission("android.permission.HIGH_SAMPLING_RATE_SENSORS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        VrLog.i("SENSOR", "[%s] iniciado ok=%b nome=\"%s\" atraso_min=%dus atraso_max=%dus fifo=%d alta_taxa=%b",
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

    // O próximo desenho vira a "frente"
    public void recenter() {
        synchronized (lock) {
            recenterRequested = true;
        }
        VrLog.i("SENSOR", "[%s] recentrar pedido", label);
    }

    // Chamado na thread do sensor a cada amostra nova (para quem quer reagir ao sensor em vez de sondar por tempo)
    public void setSampleListener(Runnable listener) {
        sampleListener = listener;
    }

    // A última leitura de getWorldToEye() trouxe uma amostra nova do sensor? (mesma thread de getWorldToEye)
    public boolean lastReadFresh() {
        synchronized (lock) {
            return lastReadFresh;
        }
    }

    // Idade da amostra usada na última leitura, em ns (mesma thread de getWorldToEye)
    public long lastReadAgeNs() {
        synchronized (lock) {
            return lastReadAgeNs;
        }
    }

    // Preenche a matriz mundo->olho (16 floats, column-major, só rotação) com a leitura mais recente.
    // false se ainda não há leitura. Chamar sempre da mesma thread (a thread GL).
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

        // Eixos do aparelho -> eixos da tela na rotação atual (o óculos usa paisagem)
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

        // O SensorManager entrega row-major; android.opengl.Matrix é column-major
        Matrix.transposeM(rotationCol, 0, remappedRow, 0);

        if (recenterNow) {
            // Olhar no mundo = R * (0, 0, -1) = -(terceira coluna)
            yaw = (float) Math.atan2(-rotationCol[8], -rotationCol[9]);
        }

        // Gira o mundo em torno de Z para o olhar inicial apontar para +Y
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
        VrLog.i("SENSOR", "[%s] eventos=%.0f/s gap_max=%.1fms leituras=%d repetidas=%d idade_med=%.2fms idade_max=%.2fms precisao=%d",
                label, events * 1e9 / windowNs, maxGap / 1e6, reads, stale,
                reads > 0 ? ageSum / 1e6 / reads : 0.0, ageMax / 1e6, acc);
    }

    // Precisão baixa do sensor (0-1) significa deriva: a câmera anda sozinha
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        synchronized (lock) {
            this.accuracy = accuracy;
        }
        VrLog.w("SENSOR", "[%s] precisao mudou para %d (0=inutilizavel 1=baixa 2=media 3=alta)", label, accuracy);
    }
}
