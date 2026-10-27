package com.limelight.vr;

// Andar no lugar sem Android (testável na JVM e no replay): amostras do acelerômetro entram com o timestamp do sensor e a tecla
// W (ou o analógico para a frente) sobe e desce só por esses timestamps: nada de Handler/postDelayed, para o replay reproduzir
// exatamente o que roda no celular. A soltura é conferida a cada amostra (~100 Hz, então a precisão é de ~10 ms).
public class StepPipeline {
    private final StepDetector detector;
    private final long releaseNs;
    private final MoveOutput out;
    private final boolean analog;

    private boolean down;
    private long lastStepNs;
    public int walkSteps;
    public long walkStartNs, lastReleaseNs;
    public boolean pressedNow, releasedNow; // eventos da última amostra, para o log do chamador

    public StepPipeline(float threshold, long minIntervalNs, long armWindowNs, long releaseNs, boolean analog, MoveOutput out) {
        this.detector = new StepDetector(threshold, minIntervalNs, armWindowNs);
        this.releaseNs = releaseNs;
        this.analog = analog;
        this.out = out;
    }

    public StepDetector detector() {
        return detector;
    }

    public boolean isWalking() {
        return down;
    }

    // true se esta amostra foi um passo
    public boolean onSample(float x, float y, float z, long tsNs) {
        pressedNow = releasedNow = false;
        boolean step = detector.onSample(x, y, z, tsNs);
        if (step) {
            lastStepNs = tsNs;
            if (detector.walking) {
                if (!down) {
                    down = true;
                    walkSteps = 0;
                    walkStartNs = tsNs;
                    pressedNow = true;
                    press();
                }
                walkSteps++;
            }
        }
        if (down && tsNs - lastStepNs > releaseNs) {
            release(tsNs);
        }
        return step;
    }

    // Encerramento: solta se estiver apertada
    public void stop(long tsNs) {
        if (down) {
            release(tsNs);
        }
    }

    private void press() {
        if (analog) {
            out.stick(0f, 1f);
        }
        else {
            out.key('W', true);
        }
    }

    private void release(long tsNs) {
        down = false;
        releasedNow = true;
        lastReleaseNs = tsNs;
        if (analog) {
            out.stick(0f, 0f);
        }
        else {
            out.key('W', false);
        }
    }
}
