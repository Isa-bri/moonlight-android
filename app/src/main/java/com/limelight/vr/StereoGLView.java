package com.limelight.vr;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;
import android.view.Choreographer;
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

    // Passe de distorção: lê a cena do olho (textura 2D) e a deforma em "barril" para cancelar a
    // almofada das lentes. s = p * (1 + k1 r^2), com p em [-1, 1] centrado na lente
    private static final String DIST_VERTEX_SHADER =
            "attribute vec2 aPos;\n" +
            "varying vec2 vPos;\n" +
            "void main() {\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "  vPos = aPos;\n" +
            "}\n";

    private static final String DIST_FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "uniform sampler2D uScene;\n" +
            "uniform float uK1;\n" +
            "uniform float uCa;\n" +
            "varying vec2 vPos;\n" +
            "// Uma cor por vez: a lente refrata cada comprimento de onda um pouco diferente\n" +
            "vec3 sampleAt(float k, float r2, float channel) {\n" +
            "  vec2 s = vPos * (1.0 + k * r2) * 0.5 + 0.5;\n" +
            "  if (s.x < 0.0 || s.x > 1.0 || s.y < 0.0 || s.y > 1.0) {\n" +
            "    return vec3(0.0);\n" +
            "  }\n" +
            "  return texture2D(uScene, s).rgb * vec3(channel == 0.0 ? 1.0 : 0.0, channel == 1.0 ? 1.0 : 0.0, channel == 2.0 ? 1.0 : 0.0);\n" +
            "}\n" +
            "void main() {\n" +
            "  float r2 = dot(vPos, vPos);\n" +
            "  vec3 c = sampleAt(uK1 * (1.0 - uCa), r2, 0.0) + sampleAt(uK1, r2, 1.0) + sampleAt(uK1 * (1.0 + uCa), r2, 2.0);\n" +
            "  gl_FragColor = vec4(c, 1.0);\n" +
            "}\n";

    private static final float[] QUAD = {-1, -1, 1, -1, -1, 1, 1, 1};

    // Campo de visão vertical por olho e distância entre os olhos (valores provisórios até a etapa 4).
    // 80 graus: com lentes de 40 mm e ~45 mm de distância focal (suposição para o Shinecon G13), a
    // metade da tela do S23 Ultra (~78 x 73 mm) cobre cerca de 80 x 79 graus
    private static final float FOV_Y_DEGREES = 80.0f;

    private final int videoWidth;
    private final int videoHeight;
    private final boolean measureLatency;
    private final boolean headTracking;
    private final float lensK1;
    private final float chromatic;
    private final float ipd; // metros
    private final float pixelsPerMeter;
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
    private int distProgram;
    private int distPosLoc;
    private int distSceneLoc;
    private int distK1Loc;
    private int distCaLoc;
    private int fbo;
    private int fboTex;
    private int fboWidth;
    private int fboHeight;
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
    private long statsLastFrameNs;
    private long statsMaxGapNs;
    private int statsSlowGaps;

    // Teste de latência do mouse (debug): lê 1 pixel do centro do olho esquerdo a cada quadro novo e
    // registra o instante em que a luminância muda (o host pisca a tela quando o mouse mexe)
    private volatile boolean probe;
    private final java.nio.ByteBuffer probePixel = java.nio.ByteBuffer.allocateDirect(4);
    private int probeLuma = -1;

    // screenDistance e screenHeight (metros) definem a tela virtual do modo cinema; a largura vem da proporção do vídeo
    public StereoGLView(Context context, int videoWidth, int videoHeight, boolean measureLatency,
                        boolean headTracking, float screenDistance, float screenHeight, float lensK1, float chromatic, float ipd, SurfaceListener listener) {
        super(context);
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
        this.measureLatency = measureLatency;
        this.listener = listener;
        this.lensK1 = lensK1;
        this.chromatic = chromatic;
        this.ipd = ipd;
        this.pixelsPerMeter = context.getResources().getDisplayMetrics().xdpi / 0.0254f;

        Matrix.setIdentityM(identity, 0);

        // Quad (x, y) em [-1, 1] -> plano vertical a screenDistance à frente (+Y), centrado na altura dos olhos
        float halfWidth = screenHeight * videoWidth / videoHeight / 2;
        float halfHeight = screenHeight / 2;
        System.arraycopy(new float[] {
                halfWidth, 0, 0, 0,
                0, 0, halfHeight, 0,
                0, 1, 0, 0,
                0, screenDistance, 0, 1
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
        // Desenha quando chega um quadro de vídeo e, com head tracking, também a cada vsync (ver startVsync)
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (headTracking) {
            if (headTracker.start()) {
                startVsync();
            }
            else {
                VrLog.w("GL:", "Sem TYPE_GAME_ROTATION_VECTOR neste aparelho; usando a tela fixa");
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopVsync();
        headTracker.stop();
        super.onDetachedFromWindow();
    }

    // Um pedido de desenho por vsync, para a cabeça se mover mesmo entre quadros de vídeo. Roda numa
    // thread própria (cada Looper tem o seu Choreographer) para não depender da thread principal.
    private HandlerThread vsyncThread;
    private volatile boolean vsyncRunning;

    private void startVsync() {
        vsyncThread = new HandlerThread("DeckVR-Vsync", Process.THREAD_PRIORITY_URGENT_DISPLAY);
        vsyncThread.start();
        vsyncRunning = true;
        new Handler(vsyncThread.getLooper()).post(() -> {
            Choreographer choreographer = Choreographer.getInstance();
            choreographer.postFrameCallback(new Choreographer.FrameCallback() {
                @Override
                public void doFrame(long frameTimeNanos) {
                    if (vsyncRunning) {
                        requestRender();
                        choreographer.postFrameCallback(this);
                    }
                }
            });
        });
    }

    private void stopVsync() {
        vsyncRunning = false;
        if (vsyncThread != null) {
            vsyncThread.quitSafely();
            vsyncThread = null;
        }
    }

    // Recentra a tela virtual na direção atual do olhar
    public void recenter() {
        headTracker.recenter();
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        // Troca assíncrona: o quadro novo substitui o que ainda não foi exibido, então não se forma
        // fila de buffers à frente do vsync (cada desenho entra na tela no vsync seguinte) e o
        // eglSwapBuffers não bloqueia a thread GL
        EGL14.eglSwapInterval(EGL14.eglGetCurrentDisplay(), 0);
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        posLoc = GLES20.glGetAttribLocation(program, "aPos");
        texMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix");
        mvpLoc = GLES20.glGetUniformLocation(program, "uMvp");

        distProgram = buildProgram(DIST_VERTEX_SHADER, DIST_FRAGMENT_SHADER);
        distPosLoc = GLES20.glGetAttribLocation(distProgram, "aPos");
        distSceneLoc = GLES20.glGetUniformLocation(distProgram, "uScene");
        distK1Loc = GLES20.glGetUniformLocation(distProgram, "uK1");
        distCaLoc = GLES20.glGetUniformLocation(distProgram, "uCa");
        fbo = 0;
        fboTex = 0;
        fboWidth = fboHeight = 0;

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

        // O centro de cada olho fica a ±IPD/2 do centro da tela (as lentes do óculos são ajustadas
        // para a IPD); a largura da imagem é limitada para não cruzar a metade da tela
        int offset = Math.round(ipd * pixelsPerMeter / 2);
        int eyeWidth = Math.max(1, Math.min(viewWidth / 2, 2 * Math.min(viewWidth / 2 - offset, offset)));
        boolean tracked = headTracking && headTracker.getWorldToEye(worldToEye);
        if (tracked) {
            Matrix.perspectiveM(projection, 0, FOV_Y_DEGREES, (float) eyeWidth / viewHeight, 0.1f, 20f);
        }
        boolean distort = lensK1 > 0 && ensureFbo(eyeWidth, viewHeight);

        for (int eye = 0; eye < 2; eye++) {
            int centerX = viewWidth / 2 + (eye == 0 ? -offset : offset);
            int x = centerX - eyeWidth / 2;
            if (distort) {
                // Cena do olho na textura; depois ela é deformada para a região do olho
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                drawEye(eye, 0, eyeWidth, tracked);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
                GLES20.glScissor(eye * (viewWidth / 2), 0, viewWidth / 2, viewHeight);
                drawDistorted(x, eyeWidth);
            }
            else {
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
                GLES20.glScissor(eye * (viewWidth / 2), 0, viewWidth / 2, viewHeight);
                drawEye(eye, x, eyeWidth, tracked);
            }
        }
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);

        if (probe && newFrame) {
            GLES20.glReadPixels(viewWidth / 2 - offset, viewHeight / 2, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, probePixel);
            int luma = ((probePixel.get(0) & 0xFF) + (probePixel.get(1) & 0xFF) + (probePixel.get(2) & 0xFF)) / 3;
            if (probeLuma >= 0 && Math.abs(luma - probeLuma) > 60) {
                Log.i("DeckVR", "PROBE mudou " + probeLuma + "->" + luma + " t=" + System.nanoTime());
            }
            probeLuma = luma;
        }

        // Só mede desenhos que consumiram um quadro novo (não os de rotação/redimensionamento)
        if (measureLatency && newFrame) {
            GLES20.glFinish();
            recordLatency(System.nanoTime() - frameAvailableNs);
        }
    }

    // Cena de um olho na região [x, x + width) x [0, viewHeight) do framebuffer atual
    private void drawEye(int eye, int x, int width, boolean tracked) {
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, texMatrix, 0);
        GLES20.glEnableVertexAttribArray(posLoc);
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, quad);

        if (tracked) {
            // Tela virtual no mundo; cada olho enxerga a partir do seu lado (±IPD/2)
            float shift = (eye == 0 ? 1 : -1) * ipd / 2;
            Matrix.setIdentityM(eyeShift, 0);
            Matrix.translateM(eyeShift, 0, shift, 0, 0);
            Matrix.multiplyMM(eyeView, 0, eyeShift, 0, worldToEye, 0);
            Matrix.multiplyMM(mvp, 0, eyeView, 0, screenModel, 0);
            Matrix.multiplyMM(mvp, 0, projection, 0, mvp, 0);

            GLES20.glViewport(x, 0, width, viewHeight);
            GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvp, 0);
        }
        else {
            // O vídeo ocupa o maior retângulo com a proporção original (barras pretas no resto)
            float videoAspect = (float) videoWidth / videoHeight;
            int w = width;
            int h = (int) (w / videoAspect);
            if (h > viewHeight) {
                h = viewHeight;
                w = (int) (h * videoAspect);
            }
            GLES20.glViewport(x + (width - w) / 2, (viewHeight - h) / 2, w, h);
            GLES20.glUniformMatrix4fv(mvpLoc, 1, false, identity, 0);
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(posLoc);
    }

    private void drawDistorted(int x, int width) {
        GLES20.glUseProgram(distProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex);
        GLES20.glUniform1i(distSceneLoc, 0);
        GLES20.glUniform1f(distK1Loc, lensK1);
        GLES20.glUniform1f(distCaLoc, chromatic);
        GLES20.glEnableVertexAttribArray(distPosLoc);
        GLES20.glVertexAttribPointer(distPosLoc, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glViewport(x, 0, width, viewHeight);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(distPosLoc);
    }

    // Framebuffer com uma textura do tamanho de um olho; false se o aparelho não o aceitar
    private boolean ensureFbo(int width, int height) {
        if (fbo != 0 && fboWidth == width && fboHeight == height) {
            return true;
        }
        int[] id = new int[1];
        if (fboTex == 0) {
            GLES20.glGenTextures(1, id, 0);
            fboTex = id[0];
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        if (fbo == 0) {
            GLES20.glGenFramebuffers(1, id, 0);
            fbo = id[0];
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, fboTex, 0);
        boolean ok = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (!ok) {
            VrLog.w("GL:", "Framebuffer incompleto; sem correção de distorção");
            return false;
        }
        fboWidth = width;
        fboHeight = height;
        return true;
    }

    public void setProbe(boolean on) {
        probeLuma = -1;
        probe = on;
    }

    private void recordLatency(long ns) {
        long now = System.nanoTime();
        if (statsFrames == 0) {
            statsStartNs = now;
        }
        statsFrames++;
        statsSumNs += ns;
        statsMaxNs = Math.max(statsMaxNs, ns);
        // Intervalo entre quadros de vídeo desenhados: a imagem "trava" quando ele passa de ~2 quadros (33 ms a 60 fps)
        if (statsLastFrameNs != 0) {
            long gap = now - statsLastFrameNs;
            statsMaxGapNs = Math.max(statsMaxGapNs, gap);
            if (gap > 33_000_000L) statsSlowGaps++;
        }
        statsLastFrameNs = now;

        if (now - statsStartNs >= 2_000_000_000L) {
            VrLog.i("GL:", "%d quadros em %.1f s, atraso quadro->desenho medio %.2f ms, max %.2f ms, intervalo max %.1f ms, travadas(>33ms) %d",
                    statsFrames, (now - statsStartNs) / 1e9, statsSumNs / 1e6 / statsFrames, statsMaxNs / 1e6,
                    statsMaxGapNs / 1e6, statsSlowGaps);
            statsFrames = 0;
            statsSumNs = 0;
            statsMaxNs = 0;
            statsMaxGapNs = 0;
            statsSlowGaps = 0;
        }
    }

    private static int buildProgram(String vertexSource, String fragmentSource) {
        int vs = compile(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
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
