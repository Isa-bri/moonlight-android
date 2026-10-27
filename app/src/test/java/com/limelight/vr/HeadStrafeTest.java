package com.limelight.vr;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HeadStrafeTest {
    private static final short A = HeadStrafe.KEY_A;
    private static final short D = HeadStrafe.KEY_D;

    @Test
    public void inclinarParaEsquerdaSegura_A_eParaDireita_D() {
        assertEquals(A, HeadStrafe.nextKey(16f, (short) 0, 15f, 5f));
        assertEquals(D, HeadStrafe.nextKey(-16f, (short) 0, 15f, 5f));
        assertEquals(0, HeadStrafe.nextKey(10f, (short) 0, 15f, 5f));
    }

    @Test
    public void histereseSegura_ate_voltar_5_graus() {
        assertEquals(A, HeadStrafe.nextKey(12f, A, 15f, 5f)); // abaixo do limiar mas acima da soltura (10°)
        assertEquals(0, HeadStrafe.nextKey(9f, A, 15f, 5f));
        assertEquals(D, HeadStrafe.nextKey(-12f, D, 15f, 5f));
        assertEquals(0, HeadStrafe.nextKey(-9f, D, 15f, 5f));
    }

    @Test
    public void passarDireitoDeUmLadoParaOOutroTroca() {
        assertEquals(D, HeadStrafe.nextKey(-20f, A, 15f, 5f));
    }
}
