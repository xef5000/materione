package dev.materione.plugin.gui;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.behavior.logic.LogicLookup;
import dev.materione.content.gui.CompiledGui;
import dev.materione.content.gui.logic.ClickMap;
import dev.materione.core.runtime.RuntimeItem;
import dev.materione.plugin.gui.rules.GuiRuleSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.bukkit.Material;

/**
 * The live GUI view of one apply (gui.md §5): every compiled GUI with its slot table resolved
 * against the same shadow's items. It is a shadow-swap view (runtime-systems §1): built by the
 * shadow builder, published with the other registries in one reference swap, read lock-free
 * through {@link #KEY}, and never mutated.
 *
 * <p>Resolution happens here, at apply time, so opening a GUI is a walk over its slots. A
 * Materione item becomes its {@link RuntimeItem}; a {@code minecraft:} id becomes its material.
 * A reference that is neither is reported as {@code MTR-0054} and its slot is left empty: the
 * rest of the GUI still shows, and nothing is guessed.
 *
 * @param guis the live GUIs, in compile order
 */
public final class RuntimeGuis {

    /** The view key under {@link dev.materione.core.runtime.RuntimeRegistries#view(Class)}. */
    public static final Class<RuntimeGuis> KEY = RuntimeGuis.class;

    private static final RuntimeGuis EMPTY = new RuntimeGuis(Map.of());

    private final Map<NamespacedId, Live> guis;

    private RuntimeGuis(Map<NamespacedId, Live> guis) {
        this.guis = Collections.unmodifiableMap(new LinkedHashMap<>(guis));
    }

    /** The view of a shadow with no GUIs (boot state, or no {@code gui} content type). */
    public static RuntimeGuis empty() {
        return EMPTY;
    }

    /**
     * Builds the view from the compiled models of one apply.
     *
     * @param compiled the compiled GUIs of this apply, keyed by GUI id, in compile order
     * @param items the apply's items by id (Materione items, placement items included)
     * @param vanilla the vanilla material a {@code minecraft:} reference names, if any
     * @param diagnostics receives one {@code MTR-0054} per unresolved slot
     */
    public static RuntimeGuis build(
            Map<NamespacedId, CompiledGui> compiled,
            Function<NamespacedId, Optional<RuntimeItem>> items,
            Function<NamespacedId, Optional<Material>> vanilla,
            Consumer<Diagnostic> diagnostics) {
        return build(compiled, items, vanilla, diagnostics, null);
    }

    /**
     * As above, and compiles each GUI's {@code behavior:} and {@code flows:} against {@code logic}
     * (logic.md §6). The rule problems are not published here: the diagnose stage of the pack compile
     * already reported them, with the same compiler, and republishing would repeat every one.
     *
     * @param logic the registries rules resolve against; null builds a view with no rules
     */
    public static RuntimeGuis build(
            Map<NamespacedId, CompiledGui> compiled,
            Function<NamespacedId, Optional<RuntimeItem>> items,
            Function<NamespacedId, Optional<Material>> vanilla,
            Consumer<Diagnostic> diagnostics,
            LogicLookup logic) {
        var out = new LinkedHashMap<NamespacedId, Live>();
        for (CompiledGui gui : compiled.values()) {
            var slots = new ArrayList<Slot>();
            for (CompiledGui.Slot entry : gui.slots()) {
                Item item = resolve(entry.item(), items, vanilla);
                if (item == null) {
                    diagnostics.accept(unresolved(gui, entry));
                } else {
                    slots.add(new Slot(entry.slot(), item, entry.amount()));
                }
            }
            GuiRuleSet rules = logic == null
                    ? GuiRuleSet.EMPTY
                    : GuiRuleSet.compile(gui.id(), gui.rules(), logic).rules();
            out.put(gui.id(), new Live(
                    gui.id(), gui.rows(), gui.title(), gui.accessibleTitle(), slots, gui.clicks(), rules));
        }
        return new RuntimeGuis(out);
    }

    /** The material a vanilla {@code minecraft:} reference names; needs a running server. */
    public static Optional<Material> vanillaMaterial(NamespacedId id) {
        return Optional.ofNullable(Material.matchMaterial(id.toString())).filter(Material::isItem);
    }

    /** The live GUI with this id, if it is live in this apply. */
    public Optional<Live> gui(NamespacedId id) {
        return Optional.ofNullable(guis.get(id));
    }

    /** Every live GUI, in compile order. */
    public Collection<Live> all() {
        return guis.values();
    }

    public boolean isEmpty() {
        return guis.isEmpty();
    }

    private static Item resolve(
            NamespacedId reference,
            Function<NamespacedId, Optional<RuntimeItem>> items,
            Function<NamespacedId, Optional<Material>> vanilla) {
        Optional<RuntimeItem> live = items.apply(reference);
        if (live.isPresent()) {
            return new Item.Materione(live.get());
        }
        if (reference.isReservedNamespace()) {
            Optional<Material> material = vanilla.apply(reference);
            if (material.isPresent()) {
                return new Item.Vanilla(material.get());
            }
        }
        return null;
    }

    private static Diagnostic unresolved(CompiledGui gui, CompiledGui.Slot entry) {
        return new Diagnostic(
                DiagnosticCodes.GUI_SLOT_ITEM_UNRESOLVED, Severity.WARN,
                "GUI " + gui.id() + " slot " + entry.slot() + " names '" + entry.item()
                        + "', which is not a live item; the slot stays empty",
                gui.id(), null,
                "check the item id (/materione browser lists the live items) or the pack that defines it");
    }

    /**
     * One live GUI: what a container screen needs to open it.
     *
     * @param id the GUI resource
     * @param rows chest rows, 1 to 6
     * @param title the MiniMessage title with the background glyph and text runs, in draw order
     * @param accessibleTitle the plain title, for listings
     * @param slots the resolved slots, sorted by slot
     * @param clicks which layers a click on each container slot reaches, topmost first
     * @param logic the GUI's compiled rules, indexed by trigger
     */
    public record Live(
            NamespacedId id, int rows, String title, String accessibleTitle, List<Slot> slots,
            ClickMap clicks, GuiRuleSet logic) {

        public Live {
            slots = List.copyOf(slots);
            clicks = clicks == null ? ClickMap.EMPTY : clicks;
            logic = logic == null ? GuiRuleSet.EMPTY : logic;
        }

        /** A GUI with no clickable layers and no rules. */
        public Live(NamespacedId id, int rows, String title, String accessibleTitle, List<Slot> slots) {
            this(id, rows, title, accessibleTitle, slots, ClickMap.EMPTY, GuiRuleSet.EMPTY);
        }

        /**
         * Whether a viewer of {@code previous} would see the same window: same rows, same title,
         * and the same item in every slot. Items compare by identity and definition hash, never
         * by object, so a rebuilt item with an unchanged definition is the same item.
         */
        public boolean sameAs(Live previous) {
            if (rows != previous.rows || !title.equals(previous.title) || slots.size() != previous.slots.size()) {
                return false;
            }
            for (int index = 0; index < slots.size(); index++) {
                Slot now = slots.get(index);
                Slot before = previous.slots.get(index);
                if (now.slot() != before.slot() || now.amount() != before.amount()
                        || !now.item().sameAs(before.item())) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * One filled slot: the chest slot, the item it shows and how many.
     *
     * @param slot the chest slot, 0 at the top-left, row by row
     * @param item the resolved item
     * @param amount how many, 1 to 64
     */
    public record Slot(int slot, Item item, int amount) {

        public Slot {
            Objects.requireNonNull(item, "item");
        }
    }

    /** What a slot shows: a Materione item or a vanilla material. */
    public sealed interface Item permits Item.Materione, Item.Vanilla {

        /** Whether two resolved items are the same appearance (see {@link Live#sameAs}). */
        boolean sameAs(Item other);

        /** A Materione item, built through the item factory when the window opens. */
        record Materione(RuntimeItem item) implements Item {

            @Override
            public boolean sameAs(Item other) {
                return other instanceof Materione that
                        && item.id().equals(that.item.id())
                        && item.definitionHash().equals(that.item.definitionHash());
            }
        }

        /** A vanilla material, shown as a plain stack. */
        record Vanilla(Material material) implements Item {

            @Override
            public boolean sameAs(Item other) {
                return other instanceof Vanilla that && material == that.material;
            }
        }
    }
}
