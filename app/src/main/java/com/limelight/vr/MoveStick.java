package com.limelight.vr;

// Destino do movimento quando o jogo usa analógico: o analógico esquerdo de um controle virtual (x para a direita, y para a
// frente, cada um em [-1, 1]). Quando não há destino, o movimento sai como teclas W/A/S/D.
public interface MoveStick {
    void setStick(float x, float y);
}
