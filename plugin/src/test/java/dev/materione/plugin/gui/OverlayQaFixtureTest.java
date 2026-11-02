package dev.materione.plugin.gui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.testing.golden.GoldenFiles;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class OverlayQaFixtureTest {
    @Test
    void ownershipFixtureCompilesItsInputAndPrechangeRules() throws Exception {
        var fixture = new GuiFixture();
        var path = GoldenFiles.fixturesRoot().resolve("packs").resolve("overlay")
                .resolve("guis").resolve("ownership.yml");
        var definition = fixture.definition(Files.readString(path));
        assertThat(definition.skipped()).isEmpty();
        var live = fixture.gui(Files.readString(path));
        assertThat(fixture.diagnostics).isEmpty();
        assertThat(live.logic().rules("gui.slot_change")).hasSize(1);
        assertThat(live.logic().rules("gui.click")).hasSize(1);
        assertThat(live.inputs().at(4)).isPresent();
    }
}
