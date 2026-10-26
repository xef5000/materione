package dev.materione.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.DiagnosticTarget;
import dev.materione.api.diagnostics.Severity;
import org.junit.jupiter.api.Test;

/**
 * The diagnostic wire shape shared by the RPC feed and live pushes: a field
 * target rides along when the diagnostic names one (web.md §6), and is
 * absent otherwise so older clients see the shape they always did.
 */
class SessionPushTest {

    @Test
    void entryJsonCarriesTheFieldTargetOfADiagnostic() {
        Diagnostic diagnostic = new Diagnostic(
                DiagnosticCodes.UNRESOLVED_REFERENCE, Severity.ERROR,
                "unresolved template 'base_ittm'", NamespacedId.parse("faulty:broken_item"), null,
                "did you mean 'faulty:base_item'?", DiagnosticTarget.field("template"));

        JsonObject entry = SessionPush.entryJson(diagnostic);

        assertThat(entry.getAsJsonObject("target").getAsJsonArray("field")).hasSize(1);
        assertThat(entry.getAsJsonObject("target").getAsJsonArray("field").get(0).getAsString())
                .isEqualTo("template");
        // the rest of the shape is unchanged
        assertThat(entry.get("code").getAsString()).isEqualTo(DiagnosticCodes.UNRESOLVED_REFERENCE.code());
        assertThat(entry.get("severity").getAsString()).isEqualTo("error");
        assertThat(entry.get("message").getAsString()).isEqualTo("unresolved template 'base_ittm'");
        assertThat(entry.get("resource").getAsString()).isEqualTo("faulty:broken_item");
        assertThat(entry.get("hint").getAsString()).isEqualTo("did you mean 'faulty:base_item'?");
    }

    @Test
    void nestedFieldPathsKeepTheirSegmentOrder() {
        Diagnostic diagnostic = new Diagnostic(
                DiagnosticCodes.UNRESOLVED_REFERENCE, Severity.ERROR, "bad lore line",
                NamespacedId.parse("sample:thing"), null, null, DiagnosticTarget.field("lore", "0"));

        JsonObject entry = SessionPush.entryJson(diagnostic);

        var field = entry.getAsJsonObject("target").getAsJsonArray("field");
        assertThat(field).hasSize(2);
        assertThat(field.get(0).getAsString()).isEqualTo("lore");
        assertThat(field.get(1).getAsString()).isEqualTo("0");
    }

    @Test
    void entryJsonOmitsTheTargetWhenTheDiagnosticHasNone() {
        Diagnostic diagnostic = Diagnostic.warn(DiagnosticCodes.EDITOR_PROTOCOL_VIOLATION, "no field here");

        JsonObject entry = SessionPush.entryJson(diagnostic);

        assertThat(entry.has("target")).isFalse();
        assertThat(entry.get("message").getAsString()).isEqualTo("no field here");
    }
}
