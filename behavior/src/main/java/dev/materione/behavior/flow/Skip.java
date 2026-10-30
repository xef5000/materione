package dev.materione.behavior.flow;

/**
 * Control flow of the compiler: thrown after a problem has been reported, to unwind to the place
 * that decides what is skipped. It never escapes the package and carries no stack trace.
 *
 * <p>The scope encodes the fail-soft policy (logic.md §10): a problem the compiler understood (an
 * unknown action, rejected parameters, a malformed expression) skips the {@link Scope#STEP} that
 * holds it; a problem that makes the author's intent unreadable (a step that is not a step, an
 * unresolvable call) skips the whole {@link Scope#UNIT}, the rule or flow being compiled.
 */
final class Skip extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** How much is lost. */
    enum Scope { STEP, UNIT }

    private final transient Scope scope;

    private Skip(Scope scope) {
        super(null, null, false, false);
        this.scope = scope;
    }

    static Skip step() {
        return new Skip(Scope.STEP);
    }

    static Skip unit() {
        return new Skip(Scope.UNIT);
    }

    Scope scope() {
        return scope;
    }
}
