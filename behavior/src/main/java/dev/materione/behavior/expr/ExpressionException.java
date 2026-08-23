package dev.materione.behavior.expr;

/** A rule failed to parse or an evaluation hit a type error (fail-soft upstream). */
public final class ExpressionException extends RuntimeException {

    public ExpressionException(String message) {
        super(message);
    }
}
