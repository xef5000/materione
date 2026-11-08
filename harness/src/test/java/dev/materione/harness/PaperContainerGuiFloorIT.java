package dev.materione.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The product runtime opens a hopper and a dispenser for a joined client on the 1.21.4 floor (gui.md 12.6, #292):
 * the window's type and size, its holder routes to a {@code PluginGui}, and the client receives the same window.
 * Opt-in ({@code -Pharness.containerGuiFloor=true} after {@code npm ci} in {@code harness/protocol}); see
 * {@link ContainerGuiFloorRunner}.
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "harness.containerGuiFloor", matches = "true")
class PaperContainerGuiFloorIT {

    @Test
    void theRuntimeOpensAHopperAndADispenserAsTheirOwnWindowsForAClient() {
        HarnessResult result = new ContainerGuiFloorRunner().run(HarnessOptions.fromProperties());
        assertThat(result.ok()).as(result.summary()).isTrue();
    }
}
