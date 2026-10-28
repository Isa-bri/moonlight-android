package com.limelight.vr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Replay of a recorded trace (see VrTrace) through the SAME classes that run on the phone: ArPipeline, StepPipeline and HeadMotion.
// No Android: runs on the JVM (Gradle tests) and inside the app (DECKVR_REPLAY broadcast), and, with a `Sink` and a `Pacer`, live,
// sending the outputs to the real path (keys, mouse, analog stick) at the pace they were recorded.
//
// Rules (.rules file, one per line; `#` comments):
//   margin 1.0                              seconds ignored at the start and end of each segment (transition)
//   rule <label> <metric> <op> <value>      applies to every segment whose label is equal to or starts with <label>
// Metrics: key_W_fraction, key_A_fraction, key_S_fraction, key_D_fraction, max_keys_fraction, stick_max, stick_x_avg,
// stick_y_avg, mouse_per_s, mouse_x_per_s, mouse_y_per_s.   Operators: <=  >=
// A rule with no matching segment FAILS (it never passes just because it was not checked).
public final class ReplayRunner {
    public interface Sink {
        void key(char key, boolean down);
        void stick(float x, float y);
        void mouse(int dx, int dy);
    }

    // Live: waits until the trace instant (relative to the first) before processing the line; false = abort now
    public interface Pacer {
        boolean waitUntil(long relativeMs);
    }

    public static final class Result {
        public final String name;
        public final List<String> lines = new ArrayList<>();
        public int checked, failed;

        Result(String name) {
            this.name = name;
        }

        public boolean passed() {
            return checked > 0 && failed == 0;
        }
    }

    private static final String KEYS = "WASD";
    private static final String KEY_METRIC_PREFIX = "key_";
    private static final String KEY_METRIC_SUFFIX = "_fraction";

    // Trace line already split, to sort by `tc`
    private static final class Rec implements Comparable<Rec> {
        final long tc;
        final int order;
        final String[] f;

        Rec(long tc, int order, String[] f) {
            this.tc = tc;
            this.order = order;
            this.f = f;
        }

        @Override
        public int compareTo(Rec o) {
            return tc != o.tc ? Long.compare(tc, o.tc) : Integer.compare(order, o.order);
        }
    }

    private static final class Timeline {
        final List<long[]> keys = new ArrayList<>();       // {tc, key index, 1=down/0=up}
        final List<double[]> sticks = new ArrayList<>();   // {tc, x, y}
        final List<double[]> mice = new ArrayList<>();     // {tc, dx, dy}
        final List<Object[]> marks = new ArrayList<>();    // {tc, label}
        long start = Long.MAX_VALUE, end = Long.MIN_VALUE;
    }

    private ReplayRunner() {}

    // Runs the trace and checks the rules
    public static Result run(String name, List<String> trace, List<String> rules) {
        Timeline tl = play(trace, null, null);
        return evaluate(name, tl, rules);
    }

    // Only plays it back (live, with `sink`/`pacer`); returns how many lines it processed
    public static int playLive(List<String> trace, Sink sink, Pacer pacer) {
        Timeline tl = play(trace, sink, pacer);
        return tl.marks.size();
    }

    private static Map<String, String> parseCfg(String s) {
        Map<String, String> m = new HashMap<>();
        for (String kv : s.split(";")) {
            int i = kv.indexOf('=');
            if (i > 0) {
                m.put(kv.substring(0, i).trim(), kv.substring(i + 1).trim());
            }
        }
        return m;
    }

    private static float num(Map<String, String> m, String k, float def) {
        String v = m.get(k);
        if (v == null) {
            return def;
        }
        try {
            return Float.parseFloat(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean bool(Map<String, String> m, String k) {
        String v = m.get(k);
        return v != null && (v.equals("true") || v.equals("1"));
    }

    private static Timeline play(List<String> trace, final Sink sink, Pacer pacer) {
        final Timeline tl = new Timeline();
        List<Rec> recs = new ArrayList<>();
        Map<String, String> cfg = new HashMap<>();
        int order = 0;
        for (String line : trace) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split(",", -1);
            if (f.length < 3) {
                continue;
            }
            long tc;
            try {
                tc = Long.parseLong(f[1].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (f[0].equals("cfg")) {
                cfg = parseCfg(f[2]);
                continue;
            }
            recs.add(new Rec(tc, order++, f));
        }
        Collections.sort(recs);
        if (recs.isEmpty()) {
            return tl;
        }
        final long base = recs.get(0).tc;

        // What produces output: the same production classes, with the recorded parameters
        final long[] now = {base};
        MoveOutput out = new MoveOutput() {
            @Override
            public void key(char key, boolean down) {
                int idx = KEYS.indexOf(key);
                tl.keys.add(new long[]{now[0], idx, down ? 1 : 0});
                if (sink != null) {
                    sink.key(key, down);
                }
            }

            @Override
            public void stick(float x, float y) {
                tl.sticks.add(new double[]{now[0], x, y});
                if (sink != null) {
                    sink.stick(x, y);
                }
            }
        };
        boolean analog = bool(cfg, "analog");
        ArPipeline ar = bool(cfg, "ar")
                ? new ArPipeline(num(cfg, "ar_cm", 10) / 100f, bool(cfg, "ar_hold"), analog, out) : null;
        StepPipeline steps = bool(cfg, "steps")
                ? new StepPipeline(num(cfg, "steps_threshold", 2.0f), 380_000_000L, 1_200_000_000L, 900_000_000L, analog, out) : null;
        HeadMotion head = new HeadMotion(num(cfg, "sens", 10), num(cfg, "vert", 100) / 100f);
        head.edgeDeg = bool(cfg, "edge") ? num(cfg, "edge_deg", 45) : 0;
        head.snapTurn = bool(cfg, "edge_steps");
        head.walkPitchBandDeg = num(cfg, "band", 1.5f);

        for (Rec r : recs) {
            now[0] = r.tc;
            tl.start = Math.min(tl.start, r.tc);
            tl.end = Math.max(tl.end, r.tc);
            if (pacer != null && !pacer.waitUntil(r.tc - base)) {
                break;
            }
            String[] f = r.f;
            try {
                switch (f[0]) {
                    case "m":
                        tl.marks.add(new Object[]{r.tc, f[2]});
                        break;
                    case "ar":
                        if (ar != null) {
                            ar.onFrame(Long.parseLong(f[2]), Float.parseFloat(f[3]), Float.parseFloat(f[4]),
                                    Float.parseFloat(f[5]), Float.parseFloat(f[6]), f[7].equals("1"));
                        }
                        break;
                    case "acc":
                        if (steps != null) {
                            steps.onSample(Float.parseFloat(f[3]), Float.parseFloat(f[4]), Float.parseFloat(f[5]), Long.parseLong(f[2]));
                        }
                        break;
                    case "hm": {
                        boolean paused = f[7].equals("1");
                        if (paused) {
                            head.invalidate();
                        }
                        else if (head.update(Float.parseFloat(f[3]), Float.parseFloat(f[4]), Float.parseFloat(f[5]),
                                Long.parseLong(f[2]), f[6].equals("1")) && (head.mx != 0 || head.my != 0)) {
                            tl.mice.add(new double[]{r.tc, head.mx, head.my});
                            if (sink != null) {
                                sink.mouse(head.mx, head.my);
                            }
                        }
                        break;
                    }
                    case "v":
                        if (f[2].equals("recenter") && ar != null) {
                            ar.requestRecenter();
                        }
                        break;
                    default:
                        break;
                }
            } catch (RuntimeException e) {
                // malformed line: ignore it (the replay must not bring the app down)
            }
        }
        if (ar != null) {
            ar.releaseAll();
        }
        if (steps != null) {
            steps.stop(0);
        }
        return tl;
    }

    // ---- Rules ----

    private static Result evaluate(String name, Timeline tl, List<String> rules) {
        Result res = new Result(name);
        double margin = 1.0;
        List<String[]> parsedRules = new ArrayList<>();
        for (String raw : rules) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] p = line.split("\\s+");
            if (p[0].equals("margin") && p.length >= 2) {
                margin = Double.parseDouble(p[1]);
            }
            else if (p[0].equals("rule") && p.length >= 5) {
                parsedRules.add(p);
            }
        }
        // Segments from the marks
        List<Object[]> segs = new ArrayList<>();
        List<Object[]> marks = new ArrayList<>(tl.marks);
        for (int i = 0; i < marks.size(); i++) {
            long a = (Long) marks.get(i)[0];
            long b = i + 1 < marks.size() ? (Long) marks.get(i + 1)[0] : tl.end;
            segs.add(new Object[]{marks.get(i)[1], a, b});
        }
        if (segs.isEmpty() && tl.end > tl.start) {
            segs.add(new Object[]{"all", tl.start, tl.end});
        }
        for (String[] r : parsedRules) {
            String ruleLabel = r[1], metricName = r[2], op = r[3];
            double limit = Double.parseDouble(r[4]);
            int matched = 0;
            for (Object[] seg : segs) {
                String label = (String) seg[0];
                if (!(label.equals(ruleLabel) || label.startsWith(ruleLabel))) {
                    continue;
                }
                long a = (Long) seg[1], b = (Long) seg[2];
                long m = Math.round(margin * 1000);
                long wa = a + m, wb = b - m;
                if (wb - wa < 300) { // short segment: use the middle
                    long len = b - a;
                    wa = a + len / 4;
                    wb = b - len / 4;
                }
                if (wb <= wa) {
                    continue;
                }
                matched++;
                double v = metric(tl, metricName, wa, wb);
                boolean ok = op.equals("<=") ? v <= limit + 1e-9 : v >= limit - 1e-9;
                res.checked++;
                if (!ok) {
                    res.failed++;
                }
                res.lines.add(String.format(Locale.US, "%s segment=%s rule=\"%s %s %s %s\" measured=%.3f",
                        ok ? "PASSED" : "FAILED", label, ruleLabel, metricName, op, r[4], v));
            }
            if (matched == 0) {
                res.checked++;
                res.failed++;
                res.lines.add(String.format(Locale.US, "FAILED rule=\"%s %s %s %s\" no segment with that label (or too short): not checked",
                        ruleLabel, metricName, op, r[4]));
            }
        }
        if (parsedRules.isEmpty()) {
            res.checked++;
            res.failed++;
            res.lines.add("FAILED no rule in the file: nothing was checked");
        }
        return res;
    }

    private static double metric(Timeline tl, String metricName, long a, long b) {
        double dur = (b - a) / 1000.0;
        if (metricName.startsWith(KEY_METRIC_PREFIX) && metricName.endsWith(KEY_METRIC_SUFFIX)
                && metricName.length() > KEY_METRIC_PREFIX.length()) {
            return keyFraction(tl, KEYS.indexOf(metricName.charAt(KEY_METRIC_PREFIX.length())), a, b);
        }
        switch (metricName) {
            case "max_keys_fraction": {
                double mx = 0;
                for (int k = 0; k < 4; k++) {
                    mx = Math.max(mx, keyFraction(tl, k, a, b));
                }
                return mx;
            }
            case "stick_max": {
                double mx = 0;
                double[] cur = stickAt(tl, a);
                mx = Math.max(mx, Math.hypot(cur[0], cur[1]));
                for (double[] s : tl.sticks) {
                    if (s[0] >= a && s[0] < b) {
                        mx = Math.max(mx, Math.hypot(s[1], s[2]));
                    }
                }
                return mx;
            }
            case "stick_x_avg":
            case "stick_y_avg": {
                int c = metricName.equals("stick_x_avg") ? 0 : 1;
                double[] cur = stickAt(tl, a);
                double v = cur[c], t = a, acc = 0;
                for (double[] s : tl.sticks) {
                    if (s[0] <= a || s[0] >= b) {
                        continue;
                    }
                    acc += v * (s[0] - t);
                    t = s[0];
                    v = s[c + 1];
                }
                acc += v * (b - t);
                return acc / (b - a);
            }
            case "mouse_per_s":
            case "mouse_x_per_s":
            case "mouse_y_per_s": {
                double sx = 0, sy = 0, sabs = 0;
                for (double[] m : tl.mice) {
                    if (m[0] >= a && m[0] < b) {
                        sx += m[1];
                        sy += m[2];
                        sabs += Math.abs(m[1]) + Math.abs(m[2]);
                    }
                }
                return (metricName.equals("mouse_per_s") ? sabs : metricName.equals("mouse_x_per_s") ? sx : sy) / dur;
            }
            default:
                return Double.NaN;
        }
    }

    private static double keyFraction(Timeline tl, int key, long a, long b) {
        boolean down = false;
        long since = a;
        double total = 0;
        for (long[] e : tl.keys) {
            if (e[1] != key) {
                continue;
            }
            long t = e[0];
            if (t >= b) {
                break;
            }
            if (t <= a) {
                down = e[2] == 1;
                continue;
            }
            if (down) {
                total += t - since;
            }
            down = e[2] == 1;
            since = t;
        }
        if (down) {
            total += b - since;
        }
        return total / (b - a);
    }

    private static double[] stickAt(Timeline tl, long t) {
        double x = 0, y = 0;
        for (double[] s : tl.sticks) {
            if (s[0] > t) {
                break;
            }
            x = s[1];
            y = s[2];
        }
        return new double[]{x, y};
    }
}
