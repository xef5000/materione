package dev.materione.core.diagnostics;

import dev.materione.api.diagnostics.DiagnosticCodes;
import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.diagnostics.DiagnosticCode;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DiagnosticCodesTest {

    /** Codes are API (plugin architecture §5): unique and format-locked. */
    @Test
    void codesAreUniqueAndWellFormed() {
        Set<String> seen = new HashSet<>();
        for (DiagnosticCode code : DiagnosticCodes.all()) {
            assertThat(code.code()).matches("MTR-\\d{4}");
            assertThat(seen.add(code.code()))
                    .as("duplicate code %s", code.code())
                    .isTrue();
            assertThat(code.summary()).isNotBlank();
        }
        // a deliberate pin: adding a code is an API change, so it moves this number on purpose
        // (MTR-0054 GUI slot item not live, MTR-0055 permission denied: gui.md §5;
        // MTR-0056 duplicate resource id across files: plugin.md §5, #194;
        // MTR-0057 logic step rejected at load: logic.md §10, §14;
        // MTR-0058 flow call cannot be linked, MTR-0059 flow run aborted by its budget,
        // MTR-0060 unknown variable root for the trigger: logic.md §10, §16, #208;
        // MTR-0063 undeclared or reserved variable, MTR-0064 type mismatch, MTR-0065 store failed:
        // logic.md §3, §17, #211)
        assertThat(DiagnosticCodes.all()).hasSize(65);
    }
}
