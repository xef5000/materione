package dev.materione.behavior.logic.recipe;

import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.logic.LogicAuthoringApi.InstantiateRequest;
import dev.materione.api.logic.Recipe;
import dev.materione.api.logic.RecipeDescription;
import dev.materione.behavior.flow.FlowCompiler;
import dev.materione.behavior.logic.LogicIds;
import dev.materione.behavior.logic.LogicLookup;
import dev.materione.behavior.rule.ParsedRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Expands a recipe request into ordinary rules (logic.md §4.6, §30). The steps are: find the recipe; check and type
 * its parameters ({@link RecipeParams}); run its expansion; compile the result with the same {@link FlowCompiler}
 * that loads a file, and refuse it if it does not validate; then report the actions this server cannot run now
 * ({@link RecipeAvailability}). Nothing is written: the caller writes the rules through the normal edit path, so the
 * file stays the only truth (D1).
 */
public final class RecipeExpander {

    private final LogicLookup lookup;

    /** @param lookup the registries the recipes, actions, conditions, triggers and variables resolve against */
    public RecipeExpander(LogicLookup lookup) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
    }

    /**
     * The rules a recipe expands to for {@code request}.
     *
     * @return the rules, with any availability warnings; or the refusal, with every problem found
     */
    public ApiResult<List<Map<String, Object>>> expand(InstantiateRequest request) {
        String name = request.recipe();
        Optional<Recipe> found = lookup.recipe(name);
        if (found.isEmpty()) {
            return ApiResult.fail(new Diagnostic(DiagnosticCodes.CONTENT_SCHEMA_INVALID, Severity.ERROR,
                    "unknown recipe '" + name + "': no recipe with that id is registered", null, null,
                    "list the recipes the server has with logic.vocabulary"));
        }
        Recipe recipe = found.get();
        RecipeDescription description;
        try {
            description = recipe.describe();
        } catch (RuntimeException failure) {
            return refused("recipe '" + name + "' has no readable description: " + failure.getMessage());
        }
        ApiResult<Map<String, Object>> typed = RecipeParams.resolve(description, request.params(), lookup);
        if (!typed.ok()) {
            return ApiResult.fail(typed.diagnostics());
        }
        List<Map<String, Object>> rules;
        try {
            rules = recipe.expand(typed.value());
        } catch (RuntimeException failure) {
            return refused("recipe '" + name + "' failed to expand: " + failure.getMessage());
        }
        if (rules == null || rules.isEmpty()) {
            return refused("recipe '" + name + "' expands to no rules");
        }
        ApiResult<List<Map<String, Object>>> checked = validate(name, rules);
        if (!checked.ok()) {
            return checked;
        }
        return ApiResult.ok(List.copyOf(rules), RecipeAvailability.warnings(name, rules, lookup));
    }

    /** The compiler's verdict on the expansion, as a file of this recipe would be judged. */
    private ApiResult<List<Map<String, Object>>> validate(String name, List<Map<String, Object>> rules) {
        Optional<NamespacedId> owner = LogicIds.parse(name);
        if (owner.isEmpty()) {
            return refused("recipe '" + name + "' is not a valid id");
        }
        ParsedRules compiled = FlowCompiler.compile(owner.get(), List.copyOf(rules), null, lookup);
        if (compiled.problems().isEmpty()) {
            return ApiResult.ok(rules);
        }
        int count = compiled.problems().size();
        var refusal = new ArrayList<Diagnostic>();
        refusal.add(Diagnostic.error(DiagnosticCodes.RECIPE_EXPANSION_REFUSED, "the expansion of '" + name
                + "' does not validate (" + count + (count == 1 ? " problem)" : " problems)")));
        refusal.addAll(compiled.problems());
        return ApiResult.fail(refusal);
    }

    private static <T> ApiResult<T> refused(String message) {
        return ApiResult.fail(Diagnostic.error(DiagnosticCodes.RECIPE_EXPANSION_REFUSED, message));
    }
}
