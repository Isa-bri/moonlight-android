package com.limelight.vr;

// Detector de passos sem Android (testável na JVM): recebe amostras do acelerômetro e diz quando houve um passo.
//
// A gravidade é estimada por filtro passa-baixa; um pico de |a| acima dela (`threshold`), com pelo menos `minIntervalNs`
// desde o passo anterior, conta como passo. Amostras acima de 60% do limiar formam um "solavanco": se nenhum virou passo,
// é um quase passo (perdido por sensibilidade, ou perto demais do passo anterior).
//
// Confirmação de caminhada: um passo isolado (o celular batendo ao ser pego ou solto) não conta. A caminhada só é
// confirmada no segundo passo, desde que venha em até `armWindowNs` do primeiro; depois disso cada passo dentro dessa janela
// mantém a confirmação, e uma pausa maior que a janela zera tudo.
public class StepDetector {
    public static final float NEAR_MISS_FRACTION = 0.6f;

    private final float threshold;
    private final long minIntervalNs;
    private final long armWindowNs;

    private float gravity = 9.80665f;
    private long lastStepNs;
    private boolean confirmed;
    private boolean inBump, bumpStepped, bumpTooSoon;
    private float bumpPeak;

    // Resultado da última amostra
    public float excess;            // |a| acima da gravidade
    public long sinceLastStepMs;    // só vale quando a amostra foi um passo; -1 no primeiro
    public boolean bumpEnded;       // um solavanco terminou nesta amostra...
    public boolean bumpWasMiss;     // ...e não virou passo (quase passo)
    public float missPeak;          // pico do quase passo
    public boolean missTooSoon;     // quase passo por estar perto do passo anterior
    public boolean walking;         // a caminhada está confirmada (vale depois do segundo passo seguido)

    public StepDetector(float threshold, long minIntervalNs, long armWindowNs) {
        this.threshold = threshold;
        this.minIntervalNs = minIntervalNs;
        this.armWindowNs = armWindowNs;
    }

    public float gravity() {
        return gravity;
    }

    // true se esta amostra é um passo
    public boolean onSample(float x, float y, float z, long timestampNs) {
        float mag = (float) Math.sqrt(x * x + y * y + z * z);
        gravity += 0.05f * (mag - gravity);
        excess = mag - gravity;
        bumpEnded = bumpWasMiss = missTooSoon = false;

        boolean step = false;
        if (excess > threshold && timestampNs - lastStepNs > minIntervalNs) {
            step = true;
            sinceLastStepMs = lastStepNs == 0 ? -1 : (timestampNs - lastStepNs) / 1_000_000;
            // Segundo passo dentro da janela confirma; passo depois de uma pausa longa recomeça (sem confirmar)
            confirmed = lastStepNs != 0 && timestampNs - lastStepNs <= armWindowNs;
            lastStepNs = timestampNs;
        }
        walking = confirmed && lastStepNs != 0 && timestampNs - lastStepNs <= armWindowNs;
        boolean tooSoon = !step && excess > threshold;

        float floor = threshold * NEAR_MISS_FRACTION;
        if (excess > floor) {
            if (!inBump) {
                inBump = true;
                bumpPeak = 0;
                bumpStepped = false;
                bumpTooSoon = false;
            }
            bumpPeak = Math.max(bumpPeak, excess);
            bumpStepped |= step;
            bumpTooSoon |= tooSoon;
        }
        else if (inBump) {
            inBump = false;
            bumpEnded = true;
            if (!bumpStepped) {
                bumpWasMiss = true;
                missPeak = bumpPeak;
                missTooSoon = bumpTooSoon;
            }
        }
        return step;
    }
}
