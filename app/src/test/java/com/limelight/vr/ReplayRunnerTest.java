package com.limelight.vr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Testa o próprio executor de replay: uma regra violada tem que FALHAR, e nada conferido não pode passar.
public class ReplayRunnerTest {
    private static final long T0 = 1_000_000;

    // Rastro de AR (ar_cm=10, teclas): `parada` e depois andando para a frente
    private static List<String> rastro(boolean andaNaParada) {
        List<String> l = new ArrayList<>();
        l.add("cfg," + T0 + ",ar=true;ar_cm=10;ar_hold=false;analogico=false");
        l.add("m," + T0 + ",parada");
        double z = 0;
        for (int i = 0; i < 180; i++) { // 6 s a 30 fps
            long tc = T0 + i * 33L;
            if (andaNaParada) z -= 0.7 / 30;
            l.add("ar," + tc + "," + tc * 1_000_000 + ",0.0," + z + ",0.0,-1.0,1");
        }
        return l;
    }

    private static final List<String> REGRA_PARADA = Arrays.asList("margem 1.0", "regra parada max_teclas_fracao <= 0.02");

    @Test
    public void paradoPassaARegraDeParada() {
        ReplayRunner.Result r = ReplayRunner.run("parado", rastro(false), REGRA_PARADA);
        assertTrue(String.join("\n", r.lines), r.passed());
        assertEquals(1, r.checked);
    }

    @Test
    public void andandoNaParadaFalhaARegra() {
        ReplayRunner.Result r = ReplayRunner.run("andando", rastro(true), REGRA_PARADA);
        assertFalse("a regra violada tem que falhar: " + String.join("\n", r.lines), r.passed());
        assertEquals(1, r.failed);
    }

    @Test
    public void regraSemSegmentoCorrespondenteFalha() {
        ReplayRunner.Result r = ReplayRunner.run("x", rastro(false),
                Arrays.asList("regra nao_existe max_teclas_fracao <= 0.02"));
        assertFalse(r.passed());
        assertTrue(r.lines.get(0).contains("não conferido"));
    }

    @Test
    public void semRegrasNaoPassa() {
        ReplayRunner.Result r = ReplayRunner.run("x", rastro(false), Arrays.asList("# vazio"));
        assertFalse(r.passed());
    }

    @Test
    public void metricaDeTeclaMedeAFracaoDeTempo() {
        ReplayRunner.Result r = ReplayRunner.run("andando", rastro(true),
                Arrays.asList("margem 1.0", "regra parada tecla_W_fracao >= 0.9", "regra parada tecla_S_fracao <= 0.01"));
        assertTrue(String.join("\n", r.lines), r.passed());
    }
}
