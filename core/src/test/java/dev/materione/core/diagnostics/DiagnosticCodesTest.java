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
        // MTR-0060 unknown variable root for the trigger: logic.md §10, §16 (#208);
        // MTR-0061 optional integration provider skipped, MTR-0062 currency rejected: logic.md §18 (#213);
        // MTR-0063 undeclared or reserved variable, MTR-0064 type mismatch, MTR-0065 store failed:
        // logic.md §3, §20, #211;
        // MTR-0066 GUI open argument unreadable, MTR-0067 open command conflict, MTR-0068 open requirement
        // does not load, MTR-0069 open command tree needs a restart: logic.md §23, #215;
        // MTR-0070 GUI item source rejected, MTR-0071 GUI refresh clock invalid,
        // MTR-0072 GUI item source unresolved at render, MTR-0073 GUI dynamic layer failed at render:
        // logic.md §24, #216;
        // MTR-0074 input accept invalid, MTR-0075 static item in an input slot, MTR-0076 input setting ignored:
        // logic.md §25, #218;
        // MTR-0077 to MTR-0080 GUI list source, filter, read and input-slot problems: logic.md §28, #219;
        // MTR-0081 prompt names an unknown input method, MTR-0082 prompt answers into a persistent variable,
        // MTR-0083 GUI not shown again after a prompt, MTR-0084 input method could not start: logic.md §6.6, §29, #220;
        // MTR-0085 recipe does not load, MTR-0086 recipe parameter refused, MTR-0087 recipe expansion does not validate,
        // MTR-0088 recipe action unavailable: logic.md §30, #222;
        // MTR-0089 live trace subscription refused: logic.md §32, #226;
        // MTR-0090 GUI art past the art cap, MTR-0091 GUI art tile breaks a provider limit,
        // MTR-0092 clickable GUI layer outside the container: gui.md section 11, #276;
        // MTR-0093 template chain loops, MTR-0094 template reference names a template and an item:
        // plugin.md section 3.1, #203;
        // MTR-0095 pack id belongs to a built-in pack, MTR-0096 built-in pack unavailable: master section 7.3, #8;
        // MTR-0097 GUI container is not supported: gui.md section 12.4, #290;
        // MTR-0098 GUI container cannot be opened on this server: gui.md section 12.6, #292;
        // MTR-0100 the platform lacks checkpoint support, MTR-0101 a player's escrow state is unreadable,
        // MTR-0102 a strict input transfer could not be recorded, MTR-0103 recovery left stacks owed:
        // gui-input-durability.md section 5.9, #309 and #310;
        // MTR-0104 a transaction cannot be reverted: codemode.md section 10, #344;
        // MTR-0105 a rule step names content no pack declares: codemode.md section 10, #363)
        assertThat(DiagnosticCodes.all()).hasSize(104);
    }
}
