package dev.materione.core.diagnostics;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.diagnostics.Severity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Thread-safe diagnostics bus with bounded history (for
 * {@code /materione doctor}) and isolated listeners: a broken listener never
 * breaks a publish.
 */
public final class DiagnosticsBusService implements DiagnosticsBus {

    private final int historyCapacity;
    private final ArrayDeque<Diagnostic> history = new ArrayDeque<>();
    private final Map<Severity, Integer> counts = new EnumMap<>(Severity.class);
    private final CopyOnWriteArrayList<Consumer<Diagnostic>> listeners = new CopyOnWriteArrayList<>();

    public DiagnosticsBusService(int historyCapacity) {
        if (historyCapacity <= 0) {
            throw new IllegalArgumentException("historyCapacity must be > 0");
        }
        this.historyCapacity = historyCapacity;
        for (Severity severity : Severity.values()) {
            counts.put(severity, 0);
        }
    }

    @Override
    public void publish(Diagnostic diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        List<Consumer<Diagnostic>> toNotify;
        synchronized (this) {
            history.addLast(diagnostic);
            while (history.size() > historyCapacity) {
                history.removeFirst();
            }
            counts.merge(diagnostic.severity(), 1, Integer::sum);
            toNotify = List.copyOf(listeners);
        }
        for (Consumer<Diagnostic> listener : toNotify) {
            try {
                listener.accept(diagnostic);
            } catch (Throwable t) {
                // A broken listener never breaks a publish; drop it loudly once.
                listeners.remove(listener);
            }
        }
    }

    @Override
    public AutoCloseable subscribe(Consumer<Diagnostic> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    @Override
    public synchronized List<Diagnostic> recent(int max) {
        if (max <= 0) {
            return List.of();
        }
        int from = Math.max(0, history.size() - max);
        return List.copyOf(new ArrayList<>(history).subList(from, history.size()));
    }

    @Override
    public synchronized int count(Severity severity) {
        return counts.getOrDefault(severity, 0);
    }

    /** Total published diagnostics regardless of severity. */
    public synchronized int total() {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }
}
