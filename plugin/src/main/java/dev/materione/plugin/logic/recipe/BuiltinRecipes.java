package dev.materione.plugin.logic.recipe;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.LogicApi;
import dev.materione.api.logic.Recipe;
import dev.materione.api.logic.RecipeTemplate;
import dev.materione.behavior.logic.LogicIds;
import dev.materione.core.document.LosslessYaml;
import dev.materione.core.document.LosslessYamlDocument;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.plugin.Plugin;

/**
 * The v1 recipes (logic.md §7.5, §30), each a YAML resource in the plugin jar under {@code recipes/}, registered
 * through the public {@link LogicApi#recipes()} like an addon's recipe (D5). A resource that does not load is left
 * out with {@code MTR-0085}, and the others register: a broken recipe never stops the server or the logic.
 *
 * <p>The trash slot ({@code trash_slot}) is a composition over {@code custody.consume} (#248, logic.md §36): its behavior is
 * only its YAML.
 */
public final class BuiltinRecipes {

    /** The built-in recipe ids, in the order the editor lists them. Each has {@code recipes/<id>.yml}. */
    public static final List<String> IDS = List.of(
            "pay_to_receive",
            "close_button",
            "welcome_message",
            "confirm_dialog",
            "paged_player_list",
            "toggle_setting",
            "server_selector",
            "trash_slot",
            "back_button");

    private BuiltinRecipes() {}

    /**
     * Registers every built-in recipe under {@code materione:}, owned by {@code owner}.
     *
     * @param diagnostics where a recipe that does not load is reported ({@code MTR-0085})
     */
    public static void register(LogicApi api, Plugin owner, Consumer<Diagnostic> diagnostics) {
        for (String id : IDS) {
            try {
                api.recipes().register(owner, NamespacedId.of(LogicIds.BUILT_IN_NAMESPACE, id), load(id));
            } catch (IOException | RuntimeException failure) {
                diagnostics.accept(Diagnostic.warn(DiagnosticCodes.RECIPE_LOAD_FAILED,
                        "recipe " + id + " does not load and is not available: " + failure.getMessage()));
            }
        }
    }

    /**
     * One built-in recipe, read from its YAML resource.
     *
     * @throws IOException when the resource is missing from the jar
     * @throws IllegalArgumentException when the YAML or the template is malformed
     */
    public static Recipe load(String id) throws IOException {
        LosslessYamlDocument document = LosslessYaml.parse(read(id));
        if (document.parseError().isPresent()) {
            throw new IllegalArgumentException(document.parseError().get());
        }
        Object data = LosslessYaml.stringKeyedDeep(document.data());
        if (!(data instanceof Map<?, ?> mapping)) {
            throw new IllegalArgumentException("the recipe is not a mapping");
        }
        var definition = new LinkedHashMap<String, Object>();
        mapping.forEach((key, value) -> definition.put(String.valueOf(key), value));
        return RecipeTemplate.parse(id, definition);
    }

    private static String read(String id) throws IOException {
        try (InputStream in = BuiltinRecipes.class.getResourceAsStream("/recipes/" + id + ".yml")) {
            if (in == null) {
                throw new IOException("no recipes/" + id + ".yml resource in the plugin jar");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
