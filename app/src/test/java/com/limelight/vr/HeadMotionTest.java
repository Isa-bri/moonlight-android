package com.limelight.vr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class HeadMotionTest {
    private static final long TICK_NS = 4_000_000L;

    private HeadMotion motion;
    private long now;

    @Before
    public void setUp() {
        motion = new HeadMotion(10f, 1f); // 10 contagens por grau
        now = 1_000_000_000L;
    }

    private void tick(float yaw, float pitch, boolean walking) {
        now += TICK_NS;
        motion.update(yaw, pitch, 1f, now, walking);
    }

    private void center(float yaw, float pitch) {
        assertFalse("a primeira chamada só define o centro", motion.update(yaw, pitch, 1f, now, false));
    }

    @Test
    public void giroLentoViraContagensProporcionais() {
        center(0, 0);
        int sumX = 0;
        for (int i = 1; i <= 250; i++) { // 10° em 1 s
            tick(i * 0.04f, 0, false);
            sumX += motion.mx;
        }
        assertEquals(100, sumX, 1);
    }

    @Test
    public void olharParaCimaMoveOMouseParaCima() {
        center(0, 0);
        int sumY = 0;
        for (int i = 1; i <= 100; i++) {
            tick(0, i * 0.1f, false); // 10° para cima
            sumY += motion.my;
        }
        assertEquals(-100, sumY, 1); // y positivo é para baixo
    }

    @Test
    public void voltaDe180GrausNaoViraSalto() {
        center(179.98f, 0);
        tick(-179.98f, 0, false); // 0,04° para a direita
        assertEquals(0.04f, motion.rawDYaw, 0.001f);
        assertFalse(motion.jump);
    }

    @Test
    public void ruidoAbaixoDaZonaMortaNaoMoveOMouse() {
        center(0, 0);
        float yaw = 0;
        int sumX = 0;
        for (int i = 0; i < 500; i++) {
            yaw += (i % 2 == 0 ? 0.004f : -0.004f);
            tick(yaw, 0, false);
            sumX += Math.abs(motion.mx);
        }
        assertEquals(0, sumX);
        assertTrue(motion.deadzoneHits > 0 || sumX == 0);
    }

    @Test
    public void invalidarRecentraSemPulo() {
        center(0, 0);
        tick(1, 0, false);
        motion.invalidate();
        assertFalse(motion.update(90f, 0, 1f, now + TICK_NS, false)); // cabeça girou na pausa: só redefine o centro
        assertEquals(0, motion.mx);
        tick(90.04f, 0, false);
        assertEquals(0.04f, motion.rawDYaw, 0.001f);
    }

    @Test
    public void giroNaBordaContinuoChegaNaVelocidadeMaxima() {
        motion.edgeDeg = 45;
        center(0, 0);
        tick(60, 0, false); // o giro da cabeça até 60° não conta nesta medição
        boolean entrou = motion.edgeEvent == HeadMotion.EVENT_STARTED;
        int sumX = 0;
        for (int i = 0; i < 250; i++) { // 1 s com a cabeça a 60° (15° além da borda = rampa cheia)
            tick(60, 0, false);
            entrou |= motion.edgeEvent == HeadMotion.EVENT_STARTED;
            sumX += motion.mx;
        }
        assertTrue(entrou);
        assertEquals(1200, sumX, 15); // 120°/s * 10 contagens/°
    }

    @Test
    public void giroNaBordaParaAoVoltarParaDentro() {
        motion.edgeDeg = 45;
        center(0, 0);
        for (int i = 0; i < 50; i++) tick(60, 0, false);
        boolean saiu = false;
        int sumX = 0;
        for (int i = 0; i < 50; i++) {
            tick(30, 0, false);
            saiu |= motion.edgeEvent == HeadMotion.EVENT_ENDED;
            sumX += motion.edgeYaw != 0 ? 1 : 0;
        }
        assertTrue(saiu);
        assertEquals(0, sumX);
    }

    @Test
    public void snapTurnGiraUmPassoEEsperaVoltarParaODentro() {
        motion.edgeDeg = 45;
        motion.snapTurn = true;
        center(0, 0);
        float total = 0;
        for (int i = 0; i < 500; i++) { // 2 s olhando além da borda
            tick(60, 0, false);
            total += motion.edgeYaw;
        }
        assertEquals(HeadMotion.SNAP_STEP_DEG, total, 0.5f); // um passo só, mesmo segurando

        for (int i = 0; i < 20; i++) tick(30, 0, false); // volta para perto do centro (re-arma)
        float second = 0;
        for (int i = 0; i < 500; i++) {
            tick(60, 0, false);
            second += motion.edgeYaw;
        }
        assertEquals(HeadMotion.SNAP_STEP_DEG, second, 0.5f);
    }

    @Test
    public void snapTurnParaOLadoNegativo() {
        motion.edgeDeg = 45;
        motion.snapTurn = true;
        center(0, 0);
        float total = 0;
        for (int i = 0; i < 500; i++) {
            tick(-60, 0, false);
            total += motion.edgeYaw;
        }
        assertEquals(-HeadMotion.SNAP_STEP_DEG, total, 0.5f);
    }

    @Test
    public void zonaMortaVerticalEngoleOBalancoMasNaoOOlharDeVerdade() {
        motion.walkPitchBandDeg = 1.5f;
        center(0, 0);
        int sumY = 0;
        for (int i = 0; i < 500; i++) { // balanço de ±1° a 2 Hz andando
            tick(0, (float) Math.sin(2 * Math.PI * 2 * (i * 0.004)), true);
            sumY += Math.abs(motion.my);
        }
        assertEquals(0, sumY);

        int look = 0;
        for (int i = 1; i <= 100; i++) { // olhar 10° para cima de propósito
            tick(0, i * 0.1f, true);
            look += motion.my;
        }
        // atravessa tudo menos a faixa (1,5°) que a zona morta engole
        assertEquals(-85, look, 10);
    }

    @Test
    public void semAndarOPitchPassaInteiro() {
        motion.walkPitchBandDeg = 1.5f;
        center(0, 0);
        int sumY = 0;
        for (int i = 1; i <= 100; i++) {
            tick(0, i * 0.01f, false); // 1° para cima, sem andar
            sumY += motion.my;
        }
        assertEquals(-10, sumY, 1);
    }

    @Test
    public void olhandoRetoParaBaixoOYawFicaCongelado() {
        center(0, -89.5f);
        now += TICK_NS;
        motion.update(50f, -89.5f, 0.01f, now, false); // yaw instável perto do polo
        assertTrue(motion.gimbalFrozen);
        assertEquals(0, motion.mx);
        assertEquals(0f, motion.rawDYaw, 0.0001f);
        now += TICK_NS;
        motion.update(50.04f, -85f, 0.5f, now, false); // saiu do polo: volta a valer
        assertFalse(motion.gimbalFrozen);
        assertEquals(0.04f, motion.rawDYaw, 0.001f);
    }

    @Test
    public void saltoDeSensorEMarcado() {
        center(0, 0);
        tick(5f, 0, false);
        assertTrue(motion.jump);
    }

    @Test
    public void recentrarComABordaAtivaAvisaOFimDela() {
        motion.edgeDeg = 45;
        center(0, 0);
        for (int i = 0; i < 50; i++) tick(60, 0, false);
        motion.invalidate();
        now += TICK_NS;
        assertFalse(motion.update(60f, 0, 1f, now, false));
        assertEquals(HeadMotion.EVENT_ENDED, motion.edgeEvent);
        assertTrue(motion.eventTurned > 0);
        tick(60.0f, 0, false); // com o novo centro em 60°, a borda não está mais ativa
        assertEquals(0f, motion.edgeYaw, 0f);
    }
}
