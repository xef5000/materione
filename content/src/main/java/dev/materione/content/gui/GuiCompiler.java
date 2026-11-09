package dev.materione.content.gui;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.DiagnosticTarget;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.gui.CompiledGuiLayer;
import dev.materione.api.gui.RenderTarget;
import dev.materione.api.gui.SlotSink;
import dev.materione.api.gui.TextureSource;
import dev.materione.api.gui.TitleSink;
import dev.materione.content.gui.logic.ClickMap;
import dev.materione.content.gui.logic.ClickMapCompiler;
import dev.materione.content.gui.raster.ArgbCanvas;
import dev.materione.content.gui.raster.PngEncoder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * The pure GUI compiler (gui.md §4): a parsed {@link GuiDefinition} becomes a
 * {@link GuiCompilation}. It touches no server and no filesystem: pixels come through a
 * {@link TextureSource}, and the background codepoint is handed in, so the pack compiler
 * and the runtime call the same function and cannot disagree (D6, D7).
 *
 * <p>Steps, in order: flatten the visible raster layers bottom to top onto one canvas;
 * encode it; collect text runs and slot placements from their components; assemble the
 * title around the background glyph; emit the offset fonts actually used; build the slot
 * table. A layer that fails any step is skipped with a {@code MTR-0052} diagnostic and the
 * GUI still compiles. Every skip is also recorded per layer ({@link GuiCompilation#layers()}),
 * so the studio and the API report the same outcome the pack ships.
 */
public final class GuiCompiler {

    /** Characters a text run may not carry: MiniMessage's tag opener and escape, and legacy codes. */
    private static final String RESERVED_TEXT = "<\\&§";

    private GuiCompiler() {}

    /**
     * Compiles one GUI whose static background is at most one tile, with a fixed codepoint for it. A
     * background of several tiles (art past 256 px, or two separated pieces) needs {@link GuiCodepoints}.
     *
     * @param gui the parsed GUI
     * @param backgroundCodepoint the codepoint of the background's first tile ({@link GuiBackgrounds})
     * @param textures pixel access to the owning pack's textures
     */
    public static GuiCompilation compile(GuiDefinition gui, int backgroundCodepoint, TextureSource textures) {
        return compile(gui, backgroundCodepoint, textures, (layer, frame) -> {
            throw new IllegalStateException("overlay allocations are required for dynamic GUI visuals");
        });
    }

    /** As {@link #compile(GuiDefinition, int, TextureSource)}, with the codepoint of each overlay frame's first tile. */
    public static GuiCompilation compile(GuiDefinition gui, int backgroundCodepoint, TextureSource textures,
            BiFunction<GuiLayer, Integer, Integer> overlayCodepoint) {
        return compile(gui, textures, (layer, frame, row, col) -> {
            if (row != 0 || col != 0) {
                throw new IllegalStateException("art tile " + row + "/" + col + " of "
                        + (layer == null ? "the background" : "layer '" + layer + "'")
                        + " needs its own codepoint: compile with GuiCodepoints");
            }
            if (layer == null) {
                return backgroundCodepoint;
            }
            GuiLayer owner = gui.layers().stream().filter(candidate -> candidate.id().equals(layer))
                    .findFirst().orElseThrow();
            return overlayCodepoint.apply(owner, frame);
        });
    }

    /**
     * Compiles one GUI (gui.md §4, §11). {@code codepoints} is asked for each art tile that is emitted and
     * for no other, so a GUI that paints no pixel allocates nothing.
     *
     * @param gui the parsed GUI
     * @param textures pixel access to the owning pack's textures
     * @param codepoints the codepoint of each art tile
     * @throws dev.materione.api.content.CompileRejectException {@code MTR-0091} when a tile breaks a
     *     font-provider invariant; nothing of this GUI may then be emitted
     */
    public static GuiCompilation compile(GuiDefinition gui, TextureSource textures, GuiCodepoints codepoints) {
        var report = new Report();
        String namespace = gui.id().namespace();
        TextureSource bound = reference -> textures.texture(
                reference.contains(":") ? reference : namespace + ":" + reference);

        boolean dynamicVisuals = GuiVisualCompiler.needed(gui);
        // the open canvas (gui.md §11): the raster is flattened over the art cap and cut to its painted bounds
        GuiArt.Cap cap = GuiArt.Cap.of(gui.container());
        ArgbCanvas background = dynamicVisuals ? null : GuiFlatten.flatten(cap.x(), cap.y(), GuiArt.CAP_SIZE,
                GuiArt.CAP_SIZE, gui.layers(), bound, (layer, reason) -> report.skip(gui, layer.id(), reason),
                layer -> report.clipped(gui, layer.id()));
        List<GuiArt.Tile> tiles = background == null ? List.of() : GuiArt.cut(background, gui.container());

        var runs = new ArrayList<TextRun>();
        var slots = new LinkedHashMap<Integer, SlotPlacement>();
        var items = new ArrayList<CompiledGui.Placement>();
        var lists = new ArrayList<CompiledGui.ListPlacement>();
        for (int index = 0; index < gui.layers().size(); index++) {
            GuiLayer layer = gui.layers().get(index);
            if (!layer.visible()) {
                continue;
            }
            if (layer.compiled().target() == RenderTarget.TEXT && !dynamicVisuals) {
                collectText(gui, layer.id(), layer.compiled(), runs, report);
            } else if (layer.compiled() instanceof ItemComponent.Layer item) {
                items.add(new CompiledGui.Placement(layer.id(), index, item.slot(), layer.visibleIf(), item));
            } else if (layer.compiled() instanceof ListComponent.Layer list) {
                lists.add(new CompiledGui.ListPlacement(layer.id(), index, list));
            } else if (layer.compiled().target() == RenderTarget.SLOT) {
                collectSlots(gui, layer.id(), layer.compiled(), slots, report);
            }
        }
        List<CompiledGui.Placement> placements = placeItems(gui, items, slots, report);

        // one provider per tile in the shared GUI font, one PNG per tile (gui.md §11.3)
        var fonts = new ArrayList<GuiCompilation.FontProvider>();
        var assets = new TreeMap<String, byte[]>();
        var art = new ArrayList<GuiTitle.ArtGlyph>();
        for (GuiArt.Tile tile : tiles) {
            int codepoint = codepoints.codepoint(null, 0, tile.row(), tile.col());
            String texture = GuiOverlayKeys.backgroundTexture(gui.id(), tile.row(), tile.col());
            assets.put(GuiFonts.textureAsset(namespace, texture),
                    PngEncoder.encodeRgba(tile.width(), tile.cell(), tile.argb()));
            fonts.add(new GuiCompilation.FontProvider(GuiFonts.background(namespace),
                    GuiFonts.tileProvider(namespace, texture, tile, codepoint)));
            art.add(new GuiTitle.ArtGlyph(codepoint, tile.x(), tile.width()));
        }
        var title = new GuiTitle(namespace, gui.container());
        title.art(art);
        var textFonts = new LinkedHashMap<String, List<Map<String, Object>>>();
        boolean smallcapsUsed = false;
        for (TextRun run : runs) {
            title.place(run.x(), run.y(), run.text(), run.font(), run.argb());
            String key = GuiFonts.text(namespace, run.font(), run.y());
            textFonts.computeIfAbsent(key, ignored -> run.font() == TitleSink.Font.SMALLCAPS
                    ? GuiFonts.smallcaps(namespace, gui.container(), run.y())
                    : GuiFonts.defaults(gui.container(), run.y()));
            smallcapsUsed |= run.font() == TitleSink.Font.SMALLCAPS;
        }

        textFonts.forEach((key, providers) -> providers.forEach(
                provider -> fonts.add(new GuiCompilation.FontProvider(key, provider))));

        if (smallcapsUsed) {
            assets.put(GuiFonts.smallcapsAsset(namespace), SmallcapsFont.sheetPng());
        }
        GuiVisualPlan visuals = dynamicVisuals
                ? GuiVisualCompiler.compile(gui, bound, codepoints, assets, fonts,
                        (layer, reason) -> report.skip(gui, layer.id(), reason),
                        layer -> report.clipped(gui, layer.id()))
                : null;

        var slotList = new ArrayList<CompiledGui.Slot>();
        slots.forEach((slot, placement) -> slotList.add(new CompiledGui.Slot(slot, placement.item(), placement.amount())));
        slotList.sort(Comparator.comparingInt(CompiledGui.Slot::slot));
        ClickMap clicks = ClickMapCompiler.compile(gui, bound, report.reasons.keySet());
        var model = new CompiledGui(gui.id(), gui.container(), title.build(), gui.title(), slotList, clicks, gui.rules(),
                PlayerInventory.of(gui.raw().get("player_inventory")), inputsOf(gui, clicks),
                placements, gui.refresh(), visuals, lists);
        return new GuiCompilation(model, report.diagnostics, assets, fonts, GuiFlatten.layerReports(gui, report.reasons));
    }

    /**
     * Potential input slots: retain a well under layers whose visibility or footprint can change.
     * A permanently covering layer still blocks it. The window selects effective inputs per render.
     */
    static List<CompiledGui.Input> inputsOf(GuiDefinition gui, ClickMap clicks) {
        var accepts = new LinkedHashMap<String, Object>();
        var variable = new java.util.HashSet<String>();
        for (GuiLayer layer : gui.layers()) {
            if (layer.visible() && layer.compiled() instanceof SlotsComponent.Layer wells && wells.input()) {
                accepts.put(layer.id(), wells.accept());
            }
            if (layer.visibleIf() != null || layer.frames() != null
                    || (layer.compiled() instanceof TextComponent.Layer text
                            && dev.materione.content.gui.item.GuiTemplates.isTemplated(text.text()))) {
                variable.add(layer.id());
            }
        }
        var inputs = new ArrayList<CompiledGui.Input>();
        for (int slot = 0; slot < gui.container().slotCount(); slot++) {
            List<String> top = clicks.layersAt(slot);
            for (String layer : top) {
                if (accepts.containsKey(layer)) {
                    inputs.add(new CompiledGui.Input(slot, layer, accepts.get(layer)));
                    break;
                }
                if (!variable.contains(layer)) {
                    break;
                }
            }
        }
        return inputs;
    }

    /**
     * Splits the item layers between the static slot table and the per-viewer placements (logic.md §6.3). A
     * slot is static when every layer that fills it is a plain id with no override and no condition: then its
     * item is resolved once and two such layers in one slot are the existing skip (the earlier one). A slot
     * with any other layer goes wholly to the placements, where the layers compete by order at render time,
     * so a stack of conditional items in one slot is the intended use and is never skipped.
     */
    private static List<CompiledGui.Placement> placeItems(
            GuiDefinition gui, List<CompiledGui.Placement> items, Map<Integer, SlotPlacement> slots, Report report) {
        var bySlot = new TreeMap<Integer, List<CompiledGui.Placement>>();
        for (CompiledGui.Placement placement : items) {
            bySlot.computeIfAbsent(placement.slot(), ignored -> new ArrayList<>()).add(placement);
        }
        var placements = new ArrayList<CompiledGui.Placement>();
        bySlot.forEach((slot, group) -> {
            boolean perViewer = group.stream().anyMatch(p -> p.visibleIf() != null || p.item().staticItem().isEmpty());
            if (perViewer) {
                slots.remove(slot); // a static entry of an addon layer yields to the placements of its slot
                placements.addAll(group);
                return;
            }
            for (CompiledGui.Placement placement : group) {
                var sink = (SlotSink) (index, item, amount) -> {
                    SlotPlacement previous = slots.put(index, new SlotPlacement(placement.layerId(), item, amount));
                    if (previous != null && !previous.layerId().equals(placement.layerId())) {
                        report.skip(gui, previous.layerId(),
                                "slot " + index + " is filled by the later layer '" + placement.layerId() + "'");
                    }
                };
                placement.item().placeSlots(sink);
            }
        });
        placements.sort(java.util.Comparator.comparingInt(CompiledGui.Placement::order));
        return List.copyOf(placements);
    }


    /**
     * Flattens the visible raster layers of {@code layers}, bottom to top, onto one transparent
     * canvas of {@code width × height}. Each layer paints onto its own transparent canvas and is
     * composited with its opacity (gui.md §4). A layer whose component throws is reported to
     * {@code skipped} and contributes nothing; the others still draw.
     *
     * <p>This is the function the shared raster vectors pin: the studio and the compiler
     * render the same layers through it.
     */
    public static ArgbCanvas flatten(int width, int height, List<GuiLayer> layers, TextureSource textures,
            BiConsumer<GuiLayer, String> skipped) {
        return GuiFlatten.flatten(0, 0, width, height, layers, textures, skipped, layer -> { });
    }

    // --- text ------------------------------------------------------------------

    private static void collectText(GuiDefinition gui, String layerId, CompiledGuiLayer compiled,
            List<TextRun> runs, Report report) {
        var collected = new ArrayList<TextRun>();
        try {
            compiled.drawText((x, y, text, font, argb) -> collected.add(new TextRun(layerId, x, y, text, font, argb)));
        } catch (RuntimeException failure) {
            report.skip(gui, layerId, String.valueOf(failure.getMessage()));
            return;
        }
        for (TextRun run : collected) {
            String problem = textProblem(gui, run);
            if (problem != null) {
                report.skip(gui, layerId, problem);
                return;
            }
        }
        runs.addAll(collected);
    }

    /** Why a run cannot be drawn, or {@code null} when it can. */
    private static String textProblem(GuiDefinition gui, TextRun run) {
        for (int index = 0; index < run.text().length(); index++) {
            char character = run.text().charAt(index);
            if (RESERVED_TEXT.indexOf(character) >= 0) {
                return "text may not contain <, \\, & or §";
            }
            boolean supported = run.font() == TitleSink.Font.SMALLCAPS
                    ? SmallcapsFont.supports(character)
                    : VanillaAscii.supports(character);
            if (!supported) {
                return "the " + (run.font() == TitleSink.Font.SMALLCAPS ? "smallcaps" : "default")
                        + " font draws printable ASCII only";
            }
        }
        if (run.font() == TitleSink.Font.DEFAULT
                && GuiTitle.ascentFor(gui.container(), run.y()) > GuiFonts.VANILLA_ASCII_HEIGHT) {
            return "default-font text must start on row " + GuiTextRules.firstDefaultRow(gui.container())
                    + " or lower, because its glyphs are " + GuiFonts.VANILLA_ASCII_HEIGHT + " px high";
        }
        return null;
    }

    // --- slots -----------------------------------------------------------------

    private static void collectSlots(GuiDefinition gui, String layerId, CompiledGuiLayer compiled,
            Map<Integer, SlotPlacement> slots, Report report) {
        SlotSink sink = (slot, item, amount) -> {
            SlotPlacement previous = slots.put(slot, new SlotPlacement(layerId, item, amount));
            if (previous != null && !previous.layerId().equals(layerId)) {
                report.skip(gui, previous.layerId(),
                        "slot " + slot + " is filled by the later layer '" + layerId + "'");
            }
        };
        try {
            compiled.placeSlots(sink);
        } catch (RuntimeException failure) {
            report.skip(gui, layerId, String.valueOf(failure.getMessage()));
        }
    }

    // --- diagnostics -----------------------------------------------------------

    /** One {@code MTR-0052} for a layer skipped at compile time, targeting {@code layers.<index>}. */
    private static Diagnostic skipped(GuiDefinition gui, String layerId, String reason) {
        return new Diagnostic(
                DiagnosticCodes.GUI_LAYER_SKIPPED, Severity.WARN,
                "GUI layer '" + layerId + "' skipped: " + reason,
                gui.id(), null,
                "fix the layer's fields or its texture; the other layers of this GUI still compile",
                DiagnosticTarget.field("layers", String.valueOf(authoredIndex(gui, layerId))));
    }

    /** The layer's position in the authored {@code layers:} list, or {@code -1} when unknown. */
    static int authoredIndex(GuiDefinition gui, String layerId) {
        if (gui.raw().get("layers") instanceof List<?> authored) {
            for (int index = 0; index < authored.size(); index++) {
                if (authored.get(index) instanceof Map<?, ?> layer && layerId.equals(layer.get("id"))) {
                    return index;
                }
            }
        }
        return -1;
    }

    /**
     * The compile's skips: the diagnostics in the order they are raised, and the first reason
     * each layer was skipped for.
     */
    private static final class Report {

        final List<Diagnostic> diagnostics = new ArrayList<>();
        final Map<String, String> reasons = new LinkedHashMap<>();

        void skip(GuiDefinition gui, String layerId, String reason) {
            diagnostics.add(skipped(gui, layerId, reason));
            reasons.putIfAbsent(layerId, reason);
        }

        /** {@code MTR-0090}: the layer paints past the art cap. The layer is not skipped. */
        void clipped(GuiDefinition gui, String layerId) {
            GuiArt.Cap cap = GuiArt.Cap.of(gui.container());
            diagnostics.add(new Diagnostic(
                    DiagnosticCodes.GUI_ART_CLIPPED, Severity.WARN,
                    "GUI layer '" + layerId + "' paints past the art cap (x " + cap.x() + ".." + cap.right()
                            + ", y " + cap.y() + ".." + cap.bottom() + "); the part outside is clipped",
                    gui.id(), null,
                    "move or shrink the layer so it stays inside the cap; the GUI and the rest of the layer still compile",
                    DiagnosticTarget.field("layers", String.valueOf(authoredIndex(gui, layerId)))));
        }
    }

    /** A text run as the compiler collects it, before title assembly. */
    private record TextRun(String layerId, int x, int y, String text, TitleSink.Font font, int argb) {}

    /** The item a slot layer places, and the layer that placed it. */
    private record SlotPlacement(String layerId, NamespacedId item, int amount) {}
}
