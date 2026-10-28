package com.limelight.vr;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

// Recorder of raw traces (opt-in; off by default) to re-run the controls logic in a replay, on the phone or
// on the JVM. It records the INPUTS of the pure classes, not the outputs: the replay redoes the outputs with the current code.
//
// Format (CSV, one line per event; `tc` = System.nanoTime()/1e6 at the moment of recording, a base common to all sources;
// `t` = the timestamp the class itself received, in its own base):
//   cfg,tc,key=value;key=value            parameters in effect (sens, edge, ar_cm, ...)
//   m,tc,label                            ground-truth mark ("still", "forward", ...) sent by broadcast
//   ar,tc,t,x,z,hx,hz,tracking            ARCore pose (hx,hz = horizontal gaze)
//   acc,tc,t,x,y,z                        accelerometer
//   hm,tc,t,yaw,pitch,horiz,walking,paused    head reading on each poll (yaw/pitch in degrees)
//   v,tc,event                            volume buttons (recenter, pause_on, pause_off, clutch_on, clutch_off)
// Never records from the 4 ms tick: lines go into a bounded queue and a dedicated thread writes them; if the queue fills up,
// the line is dropped and counted (`dropped`), never blocking.
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

    // The sources build the lines with plain concatenation (Float.toString always uses a decimal point)
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
