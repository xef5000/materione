package dev.materione.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The window type and top size of a hopper (5) and a dispenser (9) created through the legacy
 * {@code createInventory(holder, InventoryType, Component)} path, on the 1.21.4 floor (gui.md 12.2.6).
 * The screen itself is judged by the 26.1.2 probe; see {@link ContainerWindowRunner}.
 */
@Tag("integration")
class PaperContainerWindowIT {

    @Test
    void hopperAndDispenserOpenAsTheirOwnMenusWithTheirOwnSizes() {
        HarnessResult result = new ContainerWindowRunner().run(HarnessOptions.fromProperties());
        assertThat(result.ok()).as(result.summary()).isTrue();
    }
}
