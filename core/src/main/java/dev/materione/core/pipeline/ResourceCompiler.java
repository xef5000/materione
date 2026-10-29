package dev.materione.core.pipeline;

import dev.materione.api.NamespacedId;
import dev.materione.api.content.Allocations;
import dev.materione.api.content.CompileRejectException;
import dev.materione.api.content.ContentType;
import dev.materione.api.content.PackEmitter;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.diagnostics.Severity;
import dev.materione.core.diagnostics.QuarantineTracker;
import java.util.Map;
import java.util.Optional;

/**
 * The per-resource compile sequence (plugin.md §4.2–§4.4): parse one resource, diagnose the parts
 * of the parsed definition that still compile, then compile it through its content type. The pack
 * compiler runs it for every resource of a pack, and the GUI API runs it for a saved file or a
 * draft, so a preview and a pack cannot disagree about what a resource produces (D6, D7).
 *
 * <p>A resource is contained: a crash in any step is one diagnostic and one quarantine entry for
 * that resource, and the others carry on. What the caller does with the diagnostics and the
 * quarantine entries is its own {@link Sink}; this class writes nothing itself.
 */
public final class ResourceCompiler {

    /** Where one resource's diagnostics and quarantine entries go. */
    public interface Sink {

        /** A diagnostic about the resource, in the order it is raised. */
        void publish(Diagnostic diagnostic);

        /** The resource is skipped for this reason (a crash or a rejected compile). */
        void quarantine(NamespacedId resource, Diagnostic diagnostic);

        /** The sink of a pack compile: the bus and the quarantine tracker. */
        static Sink of(DiagnosticsBus diagnostics, QuarantineTracker quarantine) {
            return new Sink() {
                @Override
                public void publish(Diagnostic diagnostic) {
                    diagnostics.publish(diagnostic);
                }

                @Override
                public void quarantine(NamespacedId resource, Diagnostic diagnostic) {
                    quarantine.add(resource, diagnostic);
                }
            };
        }
    }

    private ResourceCompiler() {}

    /**
     * Parses, diagnoses and compiles one resource.
     *
     * @param resource the resource's id
     * @param rawDefinition the resource's authored mapping
     * @param templates every template of the pack set, by {@code pack:id}
     * @param type the content type the resource's directory names
     * @param emitter where the compile writes its output (the pack's emitter, or a collecting one)
     * @param allocations the durable value view the compile reads (a preview view for drafts)
     * @param sink receives the resource's diagnostics and quarantine entries
     * @return the parsed definition, or empty when the resource did not parse
     */
    public static Optional<Object> compile(
            NamespacedId resource,
            Map<String, Object> rawDefinition,
            Map<String, Map<String, Object>> templates,
            ContentType type,
            PackEmitter emitter,
            Allocations allocations,
            Sink sink) {
        Optional<Object> parsed;
        try {
            parsed = type.parse(resource, rawDefinition, templates);
        } catch (RuntimeException e) {
            crashed(resource, e, sink);
            return Optional.empty();
        }
        if (parsed.isEmpty()) {
            sink.publish(new Diagnostic(
                    DiagnosticCodes.CONTENT_SCHEMA_INVALID, Severity.ERROR,
                    "resource " + resource + " failed to parse as " + type.name(),
                    resource, null,
                    "check the required fields (the registry self-description documents them)"));
            return Optional.empty();
        }
        // per-part quarantine: problems inside a definition that still compiles
        // (one bad GUI layer) are published against the resource, not the pack
        try {
            for (Diagnostic part : type.diagnose(parsed.get())) {
                sink.publish(part);
            }
        } catch (RuntimeException e) {
            crashed(resource, e, sink);
            return parsed;
        }
        NamespacedId modelId = NamespacedId.of(resource.namespace(), resource.value() + "_model");
        try {
            type.compile(parsed.get(), modelId, emitter, allocations);
        } catch (CompileRejectException e) {
            Diagnostic rejected = new Diagnostic(
                    e.code(), Severity.ERROR, e.getMessage(), resource, null, e.hint());
            sink.publish(rejected);
            sink.quarantine(resource, rejected);
        } catch (RuntimeException e) {
            crashed(resource, e, sink);
        }
        return parsed;
    }

    /** A throwing parse, diagnose or compile is contained to its resource: MTR-0011 and a quarantine entry. */
    private static void crashed(NamespacedId resource, RuntimeException e, Sink sink) {
        Diagnostic crash = new Diagnostic(
                DiagnosticCodes.PIPELINE_STAGE_FAILED, Severity.ERROR,
                "content type crashed on " + resource + ": " + e,
                resource, null,
                "this resource was skipped; if it worked before, a recent edit is the cause");
        sink.publish(crash);
        sink.quarantine(resource, crash);
    }
}
