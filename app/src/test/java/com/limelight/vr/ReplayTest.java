package com.limelight.vr;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Re-runs, through the production classes, all traces in steamdeck-phone-vr/tests/traces (synthetic and real) against the
// rules that accompany each one. The folder comes from DECKVR_TRACES or from ../../steamdeck-phone-vr/tests/traces (sibling repository).
// Without the folder, the test is skipped (it does not pass just because it checked nothing).
public class ReplayTest {
    private static File folder() {
        String env = System.getenv("DECKVR_TRACES");
        return new File(env != null ? env : "../../steamdeck-phone-vr/tests/traces");
    }

    @Test
    public void allTracesMeetTheirRules() throws IOException {
        File dir = folder();
        assumeTrue("traces folder not found: " + dir.getAbsolutePath(), dir.isDirectory());
        File[] csvs = dir.listFiles((d, n) -> n.endsWith(".csv"));
        assumeTrue("no traces in " + dir, csvs != null && csvs.length > 0);
        Arrays.sort(csvs);
        List<String> failures = new ArrayList<>();
        for (File csv : csvs) {
            String base = csv.getName().substring(0, csv.getName().length() - 4);
            File rules = new File(dir, base + ".rules");
            if (!rules.exists()) {
                failures.add(base + ": no .rules file");
                continue;
            }
            ReplayRunner.Result r = ReplayRunner.run(base, Files.readAllLines(csv.toPath(), StandardCharsets.UTF_8),
                    Files.readAllLines(rules.toPath(), StandardCharsets.UTF_8));
            for (String l : r.lines) {
                if (l.startsWith("FAILED")) {
                    failures.add(base + ": " + l);
                }
            }
            if (r.checked == 0) {
                failures.add(base + ": nothing was checked");
            }
        }
        assertTrue("Replay failures:\n" + String.join("\n", failures), failures.isEmpty());
    }
}
