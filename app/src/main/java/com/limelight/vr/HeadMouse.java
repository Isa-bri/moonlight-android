package com.limelight.vr;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import com.limelight.nvstream.NvConnection;

// Cabeça = câmera: transforma a rotação da cabeça (yaw/pitch do olhar) em movimento relativo de mouse.
//
// Usa a mesma orientação do HeadTracker, mas só os deltas entre leituras; o recentramento do
// HeadTracker (rotação em torno de Z) não altera yaw/pitch relativos. Girar para a direita move o mouse
// para a direita; olhar para cima move o mouse para cima (y positivo é para baixo).
//
// Giro na borda: passando de `edgeDeg` graus para um lado do ponto central (onde a cabeça estava ao começar ou ao soltar
// a embreagem), a câmera continua girando sozinha, mais rápido quanto mais longe, para virar além do alcance do pescoço.
//
// Zona morta vertical ao andar: enquanto andar (W/A/S/D segurado por outra classe) o pitch só passa depois de sair de
// uma faixa de `walkPitchBandDeg` graus em volta da última posição, o que esconde o balanço da cabeça a cada passo.
//
// Diagnóstico (VrLog, linha HM uma vez por segundo + eventos): os contadores ficam só na thread do tick; nada é
// impresso por tick. Campos da linha HM:
//   ticks / periodo_max / atrasados   ritmo do laço de 4 ms (atrasado = mais de 8 ms desde o tick anterior)
//   sem_amostra / idade_max           ticks que releram a mesma amostra do sensor; idade da amostra mais velha usada
//   enviados / soma / max / uns       pacotes de mouse; soma e maior |contagem| em x,y por pacote; pacotes de 1 contagem
//   inversoes                         trocas de sinal do x entre pacotes seguidos (tremor)
//   pulos                             ticks com giro maior que 3° (salto do sensor ou tick muito atrasado)
//   zona / clamp                      giros descartados pela zona morta; pacotes cortados em 16 bits
//   percorrido                        graus de yaw,pitch realmente percorridos (soma/percorrido = contagens por grau)
//   envio_med / envio_max             tempo dentro de sendMouseMove
//   borda / andando / banda           ms com giro na borda, ms andando, graus de pitch engolidos pela zona morta vertical
public class HeadMouse {
    public interface WalkSource {
        boolean isWalking();
    }

    private static final long PERIOD_MS = 4; // ~250 Hz, acima do sensor (~200 Hz)
    private static final float DEADZONE_DEG = 0.005f;
    private static final float EDGE_RAMP_DEG = 15f;       // da borda até a velocidade máxima
    private static final float EDGE_MAX_DEG_PER_S = 120f; // velocidade máxima do giro na borda

    private static final long LOG_WINDOW_NS = 1_000_000_000L;
    private static final long LATE_TICK_NS = 2 * PERIOD_MS * 1_000_000L;
    private static final float JUMP_DEG = 3f;       // giro por tick que não é de cabeça humana
    private static final int VERBOSE_SPIKE_LIMIT = 10; // linhas de pico por segundo no modo verbose

    private final HeadTracker tracker;
    private final NvConnection conn;
    private final float countsPerDegree;
    private final float pitchCountsPerDegree;

    private HandlerThread thread;
    private Handler handler;
    private volatile boolean running;
    private volatile boolean toggledPause;
    private volatile boolean clutch;

    private final float[] worldToEye = new float[16];
    private boolean hasPrev;
    private float prevYaw, prevPitch;
    private float remX, remY;
    private float centerYaw;
    private long prevNs;
    private float anchorPitch;
    private boolean wasWalking;

    private volatile float edgeDeg;          // 0 = desligado
    private volatile float walkPitchBandDeg; // 0 = desligado
    private volatile WalkSource walkSource;

    // Diagnóstico, só na thread do tick
    private long winStartNs, lastPollNs;
    private int sTicks, sLate, sStale, sPaused, sSent, sOnes, sFlips, sJumps, sDeadzone, sClamp, sSpikeLogs;
    private long sMaxPeriodNs, sAgeMaxNs, sSendSumNs, sSendMaxNs, sEdgeNs, sWalkNs;
    private long sSumX, sSumY;
    private int sMaxX, sMaxY, lastSignX;
    private float sYawPath, sPitchPath, sBandAbsorbed;
    private long pausedSinceNs;
    private float pausedYaw, pausedPitch;
    private boolean pausedByClutch;
    private boolean edgeActive;
    private long edgeStartNs;
    private float edgeTurned;
    private long walkStartNs;
    private float walkAbsorbed;

    // Só para testes por broadcast: soma um desvio fixo e uma oscilação de pitch à leitura do sensor e pode fingir que anda
    private volatile float testYaw, testPitch, testBobAmp, testBobHz;
    private volatile boolean testWalking;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            poll();
            handler.postDelayed(this, PERIOD_MS);
        }
    };

    public HeadMouse(Context context, NvConnection conn, float countsPerDegree, float pitchScale) {
        this.tracker = new HeadTracker(context, "MOUSE");
        this.conn = conn;
        this.countsPerDegree = countsPerDegree;
        this.pitchCountsPerDegree = countsPerDegree * pitchScale;
    }

    public void setEdgeTurn(float edgeDeg) {
        this.edgeDeg = edgeDeg;
    }

    public void setTestOffsets(float yaw, float pitch, float bobAmp, float bobHz, boolean walking) {
        testYaw = yaw;
        testPitch = pitch;
        testBobAmp = bobAmp;
        testBobHz = bobHz;
        testWalking = walking;
    }

    public void setWalkPitchBand(float bandDeg, WalkSource source) {
        this.walkPitchBandDeg = bandDeg;
        this.walkSource = source;
    }

    public boolean start() {
        if (!tracker.start()) {
            return false;
        }
        thread = new HandlerThread("DeckVR-HeadMouse", Process.THREAD_PRIORITY_URGENT_DISPLAY);
        thread.start();
        handler = new Handler(thread.getLooper());
        running = true;
        handler.post(tick);
        return true;
    }

    public void stop() {
        running = false;
        VrLog.i("HM", "parou");
        tracker.stop();
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
    }

    // Liga/desliga o envio; ao voltar, o movimento parte da pose atual (sem pulo)
    public boolean togglePause() {
        toggledPause = !toggledPause;
        VrLog.i("VOL", "pausa %s", toggledPause ? "ligada" : "desligada");
        return toggledPause;
    }

    // Embreagem: enquanto segurada, a cabeça gira sem mexer a câmera (como levantar o mouse da mesa)
    public void setClutch(boolean held) {
        if (clutch != held) {
            VrLog.i("VOL", "embreagem %s", held ? "segurada" : "solta");
        }
        clutch = held;
    }

    private void poll() {
        long nowNs = System.nanoTime();
        countTick(nowNs);

        if (!tracker.getWorldToEye(worldToEye)) {
            logWindow(nowNs);
            return;
        }
        if (!tracker.lastReadFresh()) sStale++;
        sAgeMaxNs = Math.max(sAgeMaxNs, tracker.lastReadAgeNs());

        // Olhar no mundo = -(terceira coluna de eyeToWorld) = -(terceira linha de worldToEye)
        float gx = -worldToEye[2];
        float gy = -worldToEye[6];
        float gz = -worldToEye[10];
        float yaw = (float) Math.toDegrees(Math.atan2(gx, gy));
        float pitch = (float) Math.toDegrees(Math.asin(Math.max(-1f, Math.min(1f, gz))));

        if (toggledPause || clutch) {
            if (pausedSinceNs == 0) {
                pausedSinceNs = nowNs;
                pausedYaw = yaw;
                pausedPitch = pitch;
                pausedByClutch = clutch;
                endEdge(nowNs, "pausa");
            }
            hasPrev = false;
            sPaused++;
            logWindow(nowNs);
            return;
        }
        if (pausedSinceNs != 0) {
            // A câmera retoma da pose atual, sem pulo: o giro feito durante a pausa é o que "levantar o mouse" reposicionou
            VrLog.i("HM", "retomou motivo=%s pausa=%.1fs giro_na_pausa yaw=%+.1f pitch=%+.1f graus",
                    pausedByClutch ? "embreagem" : "pausa", (nowNs - pausedSinceNs) / 1e9,
                    wrap(yaw - pausedYaw), pitch - pausedPitch);
            pausedSinceNs = 0;
        }

        yaw += testYaw;
        pitch += testPitch + testBobAmp * (float) Math.sin(2 * Math.PI * testBobHz * nowNs / 1e9);
        if (!hasPrev) {
            prevYaw = yaw;
            prevPitch = pitch;
            centerYaw = yaw;
            anchorPitch = pitch;
            wasWalking = false;
            prevNs = nowNs;
            hasPrev = true;
            VrLog.i("HM", "centro yaw=%+.1f pitch=%+.1f", yaw, pitch);
            logWindow(nowNs);
            return;
        }
        float dt = Math.min((nowNs - prevNs) / 1e9f, 0.05f);
        long dtNs = nowNs - prevNs;
        prevNs = nowNs;

        float dYaw = wrap(yaw - prevYaw);
        float dPitch = pitch - prevPitch;
        prevYaw = yaw;
        prevPitch = pitch;

        if (Math.abs(dYaw) > JUMP_DEG || Math.abs(dPitch) > JUMP_DEG) {
            sJumps++;
            spike("pulo giro yaw=%+.2f pitch=%+.2f em %.1fms idade=%.1fms", dYaw, dPitch, dtNs / 1e6,
                    tracker.lastReadAgeNs() / 1e6);
        }

        // Ao andar, o pitch só segue quando sai da faixa em volta da âncora
        WalkSource source = walkSource;
        boolean walking = walkPitchBandDeg > 0 && (testWalking || (source != null && source.isWalking()));
        if (walking) {
            float rawPitch = dPitch;
            if (!wasWalking) {
                anchorPitch = pitch - dPitch;
                walkStartNs = nowNs;
                walkAbsorbed = 0;
            }
            float band = walkPitchBandDeg;
            float before = anchorPitch;
            if (pitch > anchorPitch + band) anchorPitch = pitch - band;
            else if (pitch < anchorPitch - band) anchorPitch = pitch + band;
            dPitch = anchorPitch - before;
            float absorbed = Math.abs(rawPitch - dPitch);
            sBandAbsorbed += absorbed;
            walkAbsorbed += absorbed;
            sWalkNs += dtNs;
        }
        else if (wasWalking) {
            VrLog.i("HM", "andando fim duracao=%.1fs pitch_engolido=%.1f graus banda=%.1f",
                    (nowNs - walkStartNs) / 1e9, walkAbsorbed, walkPitchBandDeg);
        }
        wasWalking = walking;

        // Giro na borda: soma uma rotação contínua de yaw quando a cabeça passa do limite
        float edgeYaw = 0;
        float edge = edgeDeg;
        if (edge > 0) {
            float rel = wrap(yaw - centerYaw);
            float excess = Math.abs(rel) - edge;
            if (excess > 0) {
                float speed = Math.min(excess / EDGE_RAMP_DEG, 1f) * EDGE_MAX_DEG_PER_S;
                edgeYaw = Math.signum(rel) * speed * dt;
                if (!edgeActive) {
                    edgeActive = true;
                    edgeStartNs = nowNs;
                    edgeTurned = 0;
                    VrLog.i("HM", "borda entrou rel=%+.1f limite=%.0f graus", rel, edge);
                }
                edgeTurned += Math.abs(edgeYaw);
                sEdgeNs += dtNs;
            }
            else {
                endEdge(nowNs, "voltou");
            }
        }

        if (dYaw != 0 && Math.abs(dYaw) < DEADZONE_DEG) { dYaw = 0; sDeadzone++; }
        if (dPitch != 0 && Math.abs(dPitch) < DEADZONE_DEG) { dPitch = 0; sDeadzone++; }
        sYawPath += Math.abs(dYaw);
        sPitchPath += Math.abs(dPitch);

        // Guarda a fração que não cabe num inteiro para não perder movimento lento
        remX += (dYaw + edgeYaw) * countsPerDegree;
        remY += -dPitch * pitchCountsPerDegree;
        int mx = (int) remX;
        int my = (int) remY;
        remX -= mx;
        remY -= my;

        if (mx != 0 || my != 0) {
            if (Math.abs(mx) > 32767 || Math.abs(my) > 32767) sClamp++;
            long t0 = System.nanoTime();
            conn.sendMouseMove((short) Math.max(-32768, Math.min(32767, mx)),
                    (short) Math.max(-32768, Math.min(32767, my)));
            long sendNs = System.nanoTime() - t0;

            sSent++;
            sSendSumNs += sendNs;
            sSendMaxNs = Math.max(sSendMaxNs, sendNs);
            int ax = Math.abs(mx), ay = Math.abs(my);
            sSumX += ax;
            sSumY += ay;
            sMaxX = Math.max(sMaxX, ax);
            sMaxY = Math.max(sMaxY, ay);
            if (ax + ay == 1) sOnes++;
            if (mx != 0) {
                int sign = mx > 0 ? 1 : -1;
                if (lastSignX != 0 && sign != lastSignX) sFlips++;
                lastSignX = sign;
            }
            if (ax > 60 || ay > 60) {
                spike("passo grande mouse=%d,%d idade=%.1fms", mx, my, tracker.lastReadAgeNs() / 1e6);
            }
        }
        logWindow(nowNs);
    }

    private static float wrap(float deg) {
        if (deg > 180f) return deg - 360f;
        if (deg < -180f) return deg + 360f;
        return deg;
    }

    private void endEdge(long nowNs, String why) {
        if (edgeActive) {
            edgeActive = false;
            VrLog.i("HM", "borda saiu (%s) duracao=%.1fs girou=%.0f graus", why, (nowNs - edgeStartNs) / 1e9, edgeTurned);
        }
    }

    // Período entre ticks seguidos: o laço de 4 ms devia ficar perto de 4 ms
    private void countTick(long nowNs) {
        sTicks++;
        if (lastPollNs != 0) {
            long period = nowNs - lastPollNs;
            sMaxPeriodNs = Math.max(sMaxPeriodNs, period);
            if (period > LATE_TICK_NS) {
                sLate++;
                spike("tick atrasado periodo=%.1fms", period / 1e6);
            }
        }
        lastPollNs = nowNs;
    }

    // Picos isolados só no modo verbose, no máximo VERBOSE_SPIKE_LIMIT por segundo
    private void spike(String format, Object... args) {
        if (VrLog.verbose() && sSpikeLogs < VERBOSE_SPIKE_LIMIT) {
            sSpikeLogs++;
            VrLog.v("HM", format, args);
        }
    }

    private void logWindow(long nowNs) {
        if (winStartNs == 0) {
            winStartNs = nowNs;
            return;
        }
        long span = nowNs - winStartNs;
        if (span < LOG_WINDOW_NS) {
            return;
        }
        VrLog.i("HM", "ticks=%d periodo_max=%.1fms atrasados=%d sem_amostra=%d idade_max=%.1fms enviados=%d pausados=%d "
                        + "soma=%d,%d max=%d,%d uns=%d inversoes=%d pulos=%d zona=%d clamp=%d percorrido=%.1f,%.1f "
                        + "envio_med=%.3fms envio_max=%.2fms borda=%d andando=%d banda=%.2f",
                sTicks, sMaxPeriodNs / 1e6, sLate, sStale, sAgeMaxNs / 1e6, sSent, sPaused,
                sSumX, sSumY, sMaxX, sMaxY, sOnes, sFlips, sJumps, sDeadzone, sClamp, sYawPath, sPitchPath,
                sSent > 0 ? sSendSumNs / 1e6 / sSent : 0.0, sSendMaxNs / 1e6,
                sEdgeNs / 1_000_000, sWalkNs / 1_000_000, sBandAbsorbed);
        winStartNs = nowNs;
        sTicks = sLate = sStale = sPaused = sSent = sOnes = sFlips = sJumps = sDeadzone = sClamp = sSpikeLogs = 0;
        sMaxPeriodNs = sAgeMaxNs = sSendSumNs = sSendMaxNs = sEdgeNs = sWalkNs = 0;
        sSumX = sSumY = 0;
        sMaxX = sMaxY = 0;
        sYawPath = sPitchPath = sBandAbsorbed = 0;
    }
}
