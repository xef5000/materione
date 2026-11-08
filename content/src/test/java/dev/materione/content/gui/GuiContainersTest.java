package dev.materione.content.gui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.gui.GuiContainer;
import dev.materione.testing.golden.GoldenFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The container profile file (gui.md section 12.5.1): its digest is pinned by {@code fixtures/gui/containers/golden.txt},
 * and the chest profiles reproduce the geometry the chest compiler has always used, so a converted GUI compiles to the
 * same output (gui.md section 12.5.2).
 */
class GuiContainersTest {

    @Test
    void theFileMatchesItsGolden() throws IOException, NoSuchAlgorithmException {
        Path golden = GoldenFiles.fixturesRoot().resolve("gui/containers/golden.txt");
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(GuiContainers.sourceText().getBytes(StandardCharsets.UTF_8)));
        if (GoldenFiles.regenEnabled()) {
            Files.writeString(golden, Files.readString(golden).replaceAll("sha256=[0-9a-f]+", "sha256=" + digest));
        }
        Map<String, String> pinned = Files.readAllLines(golden).stream()
                .filter(line -> !line.startsWith(";") && line.contains("="))
                .collect(Collectors.toMap(line -> line.substring(0, line.indexOf('=')),
                        line -> line.substring(line.indexOf('=') + 1).strip()));
        assertThat(digest).isEqualTo(pinned.get("sha256"));
        assertThat(GuiContainers.all().stream().map(GuiContainer::id).collect(Collectors.joining(",")))
                .isEqualTo(pinned.get("containers"));
    }

    @Test
    void theChestsReproduceTheChestGeometry() {
        for (int rows = 1; rows <= 6; rows++) {
            GuiContainer chest = GuiContainers.byId("chest_" + rows).orElseThrow();
            assertThat(chest.slotCount()).isEqualTo(9 * rows);
            assertThat(chest.columns()).isEqualTo(9);
            assertThat(chest.height()).isEqualTo(114 + 18 * rows);
            assertThat(chest.width()).isEqualTo(176);
            assertThat(chest.inventoryY()).isEqualTo(31 + 18 * rows);
            assertThat(chest.inventoryTop()).isEqualTo(17 + 18 * rows);
            assertThat(chest.inventoryLabelY()).isEqualTo(20 + 18 * rows);
            assertThat(chest.titleCentred()).isFalse();
            assertThat(chest.titleX()).isEqualTo(8);
            assertThat(chest.titleY()).isEqualTo(6);
            for (int slot = 0; slot < chest.slotCount(); slot++) {
                assertThat(chest.slotX(slot)).isEqualTo(8 + 18 * (slot % 9));
                assertThat(chest.slotY(slot)).isEqualTo(18 + 18 * (slot / 9));
            }
            assertThat(chest.open()).isEqualTo(GuiContainer.Open.bySize(9 * rows));
        }
    }

    @Test
    void theHopperAndTheDispenserAreProfilesToo() {
        GuiContainer hopper = GuiContainers.byId("hopper").orElseThrow();
        assertThat(hopper.slotCount()).isEqualTo(5);
        assertThat(hopper.height()).isEqualTo(133);
        assertThat(hopper.slotX(4)).isEqualTo(44 + 18 * 4);
        assertThat(hopper.open()).isEqualTo(GuiContainer.Open.byType("HOPPER"));

        GuiContainer dispenser = GuiContainers.byId("dispenser").orElseThrow();
        assertThat(dispenser.slotCount()).isEqualTo(9);
        assertThat(dispenser.columns()).isEqualTo(3);
        assertThat(dispenser.titleCentred()).isTrue();
        assertThat(dispenser.slotX(4)).isEqualTo(62 + 18);
        assertThat(dispenser.slotY(4)).isEqualTo(17 + 18);
        assertThat(dispenser.open()).isEqualTo(GuiContainer.Open.byType("DISPENSER"));
    }

    @Test
    void anUnknownIdIsNotAProfile() {
        assertThat(GuiContainers.byId("chest_7")).isEmpty();
        assertThat(GuiContainers.byId("generic_9x3")).isEmpty();
        assertThat(GuiContainers.byId(null)).isEmpty();
        assertThat(GuiContainers.supportedIds()).startsWith("chest_1, chest_2");
    }

    @Test
    void aFileOfAnotherVersionIsRefused() {
        assertThatThrownBy(() -> GuiContainers.parse("{\"version\": 2, \"containers\": []}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version 1");
    }

    @Test
    void aProfileWithoutAnOpenDescriptorIsRefused() {
        String neither = "{\"version\": 1, \"containers\": [{\"id\": \"x\", \"label\": \"X\", \"group\": \"G\", "
                + "\"slotCount\": 9, \"columns\": 9, \"slots\": {\"x\": 8, \"y\": 18}, \"width\": 176, \"height\": 132, "
                + "\"inventory\": {\"x\": 8, \"y\": 49}, \"inventoryTop\": 35, \"title\": {\"x\": 8, \"y\": 6}, "
                + "\"inventoryLabel\": {\"x\": 8, \"y\": 38}, \"underlay\": [], \"open\": {}}]}";
        assertThatThrownBy(() -> GuiContainers.parse(neither))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("open.inventoryType");
    }

    @Test
    void theJsonReaderIsStrict() {
        assertThatThrownBy(() -> GuiContainers.parse("{\"version\": 1, \"containers\": [] } trailing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trailing");
    }
}
