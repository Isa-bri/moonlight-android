package com.limelight.vr;

// Output of body movement (real walking, walking in place): W/A/S/D keys or the left analog stick.
// No Android: in production it is wired to conn.sendKeyboardInput / ControllerHandler; in replay it only records.
public interface MoveOutput {
    // key: 'W', 'A', 'S' or 'D'
    void key(char key, boolean down);

    // x to the right, y forward, in [-1, 1]
    void stick(float x, float y);
}
