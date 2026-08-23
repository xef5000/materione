package dev.materione.core.diagnostics;

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
        assertThat(DiagnosticCodes.all()).hasSize(23);
    }
}
