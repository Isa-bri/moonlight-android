package com.limelight.vr;

// Lógica da cabeça = câmera, sem Android (testável na JVM): recebe o olhar (yaw/pitch em graus) a cada tick e devolve quantas
// contagens de mouse mandar. O HeadMouse cuida do sensor, da thread, do envio e do log.
//
// - Passo de giro por tick, com a volta de 180° tratada e uma zona morta de 0,005°.
// - Zona morta vertical ao andar: o pitch só passa depois de sair de uma faixa em volta da última posição.
// - Giro na borda: passando de `edgeDeg` graus do centro, a câmera continua girando (contínuo, mais rápido quanto mais longe)
//   ou, com `snapTurn`, dá um giro fixo de SNAP_STEP_DEG e só gira de novo depois que a cabeça volta para perto do centro.
// - Olhando quase reto para cima/baixo o yaw é instável (singularidade): abaixo de GIMBAL_HORIZ ele não gira o mouse.
public class HeadMotion {
    public static final float DEADZONE_DEG = 0.005f;
    public static final float EDGE_RAMP_DEG = 15f;        // da borda até a velocidade máxima
    public static final float EDGE_MAX_DEG_PER_S = 120f;  // velocidade máxima do giro contínuo
    public static final float SNAP_STEP_DEG = 30f;        // giro de cada passo do snap turn
    public static final float SNAP_DEG_PER_S = 360f;      // velocidade do giro do passo (~80 ms), rápido para não enjoar
    public static final float SNAP_REARM_DEG = 10f;       // quanto voltar para dentro da borda antes de girar outra vez
    public static final float GIMBAL_HORIZ = 0.0872f;     // cos(85°): componente horizontal do olhar abaixo da qual o yaw congela
    public static final float JUMP_DEG = 3f;              // giro por tick que não é de cabeça humana

    public static final int EVENT_NONE = 0;
    public static final int EVENT_STARTED = 1;
    public static final int EVENT_ENDED = 2;

    private final float countsPerDegree;
    private final float pitchCountsPerDegree;

    // Parâmetros (podem mudar com o app rodando)
    public volatile float edgeDeg;          // 0 = sem giro na borda
    public volatile boolean snapTurn;
    public volatile float walkPitchBandDeg; // 0 = sem zona morta vertical

    // Estado
    private boolean hasPrev;
    private float prevYaw, prevPitch, centerYaw, anchorPitch;
    private long prevNs;
    private float remX, remY;
    private boolean wasWalking;
    private long walkStartNs;
    private float walkAbsorbed;
    private boolean edgeActive, snapArmed = true;
    private long edgeStartNs;
    private float edgeTurned, snapRemaining;

    // Resultado do último update()
    public int mx, my;
    public float rawDYaw, rawDPitch;   // giro do tick antes das zonas mortas
    public float dYaw, dPitch;         // giro que de fato virou mouse (sem a parte da borda)
    public float edgeYaw;              // giro da borda somado neste tick
    public float absorbedPitch;        // pitch engolido pela zona morta vertical neste tick
    public long dtNs;
    public int deadzoneHits;
    public boolean jump, gimbalFrozen;
    public int walkEvent, edgeEvent;
    public float eventRel;             // giro relativo ao centro quando a borda começou
    public float eventTurned;          // graus girados pela borda (no EVENT_ENDED da borda)
    public float eventSeconds;         // duração (no EVENT_ENDED da borda e do andar)
    public float eventAbsorbed;        // pitch engolido no andar inteiro (no EVENT_ENDED do andar)

    public HeadMotion(float countsPerDegree, float pitchScale) {
        this.countsPerDegree = countsPerDegree;
        this.pitchCountsPerDegree = countsPerDegree * pitchScale;
    }

    // O próximo update() só define o centro (sem movimento), como depois de pausa/embreagem
    public void invalidate() {
        hasPrev = false;
    }

    public boolean hasCenter() {
        return hasPrev;
    }

    // Termina o giro da borda em andamento (pausa); preenche edgeEvent para o chamador registrar
    public void endEdge(long nowNs) {
        edgeEvent = EVENT_NONE;
        if (edgeActive) {
            edgeActive = false;
            edgeEvent = EVENT_ENDED;
            eventSeconds = (nowNs - edgeStartNs) / 1e9f;
            eventTurned = edgeTurned;
        }
    }

    public float centerYaw() {
        return centerYaw;
    }

    // yaw/pitch em graus; horizLen = componente horizontal do vetor do olhar (1 olhando na horizontal, 0 reto para cima/baixo).
    // false na chamada que só definiu o centro (não houve movimento).
    public boolean update(float yaw, float pitch, float horizLen, long nowNs, boolean walking) {
        mx = my = 0;
        rawDYaw = rawDPitch = dYaw = dPitch = edgeYaw = absorbedPitch = 0;
        deadzoneHits = 0;
        jump = gimbalFrozen = false;
        walkEvent = edgeEvent = EVENT_NONE;

        if (!hasPrev) {
            // Recentrar termina o que estava em andamento (e avisa o chamador, para o log não ficar com a borda "ativa")
            endEdge(nowNs);
            if (wasWalking) {
                walkEvent = EVENT_ENDED;
                eventSeconds = (nowNs - walkStartNs) / 1e9f;
                eventAbsorbed = walkAbsorbed;
            }
            prevYaw = yaw;
            prevPitch = pitch;
            centerYaw = yaw;
            anchorPitch = pitch;
            wasWalking = false;
            edgeActive = false;
            snapArmed = true;
            snapRemaining = 0;
            prevNs = nowNs;
            dtNs = 0;
            hasPrev = true;
            return false;
        }
        if (horizLen < GIMBAL_HORIZ) {
            // O yaw é ruído aqui: não vira mouse, mas vira a nova referência (e leva o centro junto), para a câmera não
            // dar um salto quando o olhar sair do polo
            centerYaw += wrap(yaw - prevYaw);
            prevYaw = yaw;
            gimbalFrozen = true;
        }

        dtNs = nowNs - prevNs;
        float dt = Math.min(dtNs / 1e9f, 0.05f);
        prevNs = nowNs;

        float dY = wrap(yaw - prevYaw);
        float dP = pitch - prevPitch;
        prevYaw = yaw;
        prevPitch = pitch;
        rawDYaw = dY;
        rawDPitch = dP;
        jump = Math.abs(dY) > JUMP_DEG || Math.abs(dP) > JUMP_DEG;

        // Ao andar, o pitch só segue quando sai da faixa em volta da âncora
        boolean walkingNow = walkPitchBandDeg > 0 && walking;
        if (walkingNow) {
            if (!wasWalking) {
                anchorPitch = pitch - dP;
                walkStartNs = nowNs;
                walkAbsorbed = 0;
                walkEvent = EVENT_STARTED;
            }
            float band = walkPitchBandDeg;
            float before = anchorPitch;
            if (pitch > anchorPitch + band) anchorPitch = pitch - band;
            else if (pitch < anchorPitch - band) anchorPitch = pitch + band;
            float passed = anchorPitch - before;
            absorbedPitch = Math.abs(dP - passed);
            walkAbsorbed += absorbedPitch;
            dP = passed;
        }
        else if (wasWalking) {
            walkEvent = EVENT_ENDED;
            eventSeconds = (nowNs - walkStartNs) / 1e9f;
            eventAbsorbed = walkAbsorbed;
        }
        wasWalking = walkingNow;

        // Giro na borda
        float edge = edgeDeg;
        if (edge > 0) {
            float rel = wrap(yaw - centerYaw);
            float excess = Math.abs(rel) - edge;
            if (excess > 0) {
                if (snapTurn) {
                    if (snapArmed) {
                        snapArmed = false;
                        snapRemaining = Math.signum(rel) * SNAP_STEP_DEG;
                    }
                    float turn = Math.signum(snapRemaining) * Math.min(Math.abs(snapRemaining), SNAP_DEG_PER_S * dt);
                    snapRemaining -= turn;
                    edgeYaw = turn;
                }
                else {
                    float speed = Math.min(excess / EDGE_RAMP_DEG, 1f) * EDGE_MAX_DEG_PER_S;
                    edgeYaw = Math.signum(rel) * speed * dt;
                }
                if (!edgeActive) {
                    edgeActive = true;
                    edgeStartNs = nowNs;
                    edgeTurned = 0;
                    edgeEvent = EVENT_STARTED;
                    eventRel = rel;
                }
                edgeTurned += Math.abs(edgeYaw);
            }
            else {
                endEdge(nowNs);
            }
            // O snap turn só gira de novo depois que a cabeça volta para dentro da borda (com folga)
            if (!snapArmed && Math.abs(rel) < edge - SNAP_REARM_DEG) {
                snapArmed = true;
                snapRemaining = 0;
            }
        }

        if (dY != 0 && Math.abs(dY) < DEADZONE_DEG) { dY = 0; deadzoneHits++; }
        if (dP != 0 && Math.abs(dP) < DEADZONE_DEG) { dP = 0; deadzoneHits++; }
        dYaw = dY;
        dPitch = dP;

        // Guarda a fração que não cabe num inteiro para não perder movimento lento
        remX += (dY + edgeYaw) * countsPerDegree;
        remY += -dP * pitchCountsPerDegree;
        mx = (int) remX;
        my = (int) remY;
        remX -= mx;
        remY -= my;
        return true;
    }

    public static float wrap(float deg) {
        if (deg > 180f) return deg - 360f;
        if (deg < -180f) return deg + 360f;
        return deg;
    }
}
