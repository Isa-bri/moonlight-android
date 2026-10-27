package com.limelight.vr;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import android.view.WindowManager;

// Orientação da cabeça (celular no óculos, em paisagem) a partir do TYPE_GAME_ROTATION_VECTOR.
//
// Mundo: Z para cima, a direção do olhar na hora do recentramento vira +Y ("frente").
// Espaço do olho: X para a direita, Y para cima, olhando para -Z (convenção do OpenGL).
//
// O sensor chega a ~200 Hz, mas a tela só precisa da leitura mais recente uma vez por desenho:
// o callback só copia o vetor e as matrizes são calculadas em getWorldToEye(), na thread GL.
public class HeadTracker implements SensorEventListener {
    private final SensorManager sensorManager;
    private final WindowManager windowManager;

    private HandlerThread thread;

    private final Object lock = new Object();
    private final float[] rawVector = new float[5];
    private int rawLength;
    private boolean hasRaw;
    private boolean recenterRequested = true;

    // Só usados em getWorldToEye(), sempre da mesma thread
    private final float[] vector = new float[5];
    private final float[] rotationRow = new float[16];
    private final float[] remappedRow = new float[16];
    private final float[] rotationCol = new float[16];
    private final float[] yawFix = new float[16];
    private final float[] eyeToWorld = new float[16];
    private float yaw;

    public HeadTracker(Context context) {
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
        return sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST,
                new Handler(thread.getLooper()));
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
        synchronized (lock) {
            rawLength = Math.min(event.values.length, rawVector.length);
            System.arraycopy(event.values, 0, rawVector, 0, rawLength);
            hasRaw = true;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
