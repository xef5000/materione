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
import dev.materione.content.gui.raster.ArgbCanvas;
import dev.materione.content.gui.raster.PngEncoder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;

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
 * GUI still compiles.
 */
public final class GuiCompiler {

    /** Characters a text run may not carry: MiniMessage's tag opener and escape, and legacy codes. */
    private static final String RESERVED_TEXT = "<\\&§";

    private GuiCompiler() {}

    /**
     * Compiles one GUI.
     *
     * @param gui the parsed GUI
     * @param backgroundCodepoint the GUI's durable background codepoint ({@link GuiBackgrounds})
     * @param textures pixel access to the owning pack's textures
     */
    public static GuiCompilation compile(GuiDefinition gui, int backgroundCodepoint, TextureSource textures) {
        var diagnostics = new ArrayList<Diagnostic>();
        String namespace = gui.id().namespace();
        int width = CompiledGui.WIDTH;
        int height = CompiledGui.height(gui.rows());
        TextureSource bound = reference -> textures.texture(
                reference.contains(":") ? reference : namespace + ":" + reference);

        ArgbCanvas background = flatten(width, height, gui.layers(), bound,
                (layer, reason) -> diagnostics.add(skipped(gui, layer.id(), reason)));

        var runs = new ArrayList<TextRun>();
        var slots = new LinkedHashMap<Integer, SlotPlacement>();
        for (var layer : gui.layers()) {
            if (!layer.visible()) {
                continue;
            }
            if (layer.compiled().target() == RenderTarget.TEXT) {
                collectText(gui, layer.id(), layer.compiled(), runs, diagnostics);
            } else if (layer.compiled().target() == RenderTarget.SLOT) {
                collectSlots(gui, layer.id(), layer.compiled(), slots, diagnostics);
            }
        }

        // the client's advance for a bitmap glyph: its ink width plus one pixel of spacing
        int advance = background.inkWidth() + 1;
        var title = new GuiTitle(namespace, backgroundCodepoint, advance);
        var textFonts = new LinkedHashMap<String, List<Map<String, Object>>>();
        boolean smallcapsUsed = false;
        var reported = new LinkedHashSet<String>();
        for (TextRun run : runs) {
            if (!title.canPlace()) {
                if (reported.add(run.layerId())) {
                    diagnostics.add(skipped(gui, run.layerId(),
                            "its text follows a default-font run, whose width the compiler cannot measure; "
                                    + "place it before that run, or use smallcaps"));
                }
                continue;
            }
            title.place(run.x(), run.y(), run.text(), run.font(), run.argb());
            String key = GuiFonts.text(namespace, run.font(), run.y());
            textFonts.computeIfAbsent(key, ignored -> run.font() == TitleSink.Font.SMALLCAPS
                    ? GuiFonts.smallcaps(namespace, run.y())
                    : GuiFonts.defaults(run.y()));
            smallcapsUsed |= run.font() == TitleSink.Font.SMALLCAPS;
        }

        var fonts = new ArrayList<GuiCompilation.FontProvider>();
        fonts.add(new GuiCompilation.FontProvider(GuiFonts.background(namespace),
                GuiFonts.backgroundProvider(namespace, gui.id().value(), backgroundCodepoint, height)));
        textFonts.forEach((key, providers) -> providers.forEach(
                provider -> fonts.add(new GuiCompilation.FontProvider(key, provider))));

        var assets = new TreeMap<String, byte[]>();
        assets.put(GuiFonts.backgroundAsset(namespace, gui.id().value()),
                PngEncoder.encodeRgba(width, height, background.toArray()));
        if (smallcapsUsed) {
            assets.put(GuiFonts.smallcapsAsset(namespace), SmallcapsFont.sheetPng());
        }

        var slotList = new ArrayList<CompiledGui.Slot>();
        slots.forEach((slot, placement) -> slotList.add(new CompiledGui.Slot(slot, placement.item(), placement.amount())));
        slotList.sort(Comparator.comparingInt(CompiledGui.Slot::slot));
        var model = new CompiledGui(gui.id(), gui.rows(), title.build(), gui.title(), slotList);
        return new GuiCompilation(model, diagnostics, assets, fonts);
    }

    /**
     * Flattens the visible raster layers of {@code layers}, bottom to top, onto one
     * transparent canvas of {@code width × height}. Each layer paints onto its own transparent
     * canvas and is composited with its opacity (gui.md §4). A layer whose component throws
     * is reported to {@code skipped} and contributes nothing; the others still draw.
     *
     * <p>This is the function the shared raster vectors pin: the studio and the compiler
     * render the same layers through it.
     */
    public static ArgbCanvas flatten(int width, int height, List<GuiLayer> layers, TextureSource textures,
            BiConsumer<GuiLayer, String> skipped) {
        ArgbCanvas output = new ArgbCanvas(width, height);
        for (GuiLayer layer : layers) {
            if (!layer.visible() || layer.compiled().target() != RenderTarget.RASTER) {
                continue;
            }
            var own = new ArgbCanvas(width, height);
            try {
                layer.compiled().paint(own, textures);
            } catch (RuntimeException failure) {
                skipped.accept(layer, failure.getMessage() != null ? failure.getMessage() : failure.toString());
                continue;
            }
            output.composite(own, layer.opacity());
        }
        return output;
    }

    // --- text ------------------------------------------------------------------

    private static void collectText(GuiDefinition gui, String layerId, CompiledGuiLayer compiled,
            List<TextRun> runs, List<Diagnostic> diagnostics) {
        var collected = new ArrayList<TextRun>();
        try {
            compiled.drawText((x, y, text, font, argb) -> collected.add(new TextRun(layerId, x, y, text, font, argb)));
        } catch (RuntimeException failure) {
            diagnostics.add(skipped(gui, layerId, String.valueOf(failure.getMessage())));
            return;
        }
        for (TextRun run : collected) {
            String problem = textProblem(run);
            if (problem != null) {
                diagnostics.add(skipped(gui, layerId, problem));
                return;
            }
        }
        runs.addAll(collected);
    }

    /** Why a run cannot be drawn, or {@code null} when it can. */
    private static String textProblem(TextRun run) {
        for (int index = 0; index < run.text().length(); index++) {
            char character = run.text().charAt(index);
            if (RESERVED_TEXT.indexOf(character) >= 0) {
                return "text may not contain <, \\, & or §";
            }
            boolean supported = run.font() == TitleSink.Font.SMALLCAPS
                    ? SmallcapsFont.supports(character)
                    : character >= 0x20 && character <= 0x7E;
            if (!supported) {
                return "the " + (run.font() == TitleSink.Font.SMALLCAPS ? "smallcaps" : "default")
                        + " font draws printable ASCII only";
            }
        }
        if (run.font() == TitleSink.Font.DEFAULT && GuiTitle.ascentFor(run.y()) > GuiFonts.VANILLA_ASCII_HEIGHT) {
            return "default-font text must start on row " + (GuiTitle.ascentFor(0) - GuiFonts.VANILLA_ASCII_HEIGHT)
                    + " or lower, because its glyphs are " + GuiFonts.VANILLA_ASCII_HEIGHT + " px high";
        }
        return null;
    }

    // --- slots -----------------------------------------------------------------

    private static void collectSlots(GuiDefinition gui, String layerId, CompiledGuiLayer compiled,
            Map<Integer, SlotPlacement> slots, List<Diagnostic> diagnostics) {
        SlotSink sink = (slot, item, amount) -> {
            SlotPlacement previous = slots.put(slot, new SlotPlacement(layerId, item, amount));
            if (previous != null && !previous.layerId().equals(layerId)) {
                diagnostics.add(skipped(gui, previous.layerId(),
                        "slot " + slot + " is filled by the later layer '" + layerId + "'"));
            }
        };
        try {
            compiled.placeSlots(sink);
        } catch (RuntimeException failure) {
            diagnostics.add(skipped(gui, layerId, String.valueOf(failure.getMessage())));
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
    private static int authoredIndex(GuiDefinition gui, String layerId) {
        if (gui.raw().get("layers") instanceof List<?> authored) {
            for (int index = 0; index < authored.size(); index++) {
                if (authored.get(index) instanceof Map<?, ?> layer && layerId.equals(layer.get("id"))) {
                    return index;
                }
            }
        }
        return -1;
    }

    /** A text run as the compiler collects it, before title assembly. */
    private record TextRun(String layerId, int x, int y, String text, TitleSink.Font font, int argb) {}

    /** The item a slot layer places, and the layer that placed it. */
    private record SlotPlacement(String layerId, NamespacedId item, int amount) {}
}
