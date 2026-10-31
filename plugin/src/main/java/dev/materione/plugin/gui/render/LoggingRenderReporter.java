package dev.materione.plugin.gui.render;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.DiagnosticCode;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Logs what a render worked around, once per distinct problem per window of time. A window renders on every
 * refresh tick, so an unresolved head would otherwise log forty times a second; this logs the first occurrence
 * and then at most once every thirty seconds for the same problem.
 */
public final class LoggingRenderReporter implements RenderReporter {

    private static final long WINDOW_NANOS = 30_000_000_000L;
    private static final int MAX_KEYS = 1024;

    private final Logger log;
    private final ConcurrentHashMap<String, Long> lastLogged = new ConcurrentHashMap<>();

    public LoggingRenderReporter(Logger log) {
        this.log = log;
    }

    @Override
    public void problem(DiagnosticCode code, NamespacedId gui, String layerId, String message) {
        String key = code.code() + " " + gui + " " + layerId + " " + message;
        long now = System.nanoTime();
        if (lastLogged.size() > MAX_KEYS) {
            lastLogged.clear(); // a flood of distinct messages must not grow the table without bound
        }
        Long previous = lastLogged.put(key, now);
        if (previous == null || now - previous >= WINDOW_NANOS) {
            log.warning(code.code() + " GUI " + gui + " layer '" + layerId + "': " + message);
        }
    }
}
