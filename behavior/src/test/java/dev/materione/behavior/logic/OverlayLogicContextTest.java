package dev.materione.behavior.logic;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.TemplateText;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The entry overlay of a list (logic.md §6.7): {@code entry.*} from the entry, everything else from the window. */
class OverlayLogicContextTest {

    private static MapLogicContext window() {
        var player = new LinkedHashMap<String, Object>();
        player.put("name", "Probe");
        var root = new LinkedHashMap<String, Object>();
        root.put("player", player);
        root.put("vars", new LinkedHashMap<String, Object>());
        root.put("gui", Map.of("page", Map.of("players", Map.of("number", 2, "count", 4))));
        return new MapLogicContext(root);
    }

    private static Map<String, Object> entry(String name, int level) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("name", name);
        entry.put("level", level);
        entry.put("missing", null);
        return entry;
    }

    @Test
    void theEntryIsReadAsEntryAndTheWindowAsItself() {
        LogicContext overlay = new OverlayLogicContext(window(), "entry", entry("Ada", 3));

        assertThat(overlay.value("entry.name")).isEqualTo("Ada");
        assertThat(overlay.value("entry.level")).isEqualTo(3);
        assertThat(overlay.value("entry.missing")).isNull();
        assertThat(overlay.value("entry.absent.deeper")).isNull();
        assertThat(overlay.value("player.name")).isEqualTo("Probe");
        assertThat(overlay.value("gui.page.players.count")).isEqualTo(4);
    }

    @Test
    void anExpressionSeesTheEntryThroughTheOverlay() {
        LogicContext overlay = new OverlayLogicContext(window(), "entry", entry("Ada", 3));
        TemplateText text = EngineExpressions.INSTANCE.template("${entry.name}: ${entry.level > 2}");

        assertThat(text.render(overlay)).isEqualTo("Ada: true");
    }

    @Test
    void eachOverlayReadsItsOwnEntryOverTheSameWindow() {
        var window = window();
        assertThat(new OverlayLogicContext(window, "entry", entry("Ada", 3)).value("entry.name")).isEqualTo("Ada");
        assertThat(new OverlayLogicContext(window, "entry", entry("Grace", 9)).value("entry.name")).isEqualTo("Grace");
        assertThat(window.value("entry.name")).isNull();
    }

    @Test
    void facetsAndPlaceholdersAreTheBasesAndAnEmptyRootIsNotAnEntry() {
        var base = window();
        var overlay = new OverlayLogicContext(base, "entry", entry("Ada", 3));
        var label = dev.materione.api.logic.ContextFacet.of("materione:test_label", String.class);
        base.with(label, "window");

        assertThat(overlay.facet(label)).contains("window");
        assertThat(overlay.resolve(List.of())).isEqualTo(base.resolve(List.of()));
        assertThat(overlay.applyPlaceholders("%x%")).isEqualTo("%x%");
    }
}
