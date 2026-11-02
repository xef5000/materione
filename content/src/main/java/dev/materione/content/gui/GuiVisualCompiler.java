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
import java.util.function.BiFunction;

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

    static GuiVisualPlan compile(GuiDefinition gui, int background, TextureSource textures,
            BiFunction<GuiLayer, Integer, Integer> codepoints, Map<String, byte[]> assets,
            List<GuiCompilation.FontProvider> fonts, BiConsumer<GuiLayer, String> skipped) {
        int height = CompiledGui.height(gui.rows());
        var pieces = new ArrayList<GuiVisualPlan.Piece>();
        String namespace = gui.id().namespace();
        for (int order = 0; order < gui.layers().size(); order++) {
            GuiLayer layer = gui.layers().get(order);
            if (!layer.visible()) {
                continue;
            }
            if (layer.compiled().target() == RenderTarget.RASTER) {
                List<CompiledGuiLayer> frames = layer.frames() == null
                        ? List.of(layer.compiled()) : layer.frames().layers();
                var canvases = new ArrayList<ArgbCanvas>();
                try {
                    for (CompiledGuiLayer frame : frames) {
                        var own = new ArgbCanvas(CompiledGui.WIDTH, height);
                        frame.paint(own, textures);
                        var output = new ArgbCanvas(CompiledGui.WIDTH, height);
                        output.composite(own, layer.opacity());
                        canvases.add(output);
                    }
                } catch (RuntimeException failure) {
                    skipped.accept(layer, failure.getMessage() == null ? failure.toString() : failure.getMessage());
                    continue;
                }
                var glyphs = new ArrayList<GuiVisualPlan.Glyph>();
                for (int frame = 0; frame < canvases.size(); frame++) {
                    int cp = codepoints.apply(layer, frame);
                    ArgbCanvas canvas = canvases.get(frame);
                    String texture = GuiOverlayKeys.texture(gui.id(), layer.id(), frame);
                    assets.put("assets/" + namespace + "/textures/" + texture + ".png",
                            PngEncoder.encodeRgba(CompiledGui.WIDTH, height, canvas.toArray()));
                    fonts.add(new GuiCompilation.FontProvider(GuiFonts.background(namespace),
                            GuiFonts.bitmapProvider(namespace + ":" + texture + ".png", cp, height)));
                    dev.materione.api.gui.ClickArea area;
                    try {
                        area = frames.get(frame).clickArea(textures);
                    } catch (RuntimeException ignored) {
                        area = dev.materione.api.gui.ClickArea.NONE;
                    }
                    glyphs.add(new GuiVisualPlan.Glyph(cp, canvas.inkWidth() + 1,
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
                boolean invalid = text.stream().anyMatch(run -> GuiTextRules.problem(run.text(), run.font(), run.y(),
                        GuiTemplates.isTemplated(run.text())) != null);
                if (invalid) {
                    skipped.accept(layer, "text contains unsupported characters or has an invalid row");
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
        return new GuiVisualPlan(namespace, background, 1, pieces);
    }
}
