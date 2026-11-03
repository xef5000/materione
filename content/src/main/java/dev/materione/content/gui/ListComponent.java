package dev.materione.content.gui;

import dev.materione.api.gui.ClickArea;
import dev.materione.api.gui.CompiledGuiLayer;
import dev.materione.api.gui.GuiComponent;
import dev.materione.api.gui.GuiComponentConfiguration;
import dev.materione.api.gui.RenderTarget;
import dev.materione.api.gui.TextureSource;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.content.gui.list.ListSlots;
import dev.materione.content.gui.list.ListSort;
import dev.materione.content.gui.list.ListSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code list}: a page of entries in a run of container slots (logic.md §6.7). The entries come from a list source
 * ({@link ListSpec}), are filtered by {@code filter} and ordered by {@code sort}, and fill the slots page by page;
 * {@code entry} is the item each entry shows, an ordinary item layer's fields with {@code entry.*} in scope. Slot
 * target, but it places nothing statically: entries depend on the viewer and the page, so the window resolves a
 * list per render, and the GUI compiler routes the layer to that path (logic.md §6.7).
 *
 * <p>The list's slots are clickable. A click on a slot that shows an entry runs the {@code gui.click} rules of the
 * list with that entry's fields as {@code entry.*}; a click on an empty slot runs none.
 */
public final class ListComponent implements GuiComponent {

    private static final Set<String> FIELDS = Set.of("slots", "source", "filter", "sort", "entry");
    private static final List<String> REQUIRED = List.of("slots", "source", "entry");

    @Override
    public String name() {
        return "list";
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription(
                "List",
                "A page of entries (online players, a pack's items, a variable's values, or entries written here) "
                        + "in a run of slots, filtered, sorted and paged, each shown by an item template",
                List.of(
                        new ParameterSchema("slots", ParameterType.RAW, true, null,
                                "The slots the entries fill, in entry order: { from, to, skip_columns } or a list "
                                        + "of slot numbers", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Slots", "placement", 10, false, "text", "", "{from: 10, to: 34}")),
                        new ParameterSchema("source", ParameterType.RAW, true, null,
                                "The list source: a name (online_players), or a mapping with type and the source's "
                                        + "parameters ({type: pack_items, pack: shop}), or {entries: [...]}", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Source", "entries", 20, false, "text", "", "online_players")),
                        new ParameterSchema("filter", ParameterType.RAW, false, null,
                                "Which entries show: a condition over entry.* (an expression, a predicate or a list)",
                                null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Filter", "entries", 30, true, "text", "", "${contains(lower(entry.name), 'a')}")),
                        new ParameterSchema("sort", ParameterType.RAW, false, null,
                                "The order of the entries: a field name, or {by: <field>, order: asc|desc}", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Sort", "entries", 40, true, "text", "", "name")),
                        new ParameterSchema("entry", ParameterType.RAW, true, null,
                                "The item each entry shows: the fields of an item layer (item, name, lore, lore_mode, "
                                        + "amount, glint), with entry.* in scope", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Entry", "appearance", 50, false, "text", "", "item: head"))),
                "1.0.0");
    }

    @Override
    public RenderTarget target() {
        return RenderTarget.SLOT;
    }

    @Override
    public CompiledGuiLayer parse(GuiComponentConfiguration configuration) {
        Map<String, Object> raw = configuration.parameters();
        for (String key : raw.keySet()) {
            if (!FIELDS.contains(key)) {
                throw fail(configuration, "takes slots, source, filter, sort and entry, not '" + key + "'");
            }
        }
        for (String key : REQUIRED) {
            if (!raw.containsKey(key)) {
                throw fail(configuration, key + " is required");
            }
        }
        List<Integer> slots;
        try {
            slots = ListSlots.parse(raw.get("slots"), configuration.rows());
        } catch (IllegalArgumentException rejected) {
            throw fail(configuration, rejected.getMessage());
        }
        ListSpec source;
        try {
            source = ListSpec.parse(raw.get("source"));
        } catch (IllegalArgumentException rejected) {
            throw fail(configuration, rejected.getMessage());
        }
        ListSort sort = null;
        if (raw.get("sort") != null) {
            try {
                sort = ListSort.parse(raw.get("sort"));
            } catch (IllegalArgumentException rejected) {
                throw fail(configuration, rejected.getMessage());
            }
        }
        Object filter = filterOf(configuration, raw.get("filter"));
        ItemComponent.Layer entry = entryOf(configuration, raw.get("entry"));
        return new Layer(slots, source, filter, sort, entry);
    }

    /** The filter as authored: a condition, shaped as a {@code visible_if} is. The engine compiles it. */
    private static Object filterOf(GuiComponentConfiguration configuration, Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof String || raw instanceof Boolean || raw instanceof List<?> || raw instanceof Map<?, ?>) {
            return raw;
        }
        throw fail(configuration, "filter is a condition: an expression, a predicate or a list");
    }

    /**
     * The entry template: an item layer's own fields, parsed by {@link ItemComponent} so that the item source and
     * the overrides mean exactly what they mean on an item layer. The slot is a placeholder the entries replace.
     */
    private static ItemComponent.Layer entryOf(GuiComponentConfiguration configuration, Object raw) {
        if (!(raw instanceof Map<?, ?> mapping)) {
            throw fail(configuration, "entry is a mapping of the item each entry shows (item, name, lore, amount)");
        }
        var params = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> field : mapping.entrySet()) {
            if (!(field.getKey() instanceof String key)) {
                throw fail(configuration, "entry field names are text");
            }
            if ("slot".equals(key)) {
                throw fail(configuration, "an entry takes no slot: the list's slots place its entries");
            }
            params.put(key, field.getValue());
        }
        params.put("slot", 0);
        try {
            return (ItemComponent.Layer) new ItemComponent().parse(new GuiComponentConfiguration(
                    configuration.guiId(), configuration.layerId(), configuration.rows(), params));
        } catch (IllegalArgumentException rejected) {
            throw fail(configuration, "entry: " + rejected.getMessage());
        }
    }

    private static IllegalArgumentException fail(GuiComponentConfiguration configuration, String message) {
        return new IllegalArgumentException("layer " + configuration.layerId() + ": " + message);
    }

    /**
     * One parsed list layer.
     *
     * @param slots the slots the entries fill, in entry order, never empty
     * @param source the list source as authored
     * @param filter the authored filter condition, or null when every entry shows
     * @param sort the order of the entries, or null for the source's own order
     * @param entry the item template every entry is shown with (its slot is a placeholder)
     */
    public record Layer(
            List<Integer> slots, ListSpec source, Object filter, ListSort sort, ItemComponent.Layer entry)
            implements CompiledGuiLayer {

        public Layer {
            slots = List.copyOf(slots);
        }

        @Override
        public RenderTarget target() {
            return RenderTarget.SLOT;
        }

        /** Every slot of the list is clickable; a click there runs the list's rules with the entry it shows. */
        @Override
        public ClickArea clickArea(TextureSource textures) {
            return new ClickArea.Slots(Set.copyOf(slots));
        }

        /** A list places nothing statically: its entries are resolved per viewer when the window renders. */
        @Override
        public void placeSlots(dev.materione.api.gui.SlotSink sink) {
            // deliberately empty (logic.md §6.7)
        }
    }
}
