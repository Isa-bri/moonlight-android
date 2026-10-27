package com.limelight.vr;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

// Gravador de rastros brutos (opt-in; desligado por padrão) para reexecutar a lógica dos controles num replay, no celular ou
// na JVM. Grava as ENTRADAS das classes puras, não as saídas: o replay refaz as saídas com o código atual.
//
// Formato (CSV, uma linha por evento; `tc` = System.nanoTime()/1e6 no momento do registro, base comum a todas as fontes;
// `t` = o timestamp que a própria classe recebeu, na base dela):
//   cfg,tc,chave=valor;chave=valor        parâmetros em vigor (sens, borda, ar_cm, ...)
//   m,tc,rotulo                           marca de verdade-de-campo ("parada", "frente", ...) enviada por broadcast
//   ar,tc,t,x,z,hx,hz,rastreando          pose do ARCore (hx,hz = olhar horizontal)
//   acc,tc,t,x,y,z                        acelerômetro
//   hm,tc,t,yaw,pitch,horiz,andando,pausado   leitura da cabeça a cada poll (yaw/pitch em graus)
//   v,tc,evento                           botões de volume (recentrar, pausa, embreagem)
// Nunca grava a partir do tick de 4 ms: as linhas entram numa fila limitada e uma thread própria escreve; se a fila encher,
// a linha é descartada e contada (`dropped`), nunca bloqueia.
public final class VrTrace {
    private static final int QUEUE_SIZE = 16384;

    private static volatile boolean on;
    private static final AtomicInteger dropped = new AtomicInteger();
    private static ArrayBlockingQueue<String> queue;
    private static Thread writer;
    private static volatile boolean closing;

    private VrTrace() {}

    public static boolean on() {
        return on;
    }

    public static int dropped() {
        return dropped.get();
    }

    public static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    public static synchronized boolean start(File file, String cfg) {
        if (on) {
            return false;
        }
        final BufferedWriter out;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return false;
            }
            out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8), 1 << 16);
        } catch (IOException e) {
            return false;
        }
        queue = new ArrayBlockingQueue<>(QUEUE_SIZE);
        dropped.set(0);
        closing = false;
        final ArrayBlockingQueue<String> q = queue;
        writer = new Thread(() -> {
            try {
                String line;
                while (!(closing && q.isEmpty())) {
                    line = q.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (line != null) {
                        out.write(line);
                        out.write('\n');
                    }
                    else {
                        out.flush();
                    }
                }
                out.flush();
            } catch (IOException | InterruptedException ignored) {
            } finally {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }, "DeckVR-Trace");
        writer.setPriority(Thread.MIN_PRIORITY);
        writer.start();
        on = true;
        rec("cfg," + nowMs() + "," + cfg);
        return true;
    }

    public static synchronized void stop() {
        if (!on) {
            return;
        }
        on = false;
        closing = true;
        try {
            writer.join(3000);
        } catch (InterruptedException ignored) {
        }
        writer = null;
    }

    public static void rec(String line) {
        ArrayBlockingQueue<String> q = queue;
        if (on && q != null && !q.offer(line)) {
            dropped.incrementAndGet();
        }
    }

    public static void mark(String label) {
        rec("m," + nowMs() + "," + label);
    }

    // As fontes montam as linhas com concatenação simples (Float.toString usa sempre ponto decimal)
    public static void ar(long tNs, float x, float z, float hx, float hz, boolean tracking) {
        if (on) {
            rec("ar," + nowMs() + "," + tNs + "," + x + "," + z + "," + hx + "," + hz + "," + (tracking ? 1 : 0));
        }
    }

    public static void acc(long tNs, float x, float y, float z) {
        if (on) {
            rec("acc," + nowMs() + "," + tNs + "," + x + "," + y + "," + z);
        }
    }

    public static void hm(long tNs, float yaw, float pitch, float horiz, boolean walking, boolean paused) {
        if (on) {
            rec("hm," + nowMs() + "," + tNs + "," + yaw + "," + pitch + "," + horiz + "," + (walking ? 1 : 0) + "," + (paused ? 1 : 0));
        }
    }

    public static void volume(String event) {
        if (on) {
            rec("v," + nowMs() + "," + event);
        }
    }
}
