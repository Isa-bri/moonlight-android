package com.limelight.vr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class ArMotionKeysTest {
    private static final long FRAME_NS = 33_333_333L; // 30 fps

    private ArMotionKeys keys;
    private long t;

    @Before
    public void setUp() {
        keys = new ArMotionKeys(0.10f, 2f, 500_000_000L);
        t = 1_000_000_000L;
    }

    private void frame(float forward, float side) {
        t += FRAME_NS;
        keys.update(forward, side, t);
    }

    @Test
    public void paradoNaoApertaNada() {
        for (int i = 0; i < 90; i++) frame(0.01f * (i % 2), 0.01f * (i % 3)); // balanço de ~1-2 cm
        assertFalse(keys.w || keys.s || keys.a || keys.d);
    }

    @Test
    public void andarParaAFrenteApertaWePararSolta() {
        float f = 0;
        for (int i = 0; i < 30; i++) { // 1 s a 0,6 m/s
            f += 0.02f;
            frame(f, 0);
        }
        assertTrue(keys.w);
        assertFalse(keys.s);
        for (int i = 0; i < 30; i++) frame(f, 0); // parou 1 s, longe da origem
        assertFalse("parou: W deve soltar mesmo deslocada da origem", keys.w);
    }

    @Test
    public void andarParaTrasApertaS() {
        float f = 0;
        for (int i = 0; i < 30; i++) {
            f -= 0.02f;
            frame(f, 0);
        }
        assertTrue(keys.s);
        assertFalse(keys.w);
    }

    @Test
    public void passoParaOLadoPedeOLimiarDobrado() {
        float x = 0;
        for (int i = 0; i < 30; i++) { // 0,6 m/s para a direita: 0,3 m em 0,5 s, acima de 20 cm
            x += 0.02f;
            frame(0, x);
        }
        assertTrue(keys.d);
        assertFalse(keys.a);
        keys.reset();
        x = 0;
        for (int i = 0; i < 30; i++) { // deslize curto de 3 cm/quadro... 0,05 m/s: não conta
            x -= 0.0015f;
            frame(0, x);
        }
        assertFalse(keys.a);
    }

    @Test
    public void balancoLateralPequenoNaoViraLado() {
        for (int i = 0; i < 60; i++) frame(0, (i % 10 < 5 ? 1 : -1) * 0.06f); // ±6 cm, abaixo de 20 cm
        assertFalse(keys.a || keys.d);
    }

    @Test
    public void resetLimpaTeclasEHistorico() {
        float f = 0;
        for (int i = 0; i < 30; i++) {
            f += 0.02f;
            frame(f, 0);
        }
        assertTrue(keys.w);
        keys.reset();
        assertFalse(keys.w);
        frame(5f, 5f); // origem mudou: a primeira amostra nova não pode virar "salto" de movimento
        assertEquals(0f, keys.lastForwardDelta, 0f);
        assertFalse(keys.w || keys.d);
    }

    @Test
    public void saltoDePoseNaoViraToqueDeTecla() {
        // Reproduz o que os logs de 2026-10-27 mostraram: parada, a pose salta ~0,3 m (recuperação do rastreamento)
        // e depois volta de uma vez; nada disso é movimento de gente
        for (int i = 0; i < 30; i++) frame(0.03f, 0f);
        frame(0.31f, -0.21f);
        assertTrue(keys.jumped);
        assertFalse(keys.w || keys.s || keys.a || keys.d);
        for (int i = 0; i < 30; i++) {
            frame(0.31f, -0.21f);
            assertFalse(keys.w || keys.a);
        }
        frame(0.03f, 0f); // volta de uma vez
        assertTrue(keys.jumped);
        assertFalse(keys.w || keys.s || keys.a || keys.d);
        for (int i = 0; i < 30; i++) frame(0.03f, 0f);
        assertFalse(keys.w || keys.s || keys.a || keys.d);
    }

    @Test
    public void passoRapidoNormalNaoEConfundidoComSalto() {
        float f = 0;
        for (int i = 0; i < 30; i++) { // 1,5 m/s: 5 cm por quadro, bem abaixo dos 25 cm de salto
            f += 0.05f;
            frame(f, 0);
            assertFalse(keys.jumped);
        }
        assertTrue(keys.w);
    }

    @Test
    public void passoParaOLadoNaoAcionaWouS() {
        // Passo lateral limpo como nos logs (frente varia ±2 cm): só D
        float x = 0;
        for (int i = 0; i < 30; i++) {
            x += 0.02f;
            frame(0.02f * (i % 2 == 0 ? 1 : -1), x);
        }
        assertTrue(keys.d);
        assertFalse(keys.w || keys.s);
    }
}
