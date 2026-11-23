package dev.materione.plugin.logic.variable;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.logic.variable.VariableDeclaration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Reports an item variable that was read or written in a run with no item stack ({@code MTR-0065}), once per variable
 * per interval, because a hot trigger would otherwise report it on every event.
 */
final class StackMissReports {

    /** Minimum milliseconds between two reports for the same variable of a run that has no stack. */
    static final long INTERVAL_MS = 10_000;

    private final Map<String, Long> last = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final Consumer<Diagnostic> diagnostics;

    StackMissReports(LongSupplier clock, Consumer<Diagnostic> diagnostics) {
        this.clock = clock;
        this.diagnostics = diagnostics;
    }

    /** Reports the miss unless this variable was reported within the interval. */
    void report(VariableDeclaration declaration, String what) {
        long now = clock.getAsLong();
        boolean[] emit = {false};
        last.compute(declaration.id().toString(), (id, previous) -> {
            if (previous == null || now - previous >= INTERVAL_MS) {
                emit[0] = true;
                return now;
            }
            return previous;
        });
        if (emit[0]) {
            diagnostics.accept(new Diagnostic(DiagnosticCodes.VARIABLE_STORE_FAILED, Severity.WARN,
                    "item variable " + declaration.id() + " was " + what + " in a run with no item stack",
                    declaration.id(), null,
                    "vars.item.* needs a trigger that is about an item stack (click, entity.hit, ...) and a non-empty"
                            + " hand; the read is the default and the write is dropped. Repeated reports are rate-limited"));
        }
    }
}
