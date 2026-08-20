package dev.materione.api.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.NamespacedId;
import org.junit.jupiter.api.Test;

class DiagnosticsTypesTest {

    @Test
    void codeFormatIsEnforced() {
        assertThatThrownBy(() -> new DiagnosticCode("MTR-1", Severity.ERROR, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DiagnosticCode("mtr-0001", Severity.ERROR, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DiagnosticCode("MTR-abcd", Severity.ERROR, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        var ok = new DiagnosticCode("MTR-0001", Severity.ERROR, "too old");
        assertThat(ok.toString()).isEqualTo("MTR-0001");
    }

    @Test
    void diagnosticCarriesOptionalContext() {
        var code = new DiagnosticCode("MTR-0002", Severity.ERROR, "phase failed");
        var bare = Diagnostic.error(code, "phase 'commands' failed");
        assertThat(bare.resource()).isNull();
        assertThat(bare.location()).isNull();
        assertThat(bare.hint()).isNull();

        var located = new Diagnostic(
                code, Severity.WARN, "unresolved reference",
                NamespacedId.of("mystuff", "ruby_sword"),
                new SourceLocation("items/rubies.yml", 12, 3),
                "did you mean mystuff:ruby?");
        assertThat(located.location().toString()).isEqualTo("items/rubies.yml:12:3");
        assertThat(located.resource().toString()).isEqualTo("mystuff:ruby_sword");
    }
}
