package dev.materione.core.pipeline.validate;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.core.pipeline.ContentResource;
import java.util.List;
import java.util.Map;

/**
 * The item {@code components:} block is advertised schema surface whose
 * pass-through is not built yet (#103): parsed into the definition, never
 * applied to any stack. A silently-ignored block would masquerade as
 * applied — this warns per authoring so the pack author knows the vanilla
 * component did nothing (fail-soft honesty, not an error: the rest of the
 * item still ships).
 */
public final class ItemComponentsValidator implements ValidateStage.Validator {

    @Override
    public List<Diagnostic> validate(ContentResource resource, ValidationEnvironment environment) {
        if (!"item".equals(resource.contentType())) {
            return List.of();
        }
        Object mapping = resource.document().data().get(resource.id().value());
        if (!(mapping instanceof Map<?, ?> definition)
                || !(definition.get("components") instanceof Map<?, ?> components)
                || components.isEmpty()) {
            return List.of();
        }
        return List.of(new Diagnostic(
                DiagnosticCodes.ITEM_COMPONENTS_NOT_APPLIED, Severity.WARN,
                "item " + resource.id() + " authors " + components.size()
                        + " vanilla component(s) that are not applied: "
                        + components.keySet().stream().sorted().toList(),
                resource.id(), null,
                "the version-aware component pass-through is not built yet (#103) — "
                        + "use the dedicated fields (display_name, lore, food/jukebox mechanics) "
                        + "or the appearance surface; the block is parsed only"));
    }
}
