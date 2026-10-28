package dev.materione.api.gui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.NamespacedId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GuiComponentConfigurationTest {

    private static final NamespacedId GUI = NamespacedId.parse("example:shop");

    private static GuiComponentConfiguration of(Map<String, Object> parameters) {
        return new GuiComponentConfiguration(GUI, "layer", 3, parameters);
    }

    @Test
    void snapshotsNestedParametersWithoutMutatingTheCaller() {
        var style = new LinkedHashMap<String, Object>(Map.of("fill", "#FFFFFF"));
        var list = new ArrayList<Object>(List.of("a"));
        var raw = new LinkedHashMap<String, Object>(Map.of("style", style, "tags", list));
        var configuration = of(raw);
        style.put("fill", "#000000");
        list.clear();
        raw.clear();

        assertThat(configuration.mapping("style").get("fill")).isEqualTo("#FFFFFF");
        assertThat(configuration.strings("tags")).containsExactly("a");
        assertThatThrownBy(() -> configuration.parameters().put("bad", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> configuration.mapping("style").put("bad", true))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void integersAreStrictAndBoundedAndRequiredWhenAsked() {
        assertThat(of(Map.of("x", 7)).integer("x", 0, 0, 10)).isEqualTo(7);
        assertThat(of(Map.of()).integer("x", 4, 0, 10)).isEqualTo(4);
        for (Object value : List.of(1.5, Double.NaN, Double.POSITIVE_INFINITY, "3", true, 11)) {
            assertThatThrownBy(() -> of(Map.of("x", value)).integer("x", 0, 0, 10))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("layer layer: x");
        }
        assertThatThrownBy(() -> of(Map.of()).requiredInteger("width", 1, 512))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("width is required");
        assertThat(of(Map.of("width", 176)).requiredInteger("width", 1, 512)).isEqualTo(176);
    }

    @Test
    void colourReadsAcceptRgbAndArgbAndRejectEverythingElse() {
        assertThat(of(Map.of("c", "#404040")).color("c", 0)).isEqualTo(0xFF404040);
        assertThat(of(Map.of("c", "#80FF0000")).color("c", 0)).isEqualTo(0x80FF0000);
        assertThat(of(Map.of()).color("c", 0xFF123456)).isEqualTo(0xFF123456);
        for (String value : List.of("red", "#FFF", "#GGGGGG", "404040", "#1234567")) {
            assertThatThrownBy(() -> of(Map.of("c", value)).color("c", 0))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("#RRGGBB");
        }
    }

    @Test
    void textIsStrictWhenPresentAndAbsentWhenNotRequired() {
        assertThat(of(Map.of()).text("font", "smallcaps")).isEqualTo("smallcaps");
        assertThat(of(Map.of()).text("note", null)).isNull();
        assertThatThrownBy(() -> of(Map.of("note", "  ")).text("note", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(Map.of()).requiredText("text"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("required");
    }

    @Test
    void booleansAndStringListsAreNotCoerced() {
        assertThat(of(Map.of("on", true)).bool("on", false)).isTrue();
        assertThat(of(Map.of()).bool("on", false)).isFalse();
        assertThatThrownBy(() -> of(Map.of("on", "true")).bool("on", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(Map.of("tags", List.of("ok", 3))).strings("tags"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(Map.of("style", "not a map")).mapping("style"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRecordCarriesTheGuiAndLayerForDiagnostics() {
        var configuration = of(Map.of());
        assertThat(configuration.guiId()).isEqualTo(GUI);
        assertThat(configuration.layerId()).isEqualTo("layer");
        assertThat(configuration.rows()).isEqualTo(3);
    }
}
