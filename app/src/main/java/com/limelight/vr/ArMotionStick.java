package com.limelight.vr;

// Andar de verdade pelo ARCore como analógico (esquerdo) de um controle, para jogos que só aceitam analógico (sem WASD):
// a direção e a intensidade vêm da velocidade nos últimos `windowNs` (sem Android, testável na JVM).
//
// - Zona morta: movimento na janela abaixo de `deadzoneM` não mexe o analógico (balanço do corpo, tremor do rastreamento).
// - Acima dela, a intensidade sobe linear até 1 em `fullM` metros na janela (padrão: 4x a zona morta, ~0,8 m/s).
// - Multidirecional: o vetor (lado, frente) mantém o ângulo; um passo na diagonal vira uma diagonal no analógico.
// - Suavização leve (sobe e desce em ~3 quadros) para o analógico não tremer; parar solta rápido.
// - Salto de pose (ver ArMotionKeys.JUMP_M) apaga o histórico e zera o analógico.
//
// `forward` positivo = frente, `side` positivo = direita. Saída: x (direita +) e y (frente +), cada um em [-1, 1].
public class ArMotionStick {
    private static final int CAPACITY = 128;
    private static final float SMOOTH_UP = 0.5f;   // fração do caminho percorrida por quadro ao subir
    private static final float SMOOTH_DOWN = 0.7f; // ao descer (parar tem que ser rápido)

    private final float deadzone;
    private final float full;
    private final long windowNs;

    private final long[] times = new long[CAPACITY];
    private final float[] forwards = new float[CAPACITY];
    private final float[] sides = new float[CAPACITY];
    private int head, size;

    public float x, y;          // analógico (já suavizado)
    public float magnitude;     // |(x, y)| de 0 a 1
    public boolean jumped;
    public float lastForwardDelta, lastSideDelta;

    public ArMotionStick(float deadzoneM, float fullM, long windowNs) {
        this.deadzone = deadzoneM;
        this.full = Math.max(fullM, deadzoneM + 0.01f);
        this.windowNs = windowNs;
    }

    public void reset() {
        size = 0;
        head = 0;
        x = y = magnitude = 0;
    }

    public void update(float forward, float side, long nowNs) {
        jumped = false;
        if (size > 0) {
            int last = (head + size - 1) % CAPACITY;
            if (Math.hypot(forward - forwards[last], side - sides[last]) > ArMotionKeys.JUMP_M) {
                jumped = true;
                reset();
            }
        }
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
        while (size > 1 && times[(head + 1) % CAPACITY] <= nowNs - windowNs) {
            head = (head + 1) % CAPACITY;
            size--;
        }
        lastForwardDelta = forward - forwards[head];
        lastSideDelta = side - sides[head];

        float dist = (float) Math.hypot(lastSideDelta, lastForwardDelta);
        float target = dist <= deadzone ? 0f : Math.min(1f, (dist - deadzone) / (full - deadzone));
        float targetX = dist > 0 ? lastSideDelta / dist * target : 0f;
        float targetY = dist > 0 ? lastForwardDelta / dist * target : 0f;

        x = smooth(x, targetX);
        y = smooth(y, targetY);
        magnitude = (float) Math.min(1.0, Math.hypot(x, y));
    }

    private static float smooth(float current, float target) {
        // Descer = o módulo diminui (ou troca de sinal): segue mais rápido que ao subir
        float k = Math.abs(target) < Math.abs(current) ? SMOOTH_DOWN : SMOOTH_UP;
        return current + (target - current) * k;
    }
}
