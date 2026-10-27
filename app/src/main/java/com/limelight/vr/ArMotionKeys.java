package com.limelight.vr;

// Andar de verdade pelo ARCore, "só enquanto se move" (sem Android, testável na JVM): olha o deslocamento (frente, lado) na janela
// dos últimos `windowNs`; se a cabeça andou mais que o limiar nessa janela, a tecla fica apertada; parou, solta em ~uma janela.
// Ficar parada longe do ponto de partida não segura tecla (diferente do modo "joystick", que mede o deslocamento até a origem).
//
// `forward` positivo = frente, `side` positivo = direita (eixos do corpo, já calculados pelo ArPosition). Lados pedem
// `sideFactor` vezes o limiar (a cabeça balança para os lados sem querer). Soltura em 60% do limiar (histerese).
//
// Saltos de pose: ao recuperar o rastreamento o ARCore pode reposicionar a câmera de uma vez (~0,3 m entre dois quadros, o
// que a 30 fps seria 9 m/s). Isso não é movimento de gente: um salto maior que JUMP_M entre amostras seguidas apaga o histórico
// e solta as teclas, em vez de virar um toque de W/S/A/D (visto nos logs de 2026-10-27: `saltos=1` e S+D de ~0,4 s).
public class ArMotionKeys {
    private static final int CAPACITY = 128;
    private static final float RELEASE_FRACTION = 0.6f;
    public static final float JUMP_M = 0.25f; // deslocamento entre duas amostras seguidas que não é de gente

    public boolean jumped; // a última amostra foi um salto de pose

    private final float threshold;
    private final float sideThreshold;
    private final long windowNs;

    private final long[] times = new long[CAPACITY];
    private final float[] forwards = new float[CAPACITY];
    private final float[] sides = new float[CAPACITY];
    private int head, size;

    public boolean w, s, a, d;
    public float lastForwardDelta, lastSideDelta; // deslocamento na janela, para o log

    public ArMotionKeys(float thresholdM, float sideFactor, long windowNs) {
        this.threshold = thresholdM;
        this.sideThreshold = thresholdM * sideFactor;
        this.windowNs = windowNs;
    }

    // Origem mudou (recentrar) ou o rastreamento foi perdido: o histórico não vale mais
    public void reset() {
        size = 0;
        head = 0;
        w = s = a = d = false;
    }

    public void update(float forward, float side, long nowNs) {
        jumped = false;
        if (size > 0) {
            int last = (head + size - 1) % CAPACITY;
            if (Math.hypot(forward - forwards[last], side - sides[last]) > JUMP_M) {
                jumped = true;
                reset();
            }
        }
        // Guarda a amostra
        int idx = (head + size) % CAPACITY;
        if (size == CAPACITY) {
            head = (head + 1) % CAPACITY;
            idx = (head + size - 1) % CAPACITY;
        }
        else {
            size++;
        }
        times[idx] = nowNs;
        forwards[idx] = forward;
        sides[idx] = side;

        // Descarta o que saiu da janela, mas mantém a mais antiga dentro dela como referência
        while (size > 1 && times[(head + 1) % CAPACITY] <= nowNs - windowNs) {
            head = (head + 1) % CAPACITY;
            size--;
        }
        lastForwardDelta = forward - forwards[head];
        lastSideDelta = side - sides[head];

        w = hold(w, lastForwardDelta, threshold);
        s = hold(s, -lastForwardDelta, threshold);
        d = hold(d, lastSideDelta, sideThreshold);
        a = hold(a, -lastSideDelta, sideThreshold);
    }

    private static boolean hold(boolean held, float delta, float limit) {
        return held ? delta > limit * RELEASE_FRACTION : delta > limit;
    }
}
