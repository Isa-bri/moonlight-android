package com.limelight.vr;

import android.util.Log;

import java.util.Locale;

// Registro de diagnóstico dos controles VR (para deixar a cabeça, os passos e as teclas suaves e sem trancos).
//
// Tudo sai com a tag "DeckVR" e um prefixo de componente (HM, SENSOR, PASSO, LADO, AR, VOL, GL, REDE, CONFIG), no formato
// `PREFIXO chave=valor chave=valor`, para um script ler: `adb logcat -v time -s DeckVR`.
//
// Regras: nada de log a cada tick (o HeadMouse roda a ~250 Hz e logar ali causaria o tranco que se quer medir). Os
// componentes contam dentro do laço e imprimem um resumo por segundo, mais uma linha por evento discreto.
//
// Nível pode mudar com o celular no óculos: adb shell am broadcast -a com.limelight.debug.DECKVR_LOG --es mode off|on|verbose
//   off     = nada
//   on      = resumos por segundo e eventos (padrão)
//   verbose = também cada pico isolado (tick atrasado, passo grande do mouse), no máximo 10 por segundo
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
        // Sempre aparece, mesmo ao desligar (avisa que o silêncio é de propósito)
        Log.i(TAG, "LOG nivel=" + (newLevel == OFF ? "off" : newLevel == VERBOSE ? "verbose" : "on"));
    }

    // off | on | verbose; -1 se o texto não for reconhecido
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

    // Os números saem sempre com ponto decimal (Locale.US), qualquer que seja o idioma do celular
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
