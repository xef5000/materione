package dev.materione.content.gui;

import dev.materione.api.gui.CompiledGuiLayer;
import dev.materione.api.gui.RenderTarget;
import dev.materione.api.gui.TextureSource;
import dev.materione.content.gui.item.GuiTemplates;
import dev.materione.content.gui.raster.ArgbCanvas;
import dev.materione.content.gui.raster.PngEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Compiles ordered title pieces when a GUI needs per-viewer visuals. */
final class GuiVisualCompiler {
    private GuiVisualCompiler() {}

    static boolean needed(GuiDefinition gui) {
        return gui.layers().stream().anyMatch(layer -> layer.visible()
                && ((layer.compiled().target() == RenderTarget.RASTER
                        && (layer.visibleIf() != null || layer.frames() != null))
                    || (layer.compiled().target() == RenderTarget.TEXT
                        && (layer.visibleIf() != null || templated(layer.compiled())))));
    }

    private static boolean templated(CompiledGuiLayer layer) {
        var templated = new boolean[1];
        try {
            layer.drawText((x, y, text, font, color) -> templated[0] |= GuiTemplates.isTemplated(text));
        } catch (RuntimeException ignored) {
            return false;
        }
        return templated[0];
    }

    /**
     * Compiles the ordered pieces of a dynamic GUI. Each visible raster layer paints onto the art cap
     * (gui.md §11), is cut into tiles per frame (a frame that paints nothing contributes no tile, no PNG, no
     * provider and no codepoint), and each tile asks {@code codepoints} for its glyph. A layer that paints past
     * the cap is reported to {@code clipped} once.
     */
    static GuiVisualPlan compile(GuiDefinition gui, TextureSource textures,
            GuiCodepoints codepoints, Map<String, byte[]> assets,
            List<GuiCompilation.FontProvider> fonts, BiConsumer<GuiLayer, String> skipped,
            Consumer<GuiLayer> clipped) {
        var pieces = new ArrayList<GuiVisualPlan.Piece>();
        String namespace = gui.id().namespace();
        // one pair of cap-sized buffers serves every layer and frame; clear() only touches what was written
        ArgbCanvas own = null;
        ArgbCanvas output = null;
        for (int order = 0; order < gui.layers().size(); order++) {
            GuiLayer layer = gui.layers().get(order);
            if (!layer.visible()) {
                continue;
            }
            if (layer.compiled().target() == RenderTarget.RASTER) {
                List<CompiledGuiLayer> frames = layer.frames() == null
                        ? List.of(layer.compiled()) : layer.frames().layers();
                if (own == null) {
                    own = GuiArt.capCanvas();
                    output = GuiArt.capCanvas();
                }
                var tilesByFrame = new ArrayList<List<GuiArt.Tile>>();
                boolean pastCap = false;
                boolean failed = false;
                for (CompiledGuiLayer frame : frames) {
                    own.clear();
                    output.clear();
                    try {
                        frame.paint(own, textures);
                    } catch (RuntimeException failure) {
                        skipped.accept(layer, failure.getMessage() == null ? failure.toString() : failure.getMessage());
                        failed = true;
                        break;
                    }
                    pastCap |= own.clippedInk();
                    output.composite(own, layer.opacity());
                    tilesByFrame.add(GuiArt.cut(output)); // an invariant failure quarantines the GUI
                }
                if (failed) {
                    continue;
                }
                if (pastCap) {
                    clipped.accept(layer);
                }
                var glyphs = new ArrayList<GuiVisualPlan.Glyph>();
                for (int frame = 0; frame < tilesByFrame.size(); frame++) {
                    var placed = new ArrayList<GuiTitle.ArtGlyph>();
                    for (GuiArt.Tile tile : tilesByFrame.get(frame)) {
                        int cp = codepoints.codepoint(layer.id(), frame, tile.row(), tile.col());
                        String texture = GuiOverlayKeys.texture(gui.id(), layer.id(), frame, tile.row(), tile.col());
                        assets.put(GuiFonts.textureAsset(namespace, texture),
                                PngEncoder.encodeRgba(tile.width(), tile.cell(), tile.argb()));
                        fonts.add(new GuiCompilation.FontProvider(GuiFonts.background(namespace),
                                GuiFonts.tileProvider(namespace, texture, tile, cp)));
                        placed.add(new GuiTitle.ArtGlyph(cp, tile.x(), tile.width()));
                    }
                    dev.materione.api.gui.ClickArea area;
                    try {
                        area = frames.get(frame).clickArea(textures);
                    } catch (RuntimeException ignored) {
                        area = dev.materione.api.gui.ClickArea.NONE;
                    }
                    glyphs.add(new GuiVisualPlan.Glyph(placed,
                            java.util.Set.copyOf(dev.materione.content.gui.logic.ClickMapCompiler.coveredSlots(
                                    area == null ? dev.materione.api.gui.ClickArea.NONE : area, gui.rows()))));
                }
                pieces.add(new GuiVisualPlan.Raster(layer.id(), order, layer.visibleIf(), glyphs,
                        layer.frames() == null ? 0 : layer.frames().every()));
            } else if (layer.compiled().target() == RenderTarget.TEXT) {
                var text = new ArrayList<GuiVisualPlan.Text>();
                try {
                    int index = order;
                    layer.compiled().drawText((x, y, value, font, argb) ->
                            text.add(new GuiVisualPlan.Text(layer.id(), index, layer.visibleIf(),
                                    x, y, value, font, argb)));
                } catch (RuntimeException failure) {
                    skipped.accept(layer, failure.getMessage() == null ? failure.toString() : failure.getMessage());
                    continue;
                }
                String problem = text.stream()
                        .map(run -> GuiTextRules.problem(run.text(), run.font(), run.y(),
                                GuiTemplates.isTemplated(run.text())))
                        .filter(java.util.Objects::nonNull)
                        .findFirst().orElse(null);
                if (problem != null) {
                    skipped.accept(layer, problem);
                    continue;
                }
                pieces.addAll(text);
                for (GuiVisualPlan.Text run : text) {
                    List<Map<String, Object>> providers = run.font() == dev.materione.api.gui.TitleSink.Font.SMALLCAPS
                            ? GuiFonts.smallcaps(namespace, run.y()) : GuiFonts.defaults(run.y());
                    providers.forEach(provider -> fonts.add(new GuiCompilation.FontProvider(
                            GuiFonts.text(namespace, run.font(), run.y()), provider)));
                    if (run.font() == dev.materione.api.gui.TitleSink.Font.SMALLCAPS) {
                        assets.put(GuiFonts.smallcapsAsset(namespace), SmallcapsFont.sheetPng());
                    }
                }
            }
        }
        // The studio and static compiler draw all raster layers before all text layers.
        pieces.sort(java.util.Comparator.comparingInt(piece -> piece instanceof GuiVisualPlan.Raster ? 0 : 1));
        return new GuiVisualPlan(namespace, pieces);
    }
}
