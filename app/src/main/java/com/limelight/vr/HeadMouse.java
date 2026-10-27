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
// A matemática (giro na borda contínuo ou em passos, zona morta vertical ao andar, congelar o yaw olhando reto para
// cima/baixo) está em HeadMotion, sem Android. Aqui ficam o sensor, a thread, o envio e o log.
//
// Ritmo: por padrão um tick a cada 4 ms (~250 Hz, acima do sensor de ~200 Hz, então ~13% dos ticks releem a mesma
// amostra). Com `syncToSensor` não há tick: cada amostra nova do sensor dispara um poll, sem leitura repetida.
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

    private static final long LOG_WINDOW_NS = 1_000_000_000L;
    private static final long LATE_TICK_NS = 2 * PERIOD_MS * 1_000_000L;
    private static final int VERBOSE_SPIKE_LIMIT = 10; // linhas de pico por segundo no modo verbose

    private final HeadTracker tracker;
    private final NvConnection conn;
    private final HeadMotion motion;

    private HandlerThread thread;
    private Handler handler;
    private volatile boolean running;
    private volatile boolean toggledPause;
    private volatile boolean clutch;
    private volatile boolean syncToSensor;
    private volatile WalkSource walkSource;

    private final float[] worldToEye = new float[16];

    // Diagnóstico, só na thread do poll
    private long winStartNs, lastPollNs;
    private int sTicks, sLate, sStale, sPaused, sSent, sOnes, sFlips, sJumps, sDeadzone, sClamp, sGimbal, sSpikeLogs;
    private long sMaxPeriodNs, sAgeMaxNs, sSendSumNs, sSendMaxNs, sEdgeNs, sWalkNs;
    private long sSumX, sSumY;
    private int sMaxX, sMaxY, lastSignX;
    private float sYawPath, sPitchPath, sBandAbsorbed;
    private long pausedSinceNs;
    private float pausedYaw, pausedPitch;
    private boolean pausedByClutch;
    private boolean inEdge;

    // Só para testes por broadcast: soma um desvio fixo e uma oscilação de pitch à leitura do sensor e pode fingir que anda.
    // Zerar os desvios recentra (senão o centro do giro na borda fica no desvio antigo e o mouse anda sozinho).
    private volatile float testYaw, testPitch, testBobAmp, testBobHz;
    private volatile boolean testWalking;
    private volatile boolean testChanged;

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

    private final Runnable pollOnce = new Runnable() {
        @Override
        public void run() {
            if (running) {
                poll();
            }
        }
    };

    public HeadMouse(Context context, NvConnection conn, float countsPerDegree, float pitchScale) {
        this.tracker = new HeadTracker(context, "MOUSE");
        this.conn = conn;
        this.motion = new HeadMotion(countsPerDegree, pitchScale);
    }

    public void setEdgeTurn(float edgeDeg) {
        motion.edgeDeg = edgeDeg;
    }

    // Com o giro na borda ligado, vira em passos fixos em vez de contínuo
    public void setSnapTurn(boolean snap) {
        motion.snapTurn = snap;
    }

    // Um poll a cada amostra do sensor, em vez de um tick de 4 ms (chamar antes de start())
    public void setSyncToSensor(boolean sync) {
        syncToSensor = sync;
    }

    public void setTestOffsets(float yaw, float pitch, float bobAmp, float bobHz, boolean walking) {
        testYaw = yaw;
        testPitch = pitch;
        testBobAmp = bobAmp;
        testBobHz = bobHz;
        testWalking = walking;
        testChanged = yaw == 0 && pitch == 0 && bobAmp == 0 && !walking;
    }

    public void setWalkPitchBand(float bandDeg, WalkSource source) {
        motion.walkPitchBandDeg = bandDeg;
        this.walkSource = source;
    }

    public boolean start() {
        thread = new HandlerThread("DeckVR-HeadMouse", Process.THREAD_PRIORITY_URGENT_DISPLAY);
        thread.start();
        handler = new Handler(thread.getLooper());
        if (syncToSensor) {
            tracker.setSampleListener(() -> handler.post(pollOnce));
        }
        if (!tracker.start()) {
            thread.quitSafely();
            thread = null;
            return false;
        }
        running = true;
        if (!syncToSensor) {
            handler.post(tick);
        }
        return true;
    }

    public void stop() {
        running = false;
        VrLog.i("HM", "parou");
        tracker.setSampleListener(null);
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
        VrTrace.volume(toggledPause ? "pausa_liga" : "pausa_desliga");
        return toggledPause;
    }

    // Embreagem: enquanto segurada, a cabeça gira sem mexer a câmera (como levantar o mouse da mesa)
    public void setClutch(boolean held) {
        if (clutch != held) {
            VrLog.i("VOL", "embreagem %s", held ? "segurada" : "solta");
            VrTrace.volume(held ? "embreagem_liga" : "embreagem_solta");
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
        float horizLen = (float) Math.hypot(gx, gy);

        if (VrTrace.on()) {
            WalkSource traceSource = walkSource;
            VrTrace.hm(nowNs, yaw, pitch, horizLen, testWalking || (traceSource != null && traceSource.isWalking()),
                    toggledPause || clutch);
        }

        if (toggledPause || clutch) {
            if (pausedSinceNs == 0) {
                pausedSinceNs = nowNs;
                pausedYaw = yaw;
                pausedPitch = pitch;
                pausedByClutch = clutch;
                motion.endEdge(nowNs);
                logEdge(nowNs, "pausa");
            }
            motion.invalidate();
            sPaused++;
            logWindow(nowNs);
            return;
        }
        if (pausedSinceNs != 0) {
            // A câmera retoma da pose atual, sem pulo: o giro feito durante a pausa é o que "levantar o mouse" reposicionou
            VrLog.i("HM", "retomou motivo=%s pausa=%.1fs giro_na_pausa yaw=%+.1f pitch=%+.1f graus",
                    pausedByClutch ? "embreagem" : "pausa", (nowNs - pausedSinceNs) / 1e9,
                    HeadMotion.wrap(yaw - pausedYaw), pitch - pausedPitch);
            pausedSinceNs = 0;
        }
        if (testChanged) {
            testChanged = false;
            motion.invalidate();
        }

        yaw += testYaw;
        // Teste por broadcast com o celular deitado: o yaw congelaria (olhar reto para baixo); finge olhar na horizontal
        if (testYaw != 0 || testPitch != 0 || testBobAmp != 0) horizLen = 1f;
        pitch += testPitch + testBobAmp * (float) Math.sin(2 * Math.PI * testBobHz * nowNs / 1e9);

        WalkSource source = walkSource;
        boolean walking = testWalking || (source != null && source.isWalking());
        if (!motion.update(yaw, pitch, horizLen, nowNs, walking)) {
            logEdge(nowNs, "recentrou");
            if (motion.walkEvent == HeadMotion.EVENT_ENDED) {
                VrLog.i("HM", "andando fim (recentrou) duracao=%.1fs pitch_engolido=%.1f graus", motion.eventSeconds, motion.eventAbsorbed);
            }
            VrLog.i("HM", "centro yaw=%+.1f pitch=%+.1f", yaw, pitch);
            logWindow(nowNs);
            return;
        }

        if (motion.jump) {
            sJumps++;
            spike("pulo giro yaw=%+.2f pitch=%+.2f em %.1fms idade=%.1fms", motion.rawDYaw, motion.rawDPitch,
                    motion.dtNs / 1e6, tracker.lastReadAgeNs() / 1e6);
        }
        if (motion.gimbalFrozen) sGimbal++;
        if (motion.walkEvent == HeadMotion.EVENT_ENDED) {
            VrLog.i("HM", "andando fim duracao=%.1fs pitch_engolido=%.1f graus banda=%.1f",
                    motion.eventSeconds, motion.eventAbsorbed, motion.walkPitchBandDeg);
        }
        sBandAbsorbed += motion.absorbedPitch;
        if (motion.walkPitchBandDeg > 0 && walking) sWalkNs += motion.dtNs;
        if (motion.edgeEvent == HeadMotion.EVENT_STARTED) {
            inEdge = true;
            VrLog.i("HM", "borda entrou rel=%+.1f limite=%.0f graus modo=%s", motion.eventRel, motion.edgeDeg,
                    motion.snapTurn ? "passos" : "continuo");
        }
        else {
            logEdge(nowNs, "voltou");
        }
        if (inEdge) sEdgeNs += motion.dtNs;

        sDeadzone += motion.deadzoneHits;
        sYawPath += Math.abs(motion.dYaw);
        sPitchPath += Math.abs(motion.dPitch);

        int mx = motion.mx, my = motion.my;
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

    // Registra o fim do giro da borda (depois de motion.update() ou motion.endEdge())
    private void logEdge(long nowNs, String why) {
        if (motion.edgeEvent == HeadMotion.EVENT_ENDED) {
            inEdge = false;
            VrLog.i("HM", "borda saiu (%s) duracao=%.1fs girou=%.0f graus", why, motion.eventSeconds, motion.eventTurned);
            motion.edgeEvent = HeadMotion.EVENT_NONE;
        }
    }

    // Período entre polls seguidos: no modo por tempo devia ficar perto de 4 ms; no sincronizado, perto de 5 ms (200 Hz)
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
                        + "envio_med=%.3fms envio_max=%.2fms borda=%d andando=%d banda=%.2f congelado=%d sync=%b",
                sTicks, sMaxPeriodNs / 1e6, sLate, sStale, sAgeMaxNs / 1e6, sSent, sPaused,
                sSumX, sSumY, sMaxX, sMaxY, sOnes, sFlips, sJumps, sDeadzone, sClamp, sYawPath, sPitchPath,
                sSent > 0 ? sSendSumNs / 1e6 / sSent : 0.0, sSendMaxNs / 1e6,
                sEdgeNs / 1_000_000, sWalkNs / 1_000_000, sBandAbsorbed, sGimbal, syncToSensor);
        winStartNs = nowNs;
        sTicks = sLate = sStale = sPaused = sSent = sOnes = sFlips = sJumps = sDeadzone = sClamp = sGimbal = sSpikeLogs = 0;
        sMaxPeriodNs = sAgeMaxNs = sSendSumNs = sSendMaxNs = sEdgeNs = sWalkNs = 0;
        sSumX = sSumY = 0;
        sMaxX = sMaxY = 0;
        sYawPath = sPitchPath = sBandAbsorbed = 0;
    }
}
