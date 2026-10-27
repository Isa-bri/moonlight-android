package com.limelight.vr;

// Andar de verdade pelo ARCore, sem Android (testável na JVM e no replay): recebe a pose da câmera quadro a quadro e decide as
// teclas W/A/S/D ou o analógico. Tudo que antes ficava dentro do ArPosition (projeção nos eixos do corpo, recentrar, salto de pose,
// histerese do modo joystick, modos "só enquanto se move" e analógico) fica aqui, para o replay de um rastro gravado exercitar o
// mesmo código que roda no celular.
//
// Entrada por quadro: x, z da posição da câmera no mundo do ARCore (Y para cima), (hx, hz) = componentes horizontais do olhar
// (-zAxis da pose; só a direção importa), e se o rastreamento está ativo. `requestRecenter()` marca a próxima pose como origem.
public class ArPipeline {
    public static final float JUMP_M = 0.25f;     // salto de pose entre dois quadros que não é movimento de gente
    public static final float SIDE_FACTOR = 2f;   // lados pedem mais deslocamento: a cabeça balança sem querer
    public static final long WINDOW_NS = 500_000_000L;

    private final float thresholdM, releaseM, sideThresholdM, sideReleaseM;
    private final boolean holdMode;   // true = joystick (segura enquanto longe da origem)
    private final boolean analog;     // true = analógico em vez de teclas
    private final MoveOutput out;
    private final ArMotionKeys motion;
    private final ArMotionStick stick;

    private volatile boolean recenterRequested = true;
    private float originX, originZ;
    private float fwdX, fwdZ = -1;     // olhar atual
    private float bodyX, bodyZ = -1;   // frente do corpo, fixada na origem
    private float prevX, prevZ;
    private boolean hasPrevPose;
    private float lastStickX, lastStickY;

    public boolean fwdHeld, backHeld, leftHeld, rightHeld;
    public volatile boolean stickWalking;

    // Resultado do último onFrame(), para o log do chamador
    public float curForward, curSide;      // deslocamento nos eixos do corpo (já com o deslocamento de teste)
    public boolean poseJumped, recentered;
    public float recenterBeforeForward, recenterBeforeSide;
    public float stickX, stickY;

    // Só para testes: soma ao deslocamento medido; zerado ao recentrar
    public float testForward, testSide;

    public ArPipeline(float thresholdM, boolean holdMode, boolean analog, MoveOutput out) {
        this.thresholdM = thresholdM;
        this.releaseM = thresholdM * 0.6f;
        this.sideThresholdM = thresholdM * SIDE_FACTOR;
        this.sideReleaseM = sideThresholdM * 0.6f;
        this.holdMode = holdMode;
        this.analog = analog;
        this.out = out;
        this.motion = new ArMotionKeys(thresholdM, SIDE_FACTOR, WINDOW_NS);
        this.stick = new ArMotionStick(thresholdM, thresholdM * 4f, WINDOW_NS);
    }

    public void requestRecenter() {
        recenterRequested = true;
    }

    public boolean isWalking() {
        return fwdHeld || backHeld || leftHeld || rightHeld || stickWalking;
    }

    // false se o rastreamento não está ativo (nada é decidido; tudo é solto)
    public boolean onFrame(long tNs, float x, float z, float hx, float hz, boolean tracking) {
        poseJumped = false;
        recentered = false;
        if (!tracking) {
            hasPrevPose = false;
            motion.reset();
            releaseAll();
            return false;
        }

        if (hasPrevPose && Math.hypot(x - prevX, z - prevZ) > JUMP_M) {
            poseJumped = true;
            // A origem acompanha o salto, então o deslocamento fica contínuo (vale para o modo joystick)
            originX += x - prevX;
            originZ += z - prevZ;
        }
        prevX = x;
        prevZ = z;
        hasPrevPose = true;

        float hLen = (float) Math.hypot(hx, hz);
        if (hLen > 0.3f) { // olhando quase reto para cima/baixo: mantém a última frente
            fwdX = hx / hLen;
            fwdZ = hz / hLen;
        }

        if (recenterRequested) {
            recenterRequested = false;
            recentered = true;
            recenterBeforeForward = curForward;
            recenterBeforeSide = curSide;
            originX = x;
            originZ = z;
            bodyX = fwdX;
            bodyZ = fwdZ;
            motion.reset();
            testForward = testSide = 0;
            releaseAll();
        }

        float dx = x - originX, dz = z - originZ;
        // direita = (-bodyZ, bodyX) no sistema do ARCore (Y para cima, olhando para -Z)
        float forward = dx * bodyX + dz * bodyZ + testForward;
        float side = dx * -bodyZ + dz * bodyX + testSide;
        curForward = forward;
        curSide = side;

        if (analog) {
            if (poseJumped) {
                stick.reset();
            }
            else {
                stick.update(forward, side, tNs);
            }
            sendStick(stick.x, stick.y);
            return true;
        }

        boolean wantFwd, wantBack, wantRight, wantLeft;
        if (poseJumped) {
            motion.reset();
            wantFwd = wantBack = wantRight = wantLeft = false;
        }
        else if (holdMode) {
            wantFwd = fwdHeld ? forward > releaseM : forward > thresholdM;
            wantBack = backHeld ? -forward > releaseM : -forward > thresholdM;
            wantRight = rightHeld ? side > sideReleaseM : side > sideThresholdM;
            wantLeft = leftHeld ? -side > sideReleaseM : -side > sideThresholdM;
        }
        else {
            motion.update(forward, side, tNs);
            wantFwd = motion.w;
            wantBack = motion.s;
            wantRight = motion.d;
            wantLeft = motion.a;
        }
        if (wantFwd != fwdHeld) { fwdHeld = wantFwd; out.key('W', wantFwd); }
        if (wantBack != backHeld) { backHeld = wantBack; out.key('S', wantBack); }
        if (wantRight != rightHeld) { rightHeld = wantRight; out.key('D', wantRight); }
        if (wantLeft != leftHeld) { leftHeld = wantLeft; out.key('A', wantLeft); }
        return true;
    }

    // Solta tudo (rastreamento perdido, recentrada, encerramento)
    public void releaseAll() {
        if (analog) {
            stick.reset();
            sendStick(0f, 0f);
            return;
        }
        if (fwdHeld) { fwdHeld = false; out.key('W', false); }
        if (backHeld) { backHeld = false; out.key('S', false); }
        if (rightHeld) { rightHeld = false; out.key('D', false); }
        if (leftHeld) { leftHeld = false; out.key('A', false); }
    }

    // Manda o analógico quando muda o bastante (1/256) e sempre que volta a zero
    private void sendStick(float x, float y) {
        boolean zero = x == 0 && y == 0;
        if (zero ? (lastStickX == 0 && lastStickY == 0)
                : Math.abs(x - lastStickX) < 1 / 256f && Math.abs(y - lastStickY) < 1 / 256f) {
            return;
        }
        lastStickX = x;
        lastStickY = y;
        stickX = x;
        stickY = y;
        stickWalking = Math.hypot(x, y) > 0.1;
        out.stick(x, y);
    }
}
