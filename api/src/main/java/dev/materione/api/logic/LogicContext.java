package dev.materione.api.logic;

import dev.materione.api.logic.variable.VariableBinding;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything a running action, condition or function may see (logic.md §4, runtime-systems §2.2).
 * It is deliberately narrow: typed {@link ContextFacet facets} for the things only a platform can
 * supply, the expression-visible variable tree, the run-local variables, and placeholder
 * resolution. There is no {@code getPlayer()} and no Bukkit handle here, which is what keeps the
 * engine free of the platform and lets a test run an action against a fake context.
 *
 * <p><b>Lifecycle and threading.</b> A context belongs to one run. The engine builds it when a
 * trigger fires and hands it to one thread at a time; implementations are not thread-safe and
 * types must not retain a context after {@code execute}/{@code test} returns. A resumable SPI hook
 * may use it until its returned stage completes, but only from the single continuation thread
 * supplied by its {@link ReadExecutor}; it must not retain it afterwards. A facet's value is
 * subject to the thread rules of its own type (a {@code Player} is only safe on its owner thread),
 * which is exactly what {@link Threading} declares.
 *
 * <p><b>Purity.</b> Reading the context (every method here except mutating {@link #locals()}) has
 * no side effects, so a {@link ConditionType#test condition} may call all of it.
 */
public interface LogicContext {

    /** The value held for {@code facet}, or empty when this run has none. */
    <T> Optional<T> facet(ContextFacet<T> facet);

    /** True when this run holds a value for {@code facet}. */
    default boolean has(ContextFacet<?> facet) {
        return facet(facet).isPresent();
    }

    /**
     * The value held for {@code facet}.
     *
     * @throws MissingFacetException when there is none
     */
    default <T> T require(ContextFacet<T> facet) {
        return facet(facet).orElseThrow(() -> new MissingFacetException(facet));
    }

    /**
     * Reads the expression-visible variable tree ({@code player.level}, {@code item.amount},
     * {@code vars.count}); dotted paths walk nested maps. A missing segment is {@code null}, not an
     * error: absent variables are data (runtime-systems §2.1).
     *
     * @param path the path segments, e.g. {@code ["player", "level"]}
     */
    Object resolve(List<String> path);

    /** {@link #resolve(List)} for a dotted path such as {@code "player.level"}. */
    default Object value(String dottedPath) {
        return resolve(Arrays.asList(dottedPath.split("\\.")));
    }

    /**
     * The run-local variables: what expressions read as {@code vars.<name>} and what
     * {@code set_var} writes. Mutable, and private to this run; it is the only mutable thing a
     * context hands out.
     */
    Map<String, Object> locals();

    /**
     * The declared persistent variables this run can read and write: the run's player and the
     * server (logic.md §3.3). The default has none, so a context without a platform (a unit test's
     * fake) refuses every persistent write and reads every declared variable as its absence.
     */
    default VariableBinding variables() {
        return VariableBinding.NONE;
    }

    /**
     * Resolves PlaceholderAPI-style {@code %token%}s in already-rendered text for this run's
     * player. The default leaves the text untouched, which is also what a server without
     * PlaceholderAPI does. Normally reached through {@link TemplateText#render}, not called
     * directly.
     */
    default String applyPlaceholders(String text) {
        return text;
    }
}
