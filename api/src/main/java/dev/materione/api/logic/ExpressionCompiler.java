package dev.materione.api.logic;

/**
 * The port through which {@link ParamReader} turns authored text into templates and expressions.
 * The expression engine lives in the {@code behavior} module, which the API cannot depend on, so
 * the engine supplies the real compiler when it parses a rule; a type never sees this interface
 * except in a test that builds its own {@link ParamReader}.
 */
public interface ExpressionCompiler {

    /**
     * Compiles a template.
     *
     * @throws IllegalArgumentException on a malformed {@code ${...}} segment
     */
    TemplateText template(String source);

    /**
     * Compiles a bare expression.
     *
     * @throws IllegalArgumentException on a syntax error
     */
    ValueExpression expression(String source);

    /**
     * A compiler with no expression engine behind it, for a unit test of a type whose parameters
     * are all literal: a template renders as its source, and any {@code ${...}} segment or
     * expression is rejected so a test cannot silently pass a value the engine would evaluate.
     */
    ExpressionCompiler LITERAL = new ExpressionCompiler() {
        @Override
        public TemplateText template(String source) {
            if (source.contains("${")) {
                throw new IllegalArgumentException(
                        "'${...}' needs the expression engine; use the engine's compiler in this test");
            }
            return new TemplateText() {
                @Override
                public String source() {
                    return source;
                }

                @Override
                public String render(LogicContext context) {
                    return context.applyPlaceholders(source);
                }
            };
        }

        @Override
        public ValueExpression expression(String source) {
            throw new IllegalArgumentException(
                    "expressions need the expression engine; use the engine's compiler in this test");
        }
    };
}
