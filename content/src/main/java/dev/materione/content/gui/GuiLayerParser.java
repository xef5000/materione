package dev.materione.content.gui;

import dev.materione.api.NamespacedId;
import dev.materione.api.gui.CompiledGuiLayer;
import dev.materione.api.gui.GuiComponent;
import dev.materione.api.gui.GuiComponentConfiguration;
import dev.materione.api.gui.RenderTarget;
import dev.materione.api.registry.Registry;
import dev.materione.api.registry.RegistryEntry;
import dev.materione.core.document.LosslessYaml;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Parses ordered GUI layers, quarantining each rejected layer independently. */
final class GuiLayerParser {

    /** Common fields are withheld except for name when the component declares it. */
    private static final Set<String> COMMON_FIELDS =
            Set.of("id", "type", "name", "visible", "locked", "opacity", "visible_if");

    private final Registry<GuiComponent> components;

    GuiLayerParser(Registry<GuiComponent> components) {
        this.components = components;
    }

    record Result(List<GuiLayer> layers, List<GuiDefinition.SkippedLayer> skipped) {}

    Result parse(NamespacedId guiId, int rows, List<?> entries) {
        var layers = new ArrayList<GuiLayer>();
        var skipped = new ArrayList<GuiDefinition.SkippedLayer>();
        var accepted = new HashSet<String>();
        for (int index = 0; index < entries.size(); index++) {
            try {
                GuiLayer layer = layer(guiId, rows, entries.get(index), accepted);
                accepted.add(layer.id());
                layers.add(layer);
            } catch (Rejection rejection) {
                skipped.add(new GuiDefinition.SkippedLayer(
                        index, rejection.layerId, rejection.type, rejection.getMessage()));
            }
        }
        return new Result(layers, skipped);
    }

    /** Parses one authored layer or rejects it with the reason shown to the author. */
    private GuiLayer layer(NamespacedId guiId, int rows, Object entry, Set<String> accepted) {
        if (!(entry instanceof Map<?, ?> mapping)) {
            throw new Rejection(null, null, "a layer must be a mapping of its fields");
        }
        Map<String, Object> fields = LosslessYaml.stringKeyed(mapping);
        String layerId = fields.get("id") instanceof String text ? text : null;
        String type = fields.get("type") instanceof String text && !text.isBlank() ? text : null;
        if (layerId == null || !GuiType.LAYER_ID.matcher(layerId).matches()) {
            throw new Rejection(layerId, type, "id must match [a-z0-9_]+");
        }
        if (accepted.contains(layerId)) {
            throw new Rejection(layerId, type, "duplicate layer id; the first layer with this id is kept");
        }
        if (type == null) {
            throw new Rejection(layerId, null, "type is required and names a component");
        }
        NamespacedId componentId;
        try {
            componentId = type.contains(":")
                    ? NamespacedId.parse(type)
                    : NamespacedId.of(GuiType.BUILTIN_NAMESPACE, type);
        } catch (IllegalArgumentException malformed) {
            throw new Rejection(layerId, type, "type '" + type + "' is not a valid component name");
        }
        GuiComponent component = components.get(componentId)
                .map(RegistryEntry::entry)
                .orElseThrow(() -> new Rejection(layerId, type, "unknown component '" + type + "'"));

        String name = fields.get("name") instanceof String text && !text.isBlank() ? text : null;
        boolean visible = flag(fields, "visible", true, layerId, type);
        boolean locked = flag(fields, "locked", false, layerId, type);
        int opacity = opacity(fields, layerId, type);
        RenderTarget target = targetOf(component, layerId, type);
        if (fields.containsKey("opacity") && target != RenderTarget.RASTER) {
            throw new Rejection(layerId, type, "opacity applies to raster layers only");
        }

        Object visibleIf = visibleIf(fields, layerId, type);
        boolean takesName = declaresName(component);
        var own = new LinkedHashMap<String, Object>();
        fields.forEach((key, value) -> {
            if (!COMMON_FIELDS.contains(key) || (takesName && "name".equals(key))) {
                own.put(key, value);
            }
        });
        CompiledGuiLayer compiled;
        try {
            compiled = component.parse(new GuiComponentConfiguration(guiId, layerId, rows, own));
        } catch (IllegalArgumentException rejected) {
            throw new Rejection(layerId, type, rejected.getMessage());
        } catch (RuntimeException failure) {
            // containment boundary: an addon's parser bug skips its layer, never the GUI
            throw new Rejection(layerId, type, "component failed: " + failure);
        }
        if (compiled == null || compiled.target() != target) {
            throw new Rejection(layerId, type, "component produced a layer for a different target");
        }
        if (visibleIf != null && !(compiled instanceof ItemComponent.Layer)) {
            // Raster and text layers need overlay glyphs before they can change in an open window (#217).
            throw new Rejection(layerId, type, "visible_if works on item layers in this build; "
                    + "raster and text layers need overlay glyphs (logic.md §6.4, #217)");
        }
        return new GuiLayer(layerId, componentId, name, visible, locked, opacity, compiled, visibleIf);
    }

    private static boolean declaresName(GuiComponent component) {
        try {
            return component.describe().parameters().stream().anyMatch(parameter -> "name".equals(parameter.id()));
        } catch (RuntimeException failure) {
            return false; // containment: a broken description receives only its own fields
        }
    }

    private static Object visibleIf(Map<String, Object> fields, String layerId, String type) {
        if (!fields.containsKey("visible_if")) {
            return null;
        }
        Object value = fields.get("visible_if");
        if (value instanceof String || value instanceof Boolean || value instanceof List<?>
                || value instanceof Map<?, ?>) {
            return value;
        }
        throw new Rejection(layerId, type, "visible_if is a condition: an expression, a predicate or a list");
    }

    private static RenderTarget targetOf(GuiComponent component, String layerId, String type) {
        try {
            RenderTarget target = component.target();
            if (target == null) {
                throw new IllegalStateException("no render target");
            }
            return target;
        } catch (RuntimeException failure) {
            throw new Rejection(layerId, type, "component failed: " + failure);
        }
    }

    private static boolean flag(Map<String, Object> fields, String key, boolean fallback,
            String layerId, String type) {
        if (!fields.containsKey(key)) {
            return fallback;
        }
        if (fields.get(key) instanceof Boolean value) {
            return value;
        }
        throw new Rejection(layerId, type, key + " must be true or false");
    }

    private static int opacity(Map<String, Object> fields, String layerId, String type) {
        if (!fields.containsKey("opacity")) {
            return GuiType.MAX_OPACITY;
        }
        Integer value = wholeNumber(fields.get("opacity"));
        if (value == null || value < 0 || value > GuiType.MAX_OPACITY) {
            throw new Rejection(layerId, type, "opacity must be a whole number from 0 to " + GuiType.MAX_OPACITY);
        }
        return value;
    }

    /** A whole number as authored (YAML may read it as any numeric type), or null. */
    static Integer wholeNumber(Object value) {
        if (value instanceof Number number) {
            double decimal = number.doubleValue();
            if (Double.isFinite(decimal) && decimal == Math.rint(decimal)
                    && decimal >= Integer.MIN_VALUE && decimal <= Integer.MAX_VALUE) {
                return (int) decimal;
            }
        }
        return null;
    }

    private static final class Rejection extends RuntimeException {
        private final String layerId;
        private final String type;

        Rejection(String layerId, String type, String reason) {
            super(reason);
            this.layerId = layerId;
            this.type = type;
        }
    }
}
