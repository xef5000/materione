package dev.materione.plugin.protection;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.protection.ProtectionMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code protection_*} settings (protection.md section 4): absent keys are the defaults, values are read case-blind, and a
 * bad value falls back to its default with {@code MTR-0123} and touches nothing else.
 */
class ProtectionSettingsTest {

    private final List<Diagnostic> diagnostics = new ArrayList<>();

    @Test
    void aFreshServerGetsBothAndAsksTheActorless() {
        ProtectionSettings settings = ProtectionSettings.read(Map.of(), diagnostics::add);

        assertThat(settings).isEqualTo(ProtectionSettings.DEFAULTS);
        assertThat(settings.mode()).isEqualTo(ProtectionMode.BOTH);
        assertThat(settings.askWithoutActor()).isTrue();
        assertThat(diagnostics).isEmpty();
    }

    @Test
    void theThreeModesAndTheSwitchAreReadIgnoringCase() {
        assertThat(ProtectionSettings.read(Map.of("protection_mode", "event"), diagnostics::add).mode())
                .isEqualTo(ProtectionMode.EVENT);
        assertThat(ProtectionSettings.read(Map.of("protection_mode", " PORT "), diagnostics::add).mode())
                .isEqualTo(ProtectionMode.PORT);
        assertThat(ProtectionSettings.read(Map.of("protection_mode", "Both"), diagnostics::add).mode())
                .isEqualTo(ProtectionMode.BOTH);
        assertThat(ProtectionSettings.read(Map.of("protection_no_actor", "allow"), diagnostics::add).askWithoutActor())
                .isFalse();
        assertThat(ProtectionSettings.read(Map.of("protection_no_actor", "ASK"), diagnostics::add).askWithoutActor())
                .isTrue();
        assertThat(diagnostics).isEmpty();
    }

    @Test
    void aBadValueFallsBackToItsDefaultWithOneWarningAndTheOtherKeyIsUntouched() {
        ProtectionSettings settings = ProtectionSettings.read(
                Map.of("protection_mode", "strict", "protection_no_actor", "allow"), diagnostics::add);

        assertThat(settings.mode()).isEqualTo(ProtectionMode.BOTH);
        assertThat(settings.askWithoutActor()).isFalse();
        assertThat(diagnostics).singleElement().satisfies(d -> {
            assertThat(d.code()).isEqualTo(DiagnosticCodes.PROTECTION_SETTING_INVALID);
            assertThat(d.message()).contains("protection_mode", "strict", "event, port or both", "both");
        });
    }

    @Test
    void aValueOfTheWrongTypeIsABadValueToo() {
        ProtectionSettings settings = ProtectionSettings.read(
                Map.of("protection_mode", 3, "protection_no_actor", true), diagnostics::add);

        assertThat(settings).isEqualTo(ProtectionSettings.DEFAULTS);
        assertThat(diagnostics).hasSize(2).allSatisfy(d ->
                assertThat(d.code()).isEqualTo(DiagnosticCodes.PROTECTION_SETTING_INVALID));
    }
}
