package com.limelight.vr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class StepDetectorTest {
    private static final long MS = 1_000_000L;
    private static final float G = 9.80665f;

    private StepDetector detector;
    private long t;

    @Before
    public void setUp() {
        detector = new StepDetector(2.0f, 280 * MS, 1200 * MS);
        t = 10_000 * MS;
        for (int i = 0; i < 200; i++) sample(G); // a gravidade assenta
    }

    private boolean sample(float mag) {
        t += 10 * MS; // ~100 Hz
        return detector.onSample(0, 0, mag, t);
    }

    @Test
    public void picoAcimaDoLimiarEPasso() {
        assertTrue(sample(G + 3f));
        assertEquals(3f, detector.excess, 0.3f);
    }

    @Test
    public void dentroDoIntervaloMinimoNaoContaEViraQuasePassoPertoDoAnterior() {
        assertTrue(sample(G + 3f));
        for (int i = 0; i < 5; i++) sample(G);
        assertFalse(sample(G + 3f)); // 60 ms depois
        boolean viu = false;
        for (int i = 0; i < 10 && !viu; i++) {
            sample(G);
            viu = detector.bumpEnded && detector.bumpWasMiss && detector.missTooSoon;
        }
        assertTrue(viu);
    }

    @Test
    public void picoFracoViraQuasePassoAbaixoDoLimiar() {
        assertFalse(sample(G + 1.6f)); // 80% do limiar de 2,0
        boolean visto = false;
        for (int i = 0; i < 10 && !visto; i++) {
            sample(G);
            if (detector.bumpEnded) {
                visto = true;
                assertTrue(detector.bumpWasMiss);
                assertFalse(detector.missTooSoon);
                assertEquals(1.6f, detector.missPeak, 0.2f);
            }
        }
        assertTrue(visto);
    }

    @Test
    public void ruidoPequenoNaoFazNada() {
        for (int i = 0; i < 100; i++) {
            assertFalse(sample(G + (i % 2 == 0 ? 0.3f : -0.3f)));
            assertFalse(detector.bumpEnded);
        }
    }

    @Test
    public void passosEspacadosContamTodos() {
        int steps = 0;
        for (int i = 0; i < 5; i++) {
            if (sample(G + 3f)) steps++;
            for (int j = 0; j < 40; j++) sample(G); // 400 ms entre passos
        }
        assertEquals(5, steps);
    }

    @Test
    public void passoIsoladoNaoConfirmaACaminhada() {
        assertTrue(sample(G + 3f));
        assertFalse(detector.walking);
        for (int i = 0; i < 150; i++) { // 1,5 s sem outro passo
            sample(G);
            assertFalse(detector.walking);
        }
    }

    @Test
    public void segundoPassoSeguidoConfirmaEOsSeguintesMantem() {
        assertTrue(sample(G + 3f));
        assertFalse(detector.walking);
        for (int j = 0; j < 40; j++) sample(G);
        assertTrue(sample(G + 3f)); // 400 ms depois
        assertTrue(detector.walking);
        for (int j = 0; j < 40; j++) sample(G);
        assertTrue(sample(G + 3f));
        assertTrue(detector.walking);
    }

    @Test
    public void pausaLongaDesfazAConfirmacao() {
        sample(G + 3f);
        for (int j = 0; j < 40; j++) sample(G);
        sample(G + 3f);
        assertTrue(detector.walking);
        for (int j = 0; j < 130; j++) sample(G); // 1,3 s parado
        assertFalse(detector.walking);
        assertTrue(sample(G + 3f)); // um passo depois da pausa recomeça: isolado de novo
        assertFalse(detector.walking);
    }
}
