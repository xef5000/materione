package dev.materione.content.gui;

import dev.materione.api.gui.RenderTarget;
import dev.materione.api.gui.TextureSource;
import dev.materione.content.gui.raster.ArgbCanvas;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Flattens raster layers onto one canvas (gui.md §4 step 1, §11.2). */
final class GuiFlatten {

    private GuiFlatten() {}

    /**
     * Flattens the visible raster layers of {@code layers}, bottom to top, onto one transparent canvas whose
     * top-left is the GUI pixel {@code (originX, originY)}. Each layer paints at its authored position onto its
     * own transparent canvas of the same rectangle and is composited with its opacity. A layer whose component
     * throws is reported to {@code skipped} and contributes nothing; the others still draw. A layer that painted
     * a visible pixel outside the rectangle is reported to {@code clipped} and keeps what fell inside.
     *
     * <p>The compiler flattens over the art cap (gui.md §11.2); the shared vectors flatten over the screen.
     */
    static ArgbCanvas flatten(int originX, int originY, int width, int height, List<GuiLayer> layers,
            TextureSource textures, BiConsumer<GuiLayer, String> skipped, Consumer<GuiLayer> clipped) {
        ArgbCanvas output = new ArgbCanvas(originX, originY, width, height);
        ArgbCanvas own = null;
        for (GuiLayer layer : layers) {
            if (!layer.visible() || layer.compiled().target() != RenderTarget.RASTER) {
                continue;
            }
            if (own == null) {
                own = new ArgbCanvas(originX, originY, width, height);
            }
            own.clear();
            try {
                layer.compiled().paint(own, textures);
            } catch (RuntimeException failure) {
                skipped.accept(layer, failure.getMessage() != null ? failure.getMessage() : failure.toString());
                continue;
            }
            if (own.clippedInk()) {
                clipped.accept(layer);
            }

            output.composite(own, layer.opacity());
        }
        return output;
    }
    /**
     * Each layer as the compiler placed it, in list order. {@code rank} is the layer's position in
     * the compiled order of the visible layers: raster first, then text, then slot items, each in
     * list order (the studio's {@code TARGET_RANK}, gui.md §6); hidden layers have rank -1.
     */
    static List<GuiCompilation.LayerReport> layerReports(GuiDefinition gui, Map<String, String> reasons) {
        var visible = new ArrayList<GuiLayer>();
        for (GuiLayer layer : gui.layers()) {
            if (layer.visible()) {
                visible.add(layer);
            }
        }
        // a stable sort keeps list order within a target
        visible.sort(Comparator.comparingInt(layer -> targetRank(layer.compiled().target())));
        var ranks = new LinkedHashMap<String, Integer>();
        for (int index = 0; index < visible.size(); index++) {
            ranks.put(visible.get(index).id(), index);
        }
        var out = new ArrayList<GuiCompilation.LayerReport>();
        for (GuiLayer layer : gui.layers()) {
            out.add(new GuiCompilation.LayerReport(
                    layer.id(), layer.compiled().target(),
                    layer.visible() ? ranks.get(layer.id()) : -1,
                    reasons.get(layer.id())));
        }
        return out;
    }

    private static int targetRank(RenderTarget target) {
        return switch (target) {
            case RASTER -> 0;
            case TEXT -> 1;
            case SLOT -> 2;
        };
    }
}
