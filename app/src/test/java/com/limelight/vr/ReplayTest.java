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

// Reexecuta, pelas classes de produção, todos os rastros de steamdeck-phone-vr/testes/rastros (sintéticos e reais) contra as
// regras que acompanham cada um. A pasta vem de DECKVR_RASTROS ou de ../../steamdeck-phone-vr/testes/rastros (repositório irmão).
// Sem a pasta, o teste é pulado (não passa por não ter conferido nada).
public class ReplayTest {
    private static File pasta() {
        String env = System.getenv("DECKVR_RASTROS");
        return new File(env != null ? env : "../../steamdeck-phone-vr/testes/rastros");
    }

    @Test
    public void todosOsRastrosCumpremAsRegras() throws IOException {
        File dir = pasta();
        assumeTrue("pasta de rastros não encontrada: " + dir.getAbsolutePath(), dir.isDirectory());
        File[] csvs = dir.listFiles((d, n) -> n.endsWith(".csv"));
        assumeTrue("nenhum rastro em " + dir, csvs != null && csvs.length > 0);
        Arrays.sort(csvs);
        List<String> falhas = new ArrayList<>();
        for (File csv : csvs) {
            String base = csv.getName().substring(0, csv.getName().length() - 4);
            File regras = new File(dir, base + ".regras");
            if (!regras.exists()) {
                falhas.add(base + ": sem arquivo .regras");
                continue;
            }
            ReplayRunner.Result r = ReplayRunner.run(base, Files.readAllLines(csv.toPath(), StandardCharsets.UTF_8),
                    Files.readAllLines(regras.toPath(), StandardCharsets.UTF_8));
            for (String l : r.lines) {
                if (l.startsWith("FALHOU")) {
                    falhas.add(base + ": " + l);
                }
            }
            if (r.checked == 0) {
                falhas.add(base + ": nada foi conferido");
            }
        }
        assertTrue("Falhas no replay:\n" + String.join("\n", falhas), falhas.isEmpty());
    }
}
