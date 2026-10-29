package dev.materione.api.logic;

/**
 * A parameter value that may contain {@code ${expression}} segments and {@code %placeholder%}
 * tokens (logic.md §2.5: every parameter is a template). Obtained from
 * {@link ParamReader#template}; the malformed-{@code ${}} check happens at that call, at load, so
 * rendering cannot fail on syntax.
 *
 * <p>Rendering is a pure read of the context (an expression that cannot evaluate renders as an
 * empty or partial value or throws {@link IllegalStateException}; the engine contains it). It is
 * cheap enough for the click path: the source was parsed once.
 */
public interface TemplateText {

    /** The authored text, as written. */
    String source();

    /**
     * Renders against {@code context}: evaluates each {@code ${...}} segment, then applies
     * {@link LogicContext#applyPlaceholders}.
     */
    String render(LogicContext context);
}
