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
public class HeadTracker implements SensorEventListener {
    private final SensorManager sensorManager;
    private final WindowManager windowManager;

    private HandlerThread thread;

    private final Object lock = new Object();
    private final float[] rotationRow = new float[16];
    private final float[] remappedRow = new float[16];
    private final float[] rotationCol = new float[16];
    private final float[] yawFix = new float[16];
    private final float[] eyeToWorld = new float[16];
    private final float[] worldToEye = new float[16];
    private boolean hasSample;
    private boolean recenterRequested = true;
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
            hasSample = false;
            recenterRequested = true;
        }
    }

    // O próximo quadro de sensor vira a "frente"
    public void recenter() {
        synchronized (lock) {
            recenterRequested = true;
        }
    }

    // Copia a matriz mundo->olho (16 floats, column-major, só rotação). false se ainda não há leitura.
    public boolean getWorldToEye(float[] out) {
        synchronized (lock) {
            if (!hasSample) {
                return false;
            }
            System.arraycopy(worldToEye, 0, out, 0, 16);
            return true;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        SensorManager.getRotationMatrixFromVector(rotationRow, event.values);

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

        synchronized (lock) {
            if (recenterRequested) {
                // Olhar no mundo = R * (0, 0, -1) = -(terceira coluna)
                float gazeX = -rotationCol[8];
                float gazeY = -rotationCol[9];
                yaw = (float) Math.atan2(gazeX, gazeY);
                recenterRequested = false;
            }

            // Gira o mundo em torno de Z para o olhar inicial apontar para +Y
            Matrix.setRotateM(yawFix, 0, (float) Math.toDegrees(yaw), 0, 0, 1);
            Matrix.multiplyMM(eyeToWorld, 0, yawFix, 0, rotationCol, 0);
            Matrix.transposeM(worldToEye, 0, eyeToWorld, 0);
            hasSample = true;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
