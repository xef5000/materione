package dev.materione.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Fail-soft proof (master D6): a deliberately broken settings file must NOT
 * stop the server or the plugin — the boot completes, doctor answers, and
 * the MTR-0004 diagnostic is visible in the log. Broken settings are
 * preserved and defaults take over (core SettingsService).
 */
@Tag("integration")
class PaperFailSoftIT {

    @Test
    void brokenSettingsStillBootAndDoctor() {
        HarnessOptions base = HarnessOptions.fromProperties();
        // "{ [ this is: not valid yaml" — parse error, not just unknown keys.
        HarnessOptions scenario = base.withScenario(
                base.workRoot().resolve("failsoft"), "{ [ this is: not valid yaml");

        // MTR-0004: the injected settings fault; MTR-0007: the scenario's
        // standard broken-item fault injection
        HarnessResult result = new PaperHarnessRunner().run(scenario, Set.of("MTR-0004", "MTR-0007"));
        assertThat(result.ok()).as(result.summary()).isTrue();

        // Read the log from the run's actual workspace (result.workDir), not a
        // path recomputed here — the runner nests each scenario under the work root.
        assertThat(readLog(result.workDir())).contains("MTR-0004");
    }

    private static String readLog(Path workRoot) {
        try {
            return java.nio.file.Files.readString(
                    workRoot.resolve("logs").resolve("latest.log"), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return "";
        }
    }
}
