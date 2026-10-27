package com.limelight.vr;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

// Modo VR: o MediaCodec decodifica para um SurfaceTexture (textura OES) e esta view desenha a
// mesma textura duas vezes, uma em cada metade da tela (olho esquerdo/direito).
//
// - Sem headTracking (etapa 2): o vídeo ocupa o maior retângulo com a proporção original em cada metade.
// - Com headTracking (etapa 3, "cinema"): o vídeo é uma tela virtual fixa no mundo, a uma distância fixa,
//   e o giroscópio (HeadTracker) move a câmera. Ainda sem distorção de lente.
public class StereoGLView extends GLSurfaceView implements GLSurfaceView.Renderer,
        SurfaceTexture.OnFrameAvailableListener {

    public interface SurfaceListener {
        // Chamado na thread GL assim que a Surface do decodificador existe
        void onVideoSurfaceReady(Surface surface);
    }

    private static final String VERTEX_SHADER =
            "uniform mat4 uTexMatrix;\n" +
            "uniform mat4 uMvp;\n" +
            "attribute vec2 aPos;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "  gl_Position = uMvp * vec4(aPos, 0.0, 1.0);\n" +
            "  vTex = (uTexMatrix * vec4(aPos * 0.5 + 0.5, 0.0, 1.0)).xy;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES uTex;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(uTex, vTex);\n" +
            "}\n";

    private static final float[] QUAD = {-1, -1, 1, -1, -1, 1, 1, 1};

    // Tela virtual do modo cinema (metros): altura fixa, largura pela proporção do vídeo
    private static final float SCREEN_DISTANCE = 2.0f;
    private static final float SCREEN_HEIGHT = 1.2f;
    // Campo de visão vertical por olho e distância entre os olhos (valores provisórios até a etapa 4)
    private static final float FOV_Y_DEGREES = 90.0f;
    private static final float IPD = 0.063f;

    private final int videoWidth;
    private final int videoHeight;
    private final boolean measureLatency;
    private final boolean headTracking;
    private final HeadTracker headTracker;
    private final SurfaceListener listener;
    private final FloatBuffer quad;
    private final float[] texMatrix = new float[16];
    private final float[] identity = new float[16];
    private final float[] screenModel = new float[16];
    private final float[] projection = new float[16];
    private final float[] worldToEye = new float[16];
    private final float[] eyeView = new float[16];
    private final float[] eyeShift = new float[16];
    private final float[] mvp = new float[16];

    private SurfaceTexture surfaceTexture;
    private Surface videoSurface;
    private int program;
    private int texId;
    private int posLoc;
    private int texMatrixLoc;
    private int mvpLoc;
    private int viewWidth;
    private int viewHeight;
    private volatile boolean frameAvailable;

    // Latência extra do caminho GL: do onFrameAvailable até o fim do desenho (com glFinish,
    // só quando measureLatency). Não inclui o swap/composição nem o tempo até os fótons.
    private volatile long frameAvailableNs;
    private long statsStartNs;
    private int statsFrames;
    private long statsSumNs;
    private long statsMaxNs;

    public StereoGLView(Context context, int videoWidth, int videoHeight, boolean measureLatency,
                        boolean headTracking, SurfaceListener listener) {
        super(context);
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
        this.measureLatency = measureLatency;
        this.listener = listener;

        Matrix.setIdentityM(identity, 0);

        // Quad (x, y) em [-1, 1] -> plano vertical a SCREEN_DISTANCE à frente (+Y), centrado na altura dos olhos
        float halfWidth = SCREEN_HEIGHT * videoWidth / videoHeight / 2;
        float halfHeight = SCREEN_HEIGHT / 2;
        System.arraycopy(new float[] {
                halfWidth, 0, 0, 0,
                0, 0, halfHeight, 0,
                0, 1, 0, 0,
                0, SCREEN_DISTANCE, 0, 1
        }, 0, screenModel, 0, 16);

        this.headTracker = new HeadTracker(context);
        this.headTracking = headTracking;

        quad = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(QUAD).position(0);

        // Toques e foco continuam indo para o StreamView que fica por baixo
        setClickable(false);
        setFocusable(false);

        setEGLContextClientVersion(2);
        setRenderer(this);
        // Com head tracking desenha a cada vsync usando a leitura mais recente do sensor (200 Hz
        // de redesenhos disputariam a thread GL com os quadros de vídeo); sem ele, só quando chega quadro
        setRenderMode(headTracking ? RENDERMODE_CONTINUOUSLY : RENDERMODE_WHEN_DIRTY);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (headTracking && !headTracker.start()) {
            Log.w("DeckVR", "Sem TYPE_GAME_ROTATION_VECTOR neste aparelho; usando a tela fixa");
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        headTracker.stop();
        super.onDetachedFromWindow();
    }

    // Recentra a tela virtual na direção atual do olhar
    public void recenter() {
        headTracker.recenter();
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        program = buildProgram();
        posLoc = GLES20.glGetAttribLocation(program, "aPos");
        texMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix");
        mvpLoc = GLES20.glGetUniformLocation(program, "uMvp");

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        texId = tex[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // A textura (e a Surface) é recriada se o contexto GL for perdido
        if (videoSurface != null) {
            videoSurface.release();
            surfaceTexture.release();
        }
        surfaceTexture = new SurfaceTexture(texId);
        surfaceTexture.setDefaultBufferSize(videoWidth, videoHeight);
        surfaceTexture.setOnFrameAvailableListener(this);
        videoSurface = new Surface(surfaceTexture);

        listener.onVideoSurfaceReady(videoSurface);
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        viewWidth = width;
        viewHeight = height;
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        frameAvailableNs = System.nanoTime();
        frameAvailable = true;
        requestRender();
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        boolean newFrame = frameAvailable;
        if (newFrame) {
            frameAvailable = false;
            surfaceTexture.updateTexImage();
            surfaceTexture.getTransformMatrix(texMatrix);
        }

        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, texMatrix, 0);
        GLES20.glEnableVertexAttribArray(posLoc);
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, quad);

        int eyeWidth = viewWidth / 2;
        if (headTracking && headTracker.getWorldToEye(worldToEye)) {
            // Tela virtual no mundo; cada olho enxerga a partir do seu lado (±IPD/2)
            Matrix.perspectiveM(projection, 0, FOV_Y_DEGREES, (float) eyeWidth / viewHeight, 0.1f, 20f);
            for (int eye = 0; eye < 2; eye++) {
                float shift = (eye == 0 ? 1 : -1) * IPD / 2;
                Matrix.setIdentityM(eyeShift, 0);
                Matrix.translateM(eyeShift, 0, shift, 0, 0);
                Matrix.multiplyMM(eyeView, 0, eyeShift, 0, worldToEye, 0);
                Matrix.multiplyMM(mvp, 0, eyeView, 0, screenModel, 0);
                Matrix.multiplyMM(mvp, 0, projection, 0, mvp, 0);

                GLES20.glViewport(eye * eyeWidth, 0, eyeWidth, viewHeight);
                GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvp, 0);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            }
        }
        else {
            // Cada olho recebe metade da tela; dentro dela o vídeo ocupa o maior retângulo
            // com a proporção original (barras pretas no resto)
            float videoAspect = (float) videoWidth / videoHeight;
            int w = eyeWidth;
            int h = (int) (w / videoAspect);
            if (h > viewHeight) {
                h = viewHeight;
                w = (int) (h * videoAspect);
            }
            int y = (viewHeight - h) / 2;
            GLES20.glUniformMatrix4fv(mvpLoc, 1, false, identity, 0);
            for (int eye = 0; eye < 2; eye++) {
                GLES20.glViewport(eye * eyeWidth + (eyeWidth - w) / 2, y, w, h);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            }
        }

        GLES20.glDisableVertexAttribArray(posLoc);

        // Só mede desenhos que consumiram um quadro novo (não os de rotação/redimensionamento)
        if (measureLatency && newFrame) {
            GLES20.glFinish();
            recordLatency(System.nanoTime() - frameAvailableNs);
        }
    }

    private void recordLatency(long ns) {
        long now = System.nanoTime();
        if (statsFrames == 0) {
            statsStartNs = now;
        }
        statsFrames++;
        statsSumNs += ns;
        statsMaxNs = Math.max(statsMaxNs, ns);

        if (now - statsStartNs >= 2_000_000_000L) {
            Log.i("DeckVR", String.format("GL: %d quadros em %.1f s, atraso quadro->desenho medio %.2f ms, max %.2f ms",
                    statsFrames, (now - statsStartNs) / 1e9, statsSumNs / 1e6 / statsFrames, statsMaxNs / 1e6));
            statsFrames = 0;
            statsSumNs = 0;
            statsMaxNs = 0;
        }
    }

    private static int buildProgram() {
        int vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        int prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, vs);
        GLES20.glAttachShader(prog, fs);
        GLES20.glLinkProgram(prog);
        int[] status = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            throw new IllegalStateException("Falha ao ligar o programa GL: " + GLES20.glGetProgramInfoLog(prog));
        }
        return prog;
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            throw new IllegalStateException("Falha ao compilar o shader GL: " + GLES20.glGetShaderInfoLog(shader));
        }
        return shader;
    }
}
