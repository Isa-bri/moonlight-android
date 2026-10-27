package com.limelight.vr;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

// Modo VR (etapa 2): o MediaCodec decodifica para um SurfaceTexture (textura OES) e esta view
// desenha a mesma textura duas vezes, uma em cada metade da tela (olho esquerdo/direito),
// preservando a proporção do vídeo. Sem distorção de lente nem rastreamento ainda.
public class StereoGLView extends GLSurfaceView implements GLSurfaceView.Renderer,
        SurfaceTexture.OnFrameAvailableListener {

    public interface SurfaceListener {
        // Chamado na thread GL assim que a Surface do decodificador existe
        void onVideoSurfaceReady(Surface surface);
    }

    private static final String VERTEX_SHADER =
            "uniform mat4 uTexMatrix;\n" +
            "attribute vec2 aPos;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
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

    private final int videoWidth;
    private final int videoHeight;
    private final boolean measureLatency;
    private final SurfaceListener listener;
    private final FloatBuffer quad;
    private final float[] texMatrix = new float[16];

    private SurfaceTexture surfaceTexture;
    private Surface videoSurface;
    private int program;
    private int texId;
    private int posLoc;
    private int texMatrixLoc;
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
                        SurfaceListener listener) {
        super(context);
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
        this.measureLatency = measureLatency;
        this.listener = listener;

        quad = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(QUAD).position(0);

        // Toques e foco continuam indo para o StreamView que fica por baixo
        setClickable(false);
        setFocusable(false);

        setEGLContextClientVersion(2);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        program = buildProgram();
        posLoc = GLES20.glGetAttribLocation(program, "aPos");
        texMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix");

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

        // Cada olho recebe metade da tela; dentro dela o vídeo ocupa o maior retângulo
        // com a proporção original (barras pretas no resto)
        int eyeWidth = viewWidth / 2;
        float videoAspect = (float) videoWidth / videoHeight;
        int w = eyeWidth;
        int h = (int) (w / videoAspect);
        if (h > viewHeight) {
            h = viewHeight;
            w = (int) (h * videoAspect);
        }
        int y = (viewHeight - h) / 2;
        for (int eye = 0; eye < 2; eye++) {
            GLES20.glViewport(eye * eyeWidth + (eyeWidth - w) / 2, y, w, h);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
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
