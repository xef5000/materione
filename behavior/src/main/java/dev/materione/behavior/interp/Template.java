package dev.materione.behavior.interp;

import dev.materione.behavior.expr.EvaluationContext;
import dev.materione.behavior.expr.Expression;
import dev.materione.behavior.expr.ExpressionParser;
import dev.materione.behavior.expr.Expressions;
import dev.materione.behavior.expr.FunctionResolver;
import dev.materione.behavior.expr.ResumableExpressions;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ReadExecutor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * {@code ${expression}} interpolation for author-facing strings (messages,
 * commands). Parsed segments are memoized per source text — strings in rule
 * definitions are static, so parsing happens once per distinct text even
 * though executors call render directly. PlaceholderAPI tokens ({@code
 * %placeholder%}) pass through untouched; the platform resolves them after
 * rendering.
 *
 * <p>A template that calls a registered function must be compiled against the registry
 * ({@link #compile}), because the calls are bound at parse time. The static {@link #render} and
 * {@link #validate} entry points know no registry, so they accept no function calls; the engine
 * uses {@link #compile} and holds the result.
 */
public final class Template {

    private record Segment(String literal, Expression expression) {}

    /** A template parsed against a function table; rendering it never parses again. */
    public static final class Compiled {

        private final List<Segment> segments;

        private Compiled(List<Segment> segments) {
            this.segments = segments;
        }

        /** The parsed {@code ${...}} expressions, in order (the flow compiler reads the variable roots they touch). */
        public List<Expression> expressions() {
            return segments.stream().map(Segment::expression).filter(java.util.Objects::nonNull).toList();
        }

        /** Renders the template against {@code context}. */
        public String render(EvaluationContext context) {
            var out = new StringBuilder();
            for (Segment segment : segments) {
                if (segment.literal() != null) {
                    out.append(segment.literal());
                } else {
                    out.append(Expressions.stringify(segment.expression().eval(context)));
                }
            }
            return out.toString();
        }

        /** Renders segments in authored order, retaining prior segments across scheduled reads. */
        public CompletionStage<String> renderResumable(LogicContext context, ReadExecutor executor) {
            var reads = ResumableExpressions.reads(context, executor);
            CompletionStage<StringBuilder> result = CompletableFuture.completedFuture(new StringBuilder());
            for (Segment segment : segments) {
                result = result.thenCompose(out -> {
                    if (!executor.active()) {
                        return CompletableFuture.failedFuture(
                                new java.util.concurrent.CancellationException("run ended"));
                    }
                    if (segment.literal() != null) {
                        return CompletableFuture.completedFuture(out.append(segment.literal()));
                    }
                    return ResumableExpressions.evaluate(segment.expression().tree(), reads)
                            .thenApply(value -> out.append(Expressions.stringify(value)));
                });
            }
            return result.thenApply(out -> {
                if (!executor.active()) {
                    throw new java.util.concurrent.CancellationException("run ended");
                }
                return out.toString();
            });
        }
    }

    private static final ConcurrentHashMap<String, List<Segment>> CACHE = new ConcurrentHashMap<>();

    private Template() {}

    /**
     * Parses {@code template} against {@code functions} now, so a malformed {@code ${...}}, an
     * unknown function or a wrong argument count is found when a rule loads rather than when it
     * first runs.
     *
     * @throws IllegalArgumentException on an empty or unparsable {@code ${...}}
     */
    public static Compiled compile(String template, FunctionResolver functions) {
        if (template == null || template.isEmpty()) {
            return new Compiled(List.of());
        }
        return new Compiled(parse(template, functions));
    }

    /**
     * Parses (and memoizes) {@code template} now, so a malformed {@code ${...}} is found when a
     * rule loads rather than when it first runs.
     *
     * @throws IllegalArgumentException on an empty or unparsable {@code ${...}}
     */
    public static void validate(String template) {
        if (template != null && !template.isEmpty()) {
            CACHE.computeIfAbsent(template, text -> parse(text, FunctionResolver.NONE));
        }
    }

    /** Renders {@code template}, evaluating every {@code ${...}} against the context (no function calls). */
    public static String render(String template, EvaluationContext context) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        List<Segment> segments = CACHE.computeIfAbsent(template, text -> parse(text, FunctionResolver.NONE));
        return new Compiled(segments).render(context);
    }

    private static List<Segment> parse(String template, FunctionResolver functions) {
        var segments = new ArrayList<Segment>();
        int at = 0;
        while (at < template.length()) {
            int open = template.indexOf("${", at);
            if (open < 0) {
                segments.add(new Segment(template.substring(at), null));
                break;
            }
            if (open > at) {
                segments.add(new Segment(template.substring(at, open), null));
            }
            int close = template.indexOf('}', open + 2);
            if (close < 0) {
                // unterminated: keep the raw text rather than guessing
                segments.add(new Segment(template.substring(open), null));
                break;
            }
            String expressionText = template.substring(open + 2, close).trim();
            if (expressionText.isEmpty()) {
                throw new IllegalArgumentException("empty ${} in template: " + template);
            }
            try {
                segments.add(new Segment(null, ExpressionParser.parse(expressionText, functions)));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(
                        "bad ${" + expressionText + "} in template '" + template + "': " + e.getMessage());
            }
            at = close + 1;
        }
        return List.copyOf(segments);
    }
}
