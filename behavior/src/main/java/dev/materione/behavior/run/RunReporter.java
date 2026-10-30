package dev.materione.behavior.run;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCode;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.behavior.flow.StepPath;
import dev.materione.behavior.rule.Rule;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Runtime diagnostics, rate-limited (plugin.md §5): one broken action or condition degrades to a
 * diagnostic naming the rule and the step path, at most once per rule per second, so a hot trigger
 * never floods the bus and the chain around the failure carries on.
 */
public final class RunReporter {

    /** Minimum milliseconds between two reports of the same code for the same rule. */
    static final long INTERVAL_MS = 1000;

    private final Consumer<Diagnostic> sink;
    private final LongSupplier clock;
    private final Map<String, Long> last = new ConcurrentHashMap<>();

    public RunReporter(Consumer<Diagnostic> sink) {
        this(sink, System::currentTimeMillis);
    }

    public RunReporter(Consumer<Diagnostic> sink, LongSupplier clock) {
        this.sink = sink;
        this.clock = clock;
    }

    /** An action or condition failed at run time ({@code MTR-0023}); the chain continues. */
    public void failure(Rule rule, StepPath path, String problem) {
        report(DiagnosticCodes.ACTION_FAILED, Severity.WARN, rule, path, problem,
                "behavior continues despite the failure; repeated reports are rate-limited");
    }

    /** A run budget ended a run ({@code MTR-0059}). */
    public void aborted(Rule rule, StepPath path, String problem) {
        report(DiagnosticCodes.FLOW_RUN_ABORTED, Severity.WARN, rule, path, problem,
                "simplify the flow or raise the cause; the server is unaffected and repeated reports are rate-limited");
    }

    private void report(DiagnosticCode code, Severity severity, Rule rule, StepPath path, String problem, String hint) {
        long now = clock.getAsLong();
        String key = code.code() + "|" + rule.owner() + "|" + rule.id();
        Long previous = last.get(key);
        if (previous != null && now - previous < INTERVAL_MS) {
            return;
        }
        last.put(key, now);
        sink.accept(new Diagnostic(code, severity,
                rule.owner() + " rule '" + rule.id() + "' at " + path + ": " + problem,
                rule.owner(), null, hint, path.target()));
    }
}
