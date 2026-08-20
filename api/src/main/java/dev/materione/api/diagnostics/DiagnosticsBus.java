package dev.materione.api.diagnostics;

import java.util.List;
import java.util.function.Consumer;

/**
 * The diagnostics bus: every failure anywhere becomes a {@link Diagnostic}
 * published here (fail-soft backbone, plugin architecture §5). Thread-safe;
 * publishing never throws, and a broken listener never breaks a publish.
 */
public interface DiagnosticsBus {

    /** Publishes a diagnostic. Safe from any thread. */
    void publish(Diagnostic diagnostic);

    /**
     * Subscribes a listener.
     *
     * @return closeable that unsubscribes the listener
     */
    AutoCloseable subscribe(Consumer<Diagnostic> listener);

    /** The most recent diagnostics, newest last, at most {@code max}. */
    List<Diagnostic> recent(int max);

    /** Total published diagnostics of the given severity since startup. */
    int count(Severity severity);
}
