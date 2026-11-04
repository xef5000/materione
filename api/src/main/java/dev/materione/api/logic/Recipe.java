package dev.materione.api.logic;

import java.util.List;
import java.util.Map;

/**
 * A recipe: a parameterized fragment of rules that expands into ordinary rules (logic.md §4.6, §30). Recipes are
 * registered like every other logic type ({@link LogicApi#recipes()}), so the editor and the API see the same list
 * (D5). An expansion never persists as a recipe: the rules it returns are written to the file as rules, and from
 * then on they are the truth (D1).
 *
 * <p>The engine checks the parameters against {@link #describe()} before {@link #expand} runs, so the expansion
 * receives only declared names, with defaults applied and each value typed as its {@link RecipeParamType} says.
 * The expansion is then checked by the flow compiler; one that does not validate is refused, not written.
 *
 * <pre>{@code
 * api.logic().recipes().register(this, NamespacedId.of("example", "greet_on_open"), new GreetOnOpen());
 * }</pre>
 */
public interface Recipe {

    /** The local id, which must equal the registration id's value (as for every logic type). */
    String id();

    /** The name, the help text and the typed parameters. */
    RecipeDescription describe();

    /**
     * The rules this recipe writes for {@code params}: one map per rule, in the authored shape of a
     * {@code behavior:} entry ({@code on:}, its filters, {@code do:}). Never writes and never reads the world.
     *
     * @param params every declared parameter, typed and defaulted
     */
    List<Map<String, Object>> expand(Map<String, Object> params);
}
