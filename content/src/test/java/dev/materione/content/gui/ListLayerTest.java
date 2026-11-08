package dev.materione.content.gui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.gui.GuiContainer;
import dev.materione.api.gui.TextureSource;
import dev.materione.content.gui.list.ListSort;
import dev.materione.content.gui.list.ListSpec;
import dev.materione.content.gui.list.ListSlots;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The list component as authored (logic.md §6.7): its slots, source, filter, sort and entry template. Each rejection
 * names its reason, as an item layer's does, and an accepted list is a per-viewer placement, never a static slot.
 */
class ListLayerTest {

    private static final GuiContainer CHEST_3 = GuiContainers.byId("chest_3").orElseThrow();

    private static final GuiType TYPE = GuiTestSupport.type();

    /** A list layer's fields, with the entry an item template. */
    private static Map<String, Object> list(String id, Object... keysAndValues) {
        var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put("slots", Map.of("from", 9, "to", 11));
        fields.put("source", "online_players");
        fields.put("entry", Map.of("item", "${entry.name}"));
        for (int index = 0; index < keysAndValues.length; index += 2) {
            fields.put((String) keysAndValues[index], keysAndValues[index + 1]);
        }
        return GuiTestSupport.layer(id, "list", fields.entrySet().stream()
                .flatMap(entry -> java.util.stream.Stream.of(entry.getKey(), entry.getValue())).toArray());
    }

    @SafeVarargs
    private static GuiDefinition gui(Map<String, Object>... layers) {
        var definition = new java.util.LinkedHashMap<String, Object>();
        definition.put("title", "Test");
        definition.put("container", "chest_3");
        var authored = new java.util.ArrayList<Object>();
        for (Map<String, Object> layer : layers) {
            authored.add(layer);
        }
        definition.put("layers", authored);
        return GuiTestSupport.parse(TYPE, definition);
    }

    private static String skipReason(GuiDefinition definition, String layerId) {
        return definition.skipped().stream()
                .filter(skip -> layerId.equals(skip.layerId()))
                .map(GuiDefinition.SkippedLayer::reason)
                .findFirst()
                .orElse(null);
    }

    @Test
    void aListIsAPerViewerPlacementThatClaimsItsSlotsAndNoStaticSlot() {
        var definition = gui(list("players"));
        var compiled = GuiCompiler.compile(definition, 0xE200, TextureSource.NONE).model();

        assertThat(skipReason(definition, "players")).isNull();
        assertThat(compiled.slots()).isEmpty();
        assertThat(compiled.placements()).isEmpty();
        assertThat(compiled.lists()).hasSize(1);
        var placement = compiled.lists().get(0);
        assertThat(placement.layerId()).isEqualTo("players");
        assertThat(placement.list().slots()).containsExactly(9, 10, 11);
        assertThat(placement.list().source().source()).isEqualTo(NamespacedId.parse("materione:online_players"));
        assertThat(placement.list().clickArea(TextureSource.NONE))
                .isEqualTo(new dev.materione.api.gui.ClickArea.Slots(java.util.Set.of(9, 10, 11)));
    }

    @Test
    void theClickMapAnswersEverySlotOfTheListWithTheList() {
        var compiled = GuiCompiler.compile(gui(list("players")), 0xE200, TextureSource.NONE).model();

        assertThat(compiled.clicks().layersAt(10)).containsExactly("players");
        assertThat(compiled.clicks().layersAt(12)).isEmpty();
    }

    @Test
    void slotsAcceptARangeWithSkippedColumnsOrAnExplicitListInEntryOrder() {
        assertThat(ListSlots.parse(Map.of("from", 9, "to", 26, "skip_columns", List.of(0, 8)), CHEST_3))
                .containsExactly(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25);
        assertThat(ListSlots.parse(List.of(22, 4, 13), CHEST_3)).containsExactly(22, 4, 13);
    }

    @Test
    void slotsRejectANumberOutsideTheGuiRepeatedSlotsAndAnEmptyRange() {
        assertThatRejects(() -> ListSlots.parse(List.of(27), CHEST_3), "slot must be a slot from 0 to 26");
        assertThatRejects(() -> ListSlots.parse(List.of(4, 4), CHEST_3), "slot 4 is listed twice");
        assertThatRejects(() -> ListSlots.parse(Map.of("from", 12, "to", 9), CHEST_3), "to must be at or after from");
        assertThatRejects(() -> ListSlots.parse(Map.of("from", 0, "to", 8, "skip_columns", List.of(0, 1, 2, 3,
                4, 5, 6, 7, 8)), CHEST_3), "slots names no slot of the GUI");
        assertThatRejects(() -> ListSlots.parse("9-11", CHEST_3), "slots is a range");
    }

    @Test
    void aListWhoseSourceIsUnnamedOrMalformedIsRejectedWithItsReason() {
        var definition = gui(list("bad", "source", Map.of("filter", "x")));
        assertThat(skipReason(definition, "bad")).contains("source names a list source");

        assertThat(ListSpec.parse("online_players").source()).isEqualTo(NamespacedId.parse("materione:online_players"));
        assertThat(ListSpec.parse(Map.of("type", "example:weather", "x", 1)).source())
                .isEqualTo(NamespacedId.parse("example:weather"));
        assertThat(ListSpec.parse(Map.of("entries", List.of(Map.of("name", "Ada")))).source())
                .isEqualTo(ListSpec.STATIC);
        assertThatRejects(() -> ListSpec.parse(42), "source names a list source");
    }

    @Test
    void aListRejectsAFieldItDoesNotHave() {
        var definition = gui(list("players", "item", "shop:sword"));
        assertThat(skipReason(definition, "players")).contains("takes slots, source, filter, sort and entry");
    }

    @Test
    void aListRejectsAnEntryThatNamesASlot() {
        var definition = gui(list("players", "entry", Map.of("item", "${entry.name}", "slot", 2)));
        assertThat(skipReason(definition, "players")).contains("an entry takes no slot");
    }

    @Test
    void theEntryIsAnItemTemplateWithItsOverridesChecked() {
        var definition = gui(list("players", "entry", Map.of("item", "${entry.name}", "amount", 99)));
        assertThat(skipReason(definition, "players")).contains("amount must be a whole number from 1 to 64");

        var accepted = GuiCompiler.compile(gui(list("players", "entry", Map.of(
                "item", "${entry.name}", "name", "<yellow>${entry.name}", "lore", List.of("<gray>Click"),
                "lore_mode", "append"))), 0xE200, TextureSource.NONE).model();
        var entry = accepted.lists().get(0).list().entry();
        assertThat(entry.name()).isEqualTo("<yellow>${entry.name}");
        assertThat(entry.lore()).containsExactly("<gray>Click");
        assertThat(entry.dynamic()).isTrue();
    }

    @Test
    void aFilterIsAConditionAndASortIsAFieldOrAFieldAndAnOrder() {
        assertThat(ListSort.parse("name")).isEqualTo(new ListSort("name", false));
        assertThat(ListSort.parse(Map.of("by", "level", "order", "desc"))).isEqualTo(new ListSort("level", true));
        assertThatRejects(() -> ListSort.parse(Map.of("by", "level", "order", "up")), "sort order is asc or desc");
        assertThatRejects(() -> ListSort.parse("Level Name"), "is not an entry field name");
        assertThatRejects(() -> ListSort.parse(Map.of("name", "level")), "sort needs by");

        var definition = gui(list("players", "filter", 42));
        assertThat(skipReason(definition, "players")).contains("filter is a condition");
        var accepted = GuiCompiler.compile(gui(list("players", "filter", "${entry.level > 1}",
                "sort", Map.of("by", "name", "order", "desc"))), 0xE200, TextureSource.NONE).model();
        assertThat(accepted.lists().get(0).list().filter()).isEqualTo("${entry.level > 1}");
        assertThat(accepted.lists().get(0).list().sort()).isEqualTo(new ListSort("name", true));
    }

    @Test
    void aListOverAGuiWithoutRoomForItsSlotsIsRejected() {
        var definition = gui(list("players", "slots", List.of(30)));
        assertThat(skipReason(definition, "players")).contains("must be a slot from 0 to 26");
    }

    private static void assertThatRejects(Runnable action, String reason) {
        try {
            action.run();
        } catch (IllegalArgumentException rejected) {
            assertThat(rejected.getMessage()).contains(reason);
            return;
        }
        throw new AssertionError("expected a rejection containing: " + reason);
    }
}
