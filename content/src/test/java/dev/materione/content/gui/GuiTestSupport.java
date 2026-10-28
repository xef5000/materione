package dev.materione.content.gui;

import dev.materione.api.NamespacedId;
import dev.materione.api.gui.GuiComponent;
import dev.materione.api.registry.Registry;
import dev.materione.api.registry.RegistryKeys;
import dev.materione.core.document.LosslessYaml;
import dev.materione.core.registry.RegistryService;
import dev.materione.testing.golden.GoldenFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared fixtures for the GUI compiler tests: the built-in registry, golden loading, and tiny builders. */
public final class GuiTestSupport {

    /** The namespace the fixture pack's textures and the test GUIs live in. */
    public static final String NAMESPACE = "shop";

    private GuiTestSupport() {}

    /** The gui-components registry with the built-ins registered, as bootstrap does. */
    public static Registry<GuiComponent> components() {
        var registries = new RegistryService();
        var registry = registries.registry(RegistryKeys.GUI_COMPONENTS, GuiComponent.class);
        for (GuiComponent component : GuiComponents.builtins()) {
            registry.register(NamespacedId.of("materione", component.name()),
                    component, component.describe(), "materione");
        }
        return registry;
    }

    public static GuiType type() {
        return new GuiType(components());
    }

    /** The document under {@code key} in a fixture GUI file, as the pipeline hands it to parse. */
    public static Map<String, Object> golden(String file, String key) throws IOException {
        String raw = Files.readString(GoldenFiles.fixturesRoot().resolve("gui").resolve(file));
        @SuppressWarnings("unchecked")
        Map<String, Object> definition = (Map<String, Object>) LosslessYaml.parse(raw).data().get(key);
        return definition;
    }

    /** A GUI definition with a title, a row count and the given layers, as the pipeline parses it. */
    @SafeVarargs
    public static Map<String, Object> definition(int rows, Map<String, Object>... layers) {
        var list = new ArrayList<Object>();
        for (Map<String, Object> layer : layers) {
            list.add(layer);
        }
        var definition = new LinkedHashMap<String, Object>();
        definition.put("title", "Test");
        definition.put("rows", rows);
        definition.put("layers", list);
        return definition;
    }

    /** One layer: {@code id}, {@code type}, then the given key/value pairs in order. */
    public static Map<String, Object> layer(String id, String type, Object... keysAndValues) {
        var layer = new LinkedHashMap<String, Object>();
        layer.put("id", id);
        layer.put("type", type);
        for (int index = 0; index < keysAndValues.length; index += 2) {
            layer.put((String) keysAndValues[index], keysAndValues[index + 1]);
        }
        return layer;
    }

    public static GuiDefinition parse(GuiType type, Map<String, Object> definition) {
        var parsed = type.parse(NamespacedId.of(NAMESPACE, "test"), definition, Map.of());
        if (parsed.isEmpty()) {
            throw new AssertionError("definition did not parse: " + definition);
        }
        return (GuiDefinition) parsed.get();
    }
}
