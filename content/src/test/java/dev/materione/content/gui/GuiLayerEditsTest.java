package dev.materione.content.gui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.gui.GuiAuthoringApi.AddLayer;
import dev.materione.api.gui.GuiAuthoringApi.PatchLayer;
import dev.materione.api.gui.GuiAuthoringApi.RemoveLayers;
import dev.materione.api.gui.GuiAuthoringApi.ReorderLayers;
import dev.materione.api.gui.GuiAuthoringApi.SetScreen;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The pure layer edits (gui.md §7, §10 #9): order, refusals and what an edit leaves alone. */
class GuiLayerEditsTest {

    private static final Set<String> KNOWN = Set.of("materione:panel", "materione:slots", "materione:item");

    private final GuiLayerEdits edits = new GuiLayerEdits(type -> KNOWN.contains(
            type.contains(":") ? type : "materione:" + type));

    private static Map<String, Object> layer(String id, String type, Object... pairs) {
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("id", id);
        map.put("type", type);
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static List<Map<String, Object>> stack() {
        return List.of(
                layer("frame", "panel", "width", 176, "height", 132),
                layer("wells", "slots", "region", "container"),
                layer("sword", "item", "slot", 13, "item", "shop:sword", "comment", "kept"));
    }

    private static List<String> ids(List<Map<String, Object>> layers) {
        return layers.stream().map(layer -> (String) layer.get("id")).toList();
    }

    @Test
    void addsAtTheIndexWithGeneratedOrGivenIds() {
        var result = edits.apply("Shop", 3, stack(), List.of(
                new AddLayer(0, "panel", null, Map.of("x", 8)),
                new AddLayer(4, "slots", "extra", Map.of("region", "player"))));
        assertThat(ids(result.layers())).containsExactly("panel", "frame", "wells", "sword", "extra");
        assertThat(result.layers().get(0)).containsEntry("type", "panel").containsEntry("x", 8);
    }

    @Test
    void generatedIdsAreUniqueAndSafe() {
        var result = edits.apply("Shop", 3, stack(), List.of(
                new AddLayer(3, "materione:panel", null, Map.of()),
                new AddLayer(3, "panel", null, Map.of())));
        assertThat(ids(result.layers())).containsExactly("frame", "wells", "sword", "panel_2", "panel");
    }

    @Test
    void patchSetsAndRemovesFieldsAndKeepsTheRest() {
        var result = edits.apply("Shop", 3, stack(), List.of(
                new PatchLayer("sword", Map.of("amount", 4, "slot", 14), List.of("comment"))));
        Map<String, Object> sword = result.layers().get(2);
        assertThat(sword).containsEntry("slot", 14).containsEntry("amount", 4).containsEntry("item", "shop:sword");
        assertThat(sword).doesNotContainKey("comment");
        // a null value removes the field, as an absent one would read
        var set = new java.util.LinkedHashMap<String, Object>();
        set.put("item", null);
        var cleared = edits.apply("Shop", 3, stack(), List.of(new PatchLayer("sword", set, List.of())));
        assertThat(cleared.layers().get(2)).doesNotContainKey("item").containsEntry("slot", 13);
    }

    @Test
    void reorderIsAPermutationAndRemoveNamesExistingLayers() {
        var reordered = edits.apply("Shop", 3, stack(), List.of(new ReorderLayers(List.of("sword", "frame", "wells"))));
        assertThat(ids(reordered.layers())).containsExactly("sword", "frame", "wells");
        var removed = edits.apply("Shop", 3, stack(), List.of(new RemoveLayers(List.of("wells"))));
        assertThat(ids(removed.layers())).containsExactly("frame", "sword");
    }

    @Test
    void screenTitleAndRowsAreSetOrLeftAlone() {
        var result = edits.apply("Shop", 3, stack(), List.of(new SetScreen("Market", 6)));
        assertThat(result.title()).isEqualTo("Market");
        assertThat(result.rows()).isEqualTo(6);
        var untouched = edits.apply("Shop", 3, stack(), List.of(new SetScreen(null, null)));
        assertThat(untouched.title()).isEqualTo("Shop");
        assertThat(untouched.rows()).isEqualTo(3);
    }

    @Test
    void refusesWhatTheCompilerWouldQuarantine() {
        assertThatThrownBy(() -> edits.apply("Shop", 3, stack(), List.of(new AddLayer(0, "hologram", null, Map.of()))))
                .hasMessageContaining("unknown component 'hologram'");
        assertThatThrownBy(() -> edits.apply("Shop", 3, stack(), List.of(new AddLayer(0, "panel", "Bad Id", Map.of()))))
                .hasMessageContaining("must match [a-z0-9_]+");
        assertThatThrownBy(() -> edits.apply("Shop", 3, stack(), List.of(new AddLayer(0, "panel", "frame", Map.of()))))
                .hasMessageContaining("already used");
        assertThatThrownBy(() -> edits.apply("Shop", 3, stack(), List.of(new AddLayer(9, "panel", null, Map.of()))))
                .hasMessageContaining("outside 0..3");
        assertThatThrownBy(() -> edits.apply("Shop", 3, stack(), List.of(new PatchLayer("frame", Map.of("id", "x"), List.of()))))
                .hasMessageContaining("cannot change");
        assertThatThrownBy(() -> edits.apply("Shop", 3, stack(), List.of(new PatchLayer("frame", Map.of("type", "nope"), List.of()))))
                .hasMessageContaining("unknown component 'nope'");
        assertThatThrownBy(() -> edits.apply("Shop", 3, stack(), List.of(new PatchLayer("ghost", Map.of(), List.of()))))
                .hasMessageContaining("no layer 'ghost'");
        assertThatThrownBy(() -> edits.apply("Shop", 3, stack(), List.of(new ReorderLayers(List.of("frame", "wells")))))
                .hasMessageContaining("names every layer once");
        assertThatThrownBy(() -> edits.apply("Shop", 3, stack(), List.of(new ReorderLayers(List.of("frame", "frame", "wells")))))
                .hasMessageContaining("named twice");
        assertThatThrownBy(() -> edits.apply("Shop", 3, stack(), List.of(new SetScreen(null, 7))))
                .hasMessageContaining("rows must be from 1 to 6");
        assertThatThrownBy(() -> edits.apply("Shop", 3, stack(), List.of(new SetScreen(" ", null))))
                .hasMessageContaining("must not be blank");
    }

    @Test
    void anEditRefusesAGuiWhoseTwoLayersShareAnId() {
        var doubled = List.of(layer("frame", "panel"), layer("frame", "panel"));
        assertThatThrownBy(() -> edits.apply("Shop", 3, doubled, List.of(new RemoveLayers(List.of("frame")))))
                .hasMessageContaining("two layers are named 'frame'");
    }

    @Test
    void theInputIsNeverModified() {
        var before = stack();
        edits.apply("Shop", 3, before, List.of(new RemoveLayers(List.of("frame")), new PatchLayer("wells", Map.of("x", 1), List.of())));
        assertThat(ids(before)).containsExactly("frame", "wells", "sword");
        assertThat(before.get(1)).doesNotContainKey("x");
    }
}
