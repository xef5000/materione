package dev.materione.plugin.gui.render;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.ItemResolution;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.TemplateText;
import dev.materione.behavior.logic.OverlayLogicContext;
import dev.materione.content.gui.ItemComponent;
import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.plugin.gui.dynamic.CompiledItemLayer;
import dev.materione.plugin.gui.dynamic.ItemBinding;
import dev.materione.plugin.gui.list.CompiledListLayer;
import dev.materione.plugin.gui.list.ListBinding;
import dev.materione.plugin.gui.list.ListEntries;
import dev.materione.plugin.gui.list.ListPage;
import dev.materione.plugin.gui.list.ListPager;
import dev.materione.plugin.gui.render.Rendered.ListRender;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Decides what every slot of a window shows for one viewer (logic.md §6.3, §6.7). Pure: it reads the compiled GUI
 * and the viewer's logic context, and it returns a {@link Rendered}. The Bukkit write is {@link RenderDiff}'s.
 *
 * <p>The rules, in order:
 *
 * <ol>
 *   <li>A static slot shows its apply-time item, always.</li>
 *   <li>A layer whose {@code visible_if} is false is hidden; it is recorded in {@link Rendered#hidden()}
 *       whether or not it would have won its slot.</li>
 *   <li>Within a slot the topmost visible layer wins, item and list layers alike. A layer that wins resolves its
 *       source and overrides; if that resolves to nothing, the slot is empty (a higher visible layer is not
 *       replaced by one below). A list owns every slot it fills: a slot past the page's last entry is empty.</li>
 * </ol>
 *
 * <p>A partial render ({@code previous} given) re-reads only the dynamic layers and reuses the resolved
 * appearance of the others, so a 20-tick refresh does not rebuild the static items. A list is always dynamic, but a
 * {@code cached} source keeps the entries it read when the window opened and reads them again only on a full render.
 * A full render ({@code previous} null) re-reads everything, which is what a reload or {@code gui.refresh} needs. The
 * renderer keeps no state of its own: one instance serves every window, on any region thread.
 */
public final class SlotRenderer {

    private final RenderReporter reporter;

    /** @param reporter where a worked-around problem goes; {@link RenderReporter#SILENT} drops it */
    public SlotRenderer(RenderReporter reporter) {
        this.reporter = reporter;
    }

    /**
     * Renders {@code gui} for the viewer in {@code context}.
     *
     * @param ticks the window's clock, which chooses an animation's frame
     * @param previous the last render of this window, or null for a full render
     */
    public Rendered render(RuntimeGuis.Live gui, LogicContext context, long ticks, Rendered previous) {
        var pass = new Pass(gui.id(), context, ticks);
        Map<Integer, Appearance> slots = new HashMap<>();
        for (RuntimeGuis.Slot slot : gui.slots()) {
            slots.put(slot.slot(), Appearance.of(sourceOf(slot.item()), slot.amount()));
        }
        var lists = new ListFrame(previous);
        Set<String> hidden = new HashSet<>();
        Map<String, Optional<Appearance>> resolved = new HashMap<>();
        Set<Integer> decided = new HashSet<>();
        List<CompiledItemLayer> items = gui.items();
        List<CompiledListLayer> listLayers = gui.lists();
        int item = items.size() - 1;
        int list = listLayers.size() - 1;
        // topmost first, items and lists in layer order: the first visible layer of a slot decides it
        while (item >= 0 || list >= 0) {
            if (list < 0 || (item >= 0 && items.get(item).order() > listLayers.get(list).order())) {
                CompiledItemLayer layer = items.get(item--);
                if (!visible(pass, layer)) {
                    hidden.add(layer.layerId());
                    continue;
                }
                if (!decided.add(layer.slot())) {
                    continue;
                }
                Optional<Appearance> appearance = reusable(layer, previous);
                if (appearance == null) {
                    appearance = resolve(pass, layer);
                }
                resolved.put(layer.layerId(), appearance);
                appearance.ifPresent(shown -> slots.put(layer.slot(), shown));
            } else {
                decideList(pass, listLayers.get(list--), decided, slots, lists);
            }
        }
        return new Rendered(slots, hidden, resolved, null, Map.of(), lists.render());
    }

    /**
     * Decides the slots of one list layer: the page's entries go in order into the list's slots, the slots past the
     * page are emptied, and a slot a higher layer decided is left to it (logic.md §6.7).
     */
    private void decideList(
            Pass pass, CompiledListLayer layer, Set<Integer> decided, Map<Integer, Appearance> slots, ListFrame frame) {
        List<Map<String, Object>> rows = frame.rowsOf(pass, layer, this);
        List<Map<String, Object>> arranged = ListEntries.arrange(layer, pass.context(), rows, reporter, pass.gui());
        int size = layer.slots().size();
        int count = ListPager.countOf(arranged.size(), size);
        int number = ListPager.clamp(pageNumber(pass.context(), layer.layerId()), count);
        frame.page(layer.layerId(), new ListPage(number, count));
        List<Map<String, Object>> onPage = ListPager.pageOf(arranged, number, size);
        for (int position = 0; position < size; position++) {
            int slot = layer.slots().get(position);
            if (!decided.add(slot)) {
                continue;
            }
            Optional<Appearance> appearance = position < onPage.size()
                    ? entryAppearance(pass, layer, onPage.get(position))
                    : Optional.empty();
            if (appearance.isPresent()) {
                slots.put(slot, appearance.get());
                frame.show(slot, layer.layerId(), onPage.get(position));
            } else {
                slots.remove(slot);
            }
        }
    }

    /** The item an entry shows: the list's template with {@code entry.*} read from this entry. */
    private Optional<Appearance> entryAppearance(Pass pass, CompiledListLayer layer, Map<String, Object> entry) {
        var overlay = new OverlayLogicContext(pass.context(), "entry", entry);
        return resolve(new Pass(pass.gui(), overlay, pass.ticks()), layer.entry());
    }

    /** The page a list showed last, as the session stores it under {@code gui.page.<list>.number}; first when none. */
    private static int pageNumber(LogicContext context, String list) {
        Object stored = context.resolve(List.of("gui", "page", list, "number"));
        return stored instanceof Number number ? number.intValue() : 1;
    }

    /** The resolved appearance a partial render keeps, or null when the layer must be read again. */
    private static Optional<Appearance> reusable(CompiledItemLayer layer, Rendered previous) {
        if (previous == null || layer.dynamic()) {
            return null;
        }
        return previous.resolved().get(layer.layerId());
    }

    private boolean visible(Pass pass, CompiledItemLayer layer) {
        if (layer.visibleIf() == null) {
            return true;
        }
        try {
            return layer.visibleIf().test(pass.context());
        } catch (RuntimeException failed) {
            // a condition that cannot be read hides its layer, as a condition that is false does
            report(pass, DiagnosticCodes.GUI_DYNAMIC_LAYER_FAILED, layer,
                    "visible_if could not be evaluated, so the layer is hidden: " + messageOf(failed));
            return false;
        }
    }

    private Optional<Appearance> resolve(Pass pass, CompiledItemLayer layer) {
        ItemBinding binding = layer.sourceAt(pass.ticks());
        ItemResolution result;
        try {
            result = binding.resolve(pass.context());
        } catch (RuntimeException failed) {
            result = ItemResolution.unresolved(messageOf(failed));
        }
        if (result.failed()) {
            report(pass, DiagnosticCodes.GUI_ITEM_SOURCE_UNRESOLVED, layer, result.reason());
            return Optional.of(Appearance.of(new SlotSource.Barrier(result.reason()), 1));
        }
        if (result.stack() == null) {
            return Optional.empty();
        }
        Overrides overrides = overridesOf(pass, layer);
        return Optional.of(new Appearance(new SlotSource.Stack(result.stack()), overrides.name(), overrides.lore(),
                layer.loreMode(), overrides.amount(), overrides.glint()));
    }

    /** The templated overrides of a layer, rendered for the pass's viewer. */
    Overrides overridesOf(Pass pass, CompiledItemLayer layer) {
        return new Overrides(nameOf(pass, layer), loreOf(pass, layer), amountOf(pass, layer), glintOf(pass, layer));
    }

    /**
     * A layer's rendered overrides: the name (null to keep the item's own), the lore lines, the amount and the
     * glint override (null to keep the item's own).
     */
    record Overrides(String name, List<String> lore, int amount, Boolean glint) {}

    private String nameOf(Pass pass, CompiledItemLayer layer) {
        return layer.name() == null ? null : render(pass, layer, layer.name(), "name");
    }

    private List<String> loreOf(Pass pass, CompiledItemLayer layer) {
        var lines = new ArrayList<String>(layer.lore().size());
        for (TemplateText line : layer.lore()) {
            String rendered = render(pass, layer, line, "lore");
            if (rendered != null) {
                lines.add(rendered); // a line that fails is dropped and reported; the others still show
            }
        }
        return lines;
    }

    /** The amount, clamped to a chest stack; an amount that does not read as a number is 1, and reported. */
    private int amountOf(Pass pass, CompiledItemLayer layer) {
        String text = render(pass, layer, layer.amount(), "amount");
        try {
            int amount = Integer.parseInt(text == null ? "" : text.trim());
            return Math.max(1, Math.min(amount, ItemComponent.MAX_AMOUNT));
        } catch (NumberFormatException notANumber) {
            if (text != null) {
                report(pass, DiagnosticCodes.GUI_DYNAMIC_LAYER_FAILED, layer,
                        "amount '" + text + "' is not a whole number; showing 1");
            }
            return 1;
        }
    }

    /** The glint override: {@code true} or {@code false} once rendered; anything else keeps the item's own glint. */
    private Boolean glintOf(Pass pass, CompiledItemLayer layer) {
        if (layer.glint() == null) {
            return null;
        }
        String text = render(pass, layer, layer.glint(), "glint");
        return text == null ? null : switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** One template rendered; a template that fails renders empty and is reported, so the layer still shows. */
    private String render(Pass pass, CompiledItemLayer layer, TemplateText template, String field) {
        try {
            return template.render(pass.context());
        } catch (RuntimeException failed) {
            report(pass, DiagnosticCodes.GUI_DYNAMIC_LAYER_FAILED, layer,
                    field + " could not be rendered: " + messageOf(failed));
            return null;
        }
    }

    /** Reports one list source failure: the entries of a list that could not be read (MTR-0079). */
    void reportSource(NamespacedId gui, String layerId, String reason) {
        reporter.problem(DiagnosticCodes.GUI_LIST_SOURCE_FAILED, gui, layerId, reason);
    }

    private void report(Pass pass, dev.materione.api.diagnostics.DiagnosticCode code, CompiledItemLayer layer,
            String message) {
        reporter.problem(code, pass.gui(), layer.layerId(), message);
    }

    private static SlotSource sourceOf(RuntimeGuis.Item item) {
        return switch (item) {
            case RuntimeGuis.Item.Materione materione -> new SlotSource.Materione(materione.item());
            case RuntimeGuis.Item.Vanilla vanilla -> new SlotSource.Vanilla(vanilla.material());
        };
    }

    private static String messageOf(RuntimeException failure) {
        return failure.getMessage() != null ? failure.getMessage() : failure.toString();
    }

    /** One render's inputs, passed down so that no state lives in the renderer. */
    record Pass(NamespacedId gui, LogicContext context, long ticks) {}

    /**
     * The list state of one render: the entries each list reads (a cached list keeps the entries of the previous
     * render), the entry each list slot shows and the page each list shows. Built in layer order, read once.
     */
    private static final class ListFrame {

        private final Rendered previous;
        private final Map<Integer, ListRender.Shown> shown = new HashMap<>();
        private final Map<String, List<Map<String, Object>>> entries = new HashMap<>();
        private final Map<String, ListPage> pages = new HashMap<>();

        ListFrame(Rendered previous) {
            this.previous = previous;
        }

        /**
         * The entries a list reads this render: the previous render's snapshot for a cached list, else the source's
         * entries now. A failed read is reported and reads as no entries.
         */
        List<Map<String, Object>> rowsOf(Pass pass, CompiledListLayer layer, SlotRenderer renderer) {
            List<Map<String, Object>> rows = null;
            if (layer.cached() && previous != null) {
                rows = previous.lists().entries().get(layer.layerId());
            }
            if (rows == null) {
                ListBinding.ListRead read = readOf(layer, pass, renderer);
                rows = read.entries();
            }
            entries.put(layer.layerId(), rows);
            return rows;
        }

        private static ListBinding.ListRead readOf(CompiledListLayer layer, Pass pass, SlotRenderer renderer) {
            ListBinding.ListRead read;
            try {
                read = layer.source().read(pass.context());
            } catch (RuntimeException failed) {
                read = ListBinding.ListRead.failed(messageOf(failed));
            }
            if (read.failed()) {
                renderer.reportSource(pass.gui(), layer.layerId(), read.failure());
            }
            return read;
        }

        void page(String layerId, ListPage page) {
            pages.put(layerId, page);
        }

        void show(int slot, String layerId, Map<String, Object> entry) {
            shown.put(slot, new ListRender.Shown(layerId, entry));
        }

        ListRender render() {
            return new ListRender(shown, entries, pages);
        }
    }
}
