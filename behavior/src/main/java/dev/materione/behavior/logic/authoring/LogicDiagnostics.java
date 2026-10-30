package dev.materione.behavior.logic.authoring;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.behavior.flow.FlowCompiler;
import dev.materione.behavior.logic.LogicLookup;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The logic compiler's verdict on a resource's authored mapping, for hosts that fold it into their own
 * validation (the GUI's {@code gui.validate}, logic.md §10). It is the compiler's own problems, with the
 * step paths as their targets, and nothing is written.
 */
public final class LogicDiagnostics {

    private LogicDiagnostics() {}

    /** The problems {@link FlowCompiler} reports for {@code definition}'s {@code behavior:} and {@code flows:}. */
    public static List<Diagnostic> of(NamespacedId owner, Map<String, Object> definition, LogicLookup lookup) {
        Objects.requireNonNull(owner, "owner");
        Map<String, Object> authored = definition == null ? Map.of() : definition;
        return FlowCompiler.compile(owner, authored.get("behavior"), authored.get("flows"), lookup).problems();
    }
}
