package dev.materione.core.pipeline.validate;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.core.pipeline.ContentPack;
import dev.materione.core.pipeline.ContentResource;
import dev.materione.core.pipeline.PipelineContext;
import java.util.ArrayList;
import java.util.List;

/**
 * VALIDATE (plugin architecture §4.3), levels 1–4: syntax (already proven
 * by parse), schema, semantic, platform. Validation is <em>pure</em> — no
 * I/O, no clocks, no randomness (enforced by ArchUnit rule R6) — so the same
 * document always validates the same way and validation is unit-testable
 * against fixtures alone.
 *
 * <p>Level 3 reference resolution lives in {@link ReferenceValidator};
 * level-5 build/allocation diagnostics arrive with the compiler (M1 slice 2).
 * Content-type schemas are registry-driven from slice 2; this stage already
 * enforces the structural floor (mapping shape, ids) and quarantines per
 * resource, never per file.
 */
public final class ValidateStage {

    private final List<Validator> validators = List.of(
            new ReferenceValidator(),
            new ItemComponentsValidator());

    /** A pure check over one resource; returns diagnostics, never throws. */
    public interface Validator {
        List<Diagnostic> validate(ContentResource resource, ValidationEnvironment environment);
    }

    public void run(PipelineContext context) {
        for (ContentPack pack : context.packs()) {
            if (pack.manifest() == null) {
                // manifest diagnostics were already published at parse; the
                // pack's resources are excluded from the graph (fail-soft).
                pack.resources().forEach(resource -> context.excluded().add(resource.id()));
            }
        }
        var environment = new ValidationEnvironment(context.packs(), context.knownAssets(), context.paintingSizes());
        for (ContentPack pack : context.packs()) {
            if (pack.manifest() == null) {
                continue;
            }
            for (ContentResource resource : pack.resources()) {
                var problems = new ArrayList<Diagnostic>();
                for (Validator validator : validators) {
                    problems.addAll(validator.validate(resource, environment));
                }
                boolean fatal = problems.stream().anyMatch(d -> d.severity() == Severity.ERROR);
                if (fatal) {
                    context.excluded().add(resource.id());
                    for (Diagnostic problem : problems) {
                        context.quarantine().add(resource.id(), problem);
                    }
                    context.diagnostics().publish(new Diagnostic(
                            DiagnosticCodes.CONTENT_QUARANTINED, Severity.WARN,
                            "resource " + resource.id() + " quarantined",
                            resource.id(), null,
                            "fix the reported problems, then /materione reload"));
                } else {
                    problems.forEach(context.diagnostics()::publish);
                }
            }
        }
    }
}
