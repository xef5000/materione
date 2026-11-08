package dev.materione.content.gui;

import dev.materione.api.NamespacedId;
import dev.materione.api.gui.CompiledGuiLayer;
import dev.materione.api.gui.GuiContainer;
import dev.materione.api.gui.GuiComponent;
import dev.materione.api.gui.GuiComponentConfiguration;
import dev.materione.api.gui.RenderTarget;
import dev.materione.core.document.LosslessYaml;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Raster animation frames, each a component-field patch over the base layer. */
public record GuiRasterFrames(int every, List<CompiledGuiLayer> layers) {
    public GuiRasterFrames {
        layers = List.copyOf(layers);
    }

    static GuiRasterFrames parse(Object raw, GuiComponent component, NamespacedId gui, String layer,
            GuiContainer container, Map<String, Object> base) {
        if (!(raw instanceof Map<?, ?> mapping)) {
            throw new IllegalArgumentException("frames must be a mapping with every and layers");
        }
        Map<String, Object> fields = LosslessYaml.stringKeyed(mapping);
        Integer every = GuiLayerParser.wholeNumber(fields.get("every"));
        if (every == null || every < 1 || !(fields.get("layers") instanceof List<?> entries) || entries.isEmpty()) {
            throw new IllegalArgumentException("frames needs every (positive ticks) and a non-empty layers list");
        }
        if (fields.size() != 2 || entries.size() > 256) {
            throw new IllegalArgumentException("frames takes only every and up to 256 layers");
        }
        var frames = new ArrayList<CompiledGuiLayer>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> patch)) {
                throw new IllegalArgumentException("each raster frame must be a component-field mapping");
            }
            var params = new LinkedHashMap<>(base);
            Map<String, Object> overrides = LosslessYaml.stringKeyed(patch);
            if (overrides.keySet().stream().anyMatch(key -> key.equals(GuiLayerParser.OVERLAY_FRAMES) || key.equals("visible_if")
                    || key.equals("id") || key.equals("type") || key.equals("opacity"))) {
                throw new IllegalArgumentException("raster frames may override component fields only");
            }
            params.putAll(overrides);
            CompiledGuiLayer compiled = component.parse(new GuiComponentConfiguration(gui, layer, container, params));
            if (compiled == null || compiled.target() != RenderTarget.RASTER) {
                throw new IllegalArgumentException("frame did not produce a raster layer");
            }
            frames.add(compiled);
        }
        return new GuiRasterFrames(every, frames);
    }
}
