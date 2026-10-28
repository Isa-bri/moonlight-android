package com.limelight.vr;

import android.util.Log;

import java.util.Locale;

// Diagnostic log of the VR controls (to keep the head, the steps and the keys smooth and jerk-free).
//
// Everything goes out with the "DeckVR" tag and a component prefix (HM, SENSOR, STEP, STRAFE, AR, VOL, GL, NET, CONFIG), in the
// format `PREFIX key=value key=value`, for a script to read: `adb logcat -v time -s DeckVR`.
//
// Rules: no logging on every tick (HeadMouse runs at ~250 Hz and logging there would cause the very jerk we want to measure).
// Components count inside the loop and print a summary per second, plus one line per discrete event.
//
// The level can change while the phone is in the headset: adb shell am broadcast -a com.limelight.debug.DECKVR_LOG --es mode off|on|verbose
//   off     = nothing
//   on      = per-second summaries and events (default)
//   verbose = also every isolated spike (late tick, large mouse step), at most 10 per second
public final class VrLog {
    public static final String TAG = "DeckVR";
    public static final String ACTION = "com.limelight.debug.DECKVR_LOG";

    public static final int OFF = 0;
    public static final int ON = 1;
    public static final int VERBOSE = 2;

    private static volatile int level = ON;

    private VrLog() {}

    public static void setLevel(int newLevel) {
        level = newLevel;
        // Always shows up, even when turning off (tells that the silence is on purpose)
        Log.i(TAG, "LOG level=" + (newLevel == OFF ? "off" : newLevel == VERBOSE ? "verbose" : "on"));
    }

    // off | on | verbose; -1 if the text is not recognized
    public static int parse(String mode) {
        if (mode == null) return -1;
        switch (mode.toLowerCase(Locale.ROOT)) {
            case "off": return OFF;
            case "on": return ON;
            case "verbose": return VERBOSE;
            default: return -1;
        }
    }

    public static boolean on() {
        return level >= ON;
    }

    public static boolean verbose() {
        return level >= VERBOSE;
    }

    // Numbers always come out with a decimal point (Locale.US), whatever the phone's language
    public static void i(String component, String format, Object... args) {
        if (level >= ON) {
            Log.i(TAG, component + " " + String.format(Locale.US, format, args));
        }
    }

    public static void v(String component, String format, Object... args) {
        if (level >= VERBOSE) {
            Log.i(TAG, component + " " + String.format(Locale.US, format, args));
        }
    }

    public static void w(String component, String format, Object... args) {
        if (level >= ON) {
            Log.w(TAG, component + " " + String.format(Locale.US, format, args));
        }
    }
}
