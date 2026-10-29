package dev.materione.behavior.interp;

import dev.materione.behavior.expr.EvaluationContext;
import dev.materione.behavior.expr.Expression;
import dev.materione.behavior.expr.ExpressionParser;
import dev.materione.behavior.expr.Expressions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code ${expression}} interpolation for author-facing strings (messages,
 * commands). Parsed segments are memoized per source text — strings in rule
 * definitions are static, so parsing happens once per distinct text even
 * though executors call render directly. PlaceholderAPI tokens ({@code
 * %placeholder%}) pass through untouched; the platform resolves them after
 * rendering.
 */
public final class Template {

    private record Segment(String literal, Expression expression) {}

    private static final ConcurrentHashMap<String, List<Segment>> CACHE = new ConcurrentHashMap<>();

    private Template() {}

    /**
     * Parses (and memoizes) {@code template} now, so a malformed {@code ${...}} is found when a
     * rule loads rather than when it first runs.
     *
     * @throws IllegalArgumentException on an empty or unparsable {@code ${...}}
     */
    public static void validate(String template) {
        if (template != null && !template.isEmpty()) {
            CACHE.computeIfAbsent(template, Template::parse);
        }
    }

    /** Renders {@code template}, evaluating every {@code ${...}} against the context. */
    public static String render(String template, EvaluationContext context) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        List<Segment> segments = CACHE.computeIfAbsent(template, Template::parse);
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

    private static List<Segment> parse(String template) {
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
                segments.add(new Segment(null, ExpressionParser.parse(expressionText)));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(
                        "bad ${" + expressionText + "} in template '" + template + "': " + e.getMessage());
            }
            at = close + 1;
        }
        return List.copyOf(segments);
    }
}
