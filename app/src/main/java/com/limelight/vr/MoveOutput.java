package com.limelight.vr;

// Saída do movimento do corpo (andar de verdade, andar no lugar): teclas W/A/S/D ou o analógico esquerdo.
// Sem Android: em produção liga em conn.sendKeyboardInput / ControllerHandler; no replay, só registra.
public interface MoveOutput {
    // key: 'W', 'A', 'S' ou 'D'
    void key(char key, boolean down);

    // x para a direita, y para a frente, em [-1, 1]
    void stick(float x, float y);
}
