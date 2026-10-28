package com.limelight.vr;

// Movement destination when the game uses an analog stick: the left stick of a virtual controller (x to the right, y
// forward, each in [-1, 1]). When there is no destination, movement goes out as W/A/S/D keys.
public interface MoveStick {
    void setStick(float x, float y);
}
