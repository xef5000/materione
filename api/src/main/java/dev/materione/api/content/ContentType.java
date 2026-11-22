package dev.materione.api.content;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A content type (items, blocks, furniture…) — the contract every built-in
 * and addon type implements, registered through the same registry path (D5).
 * The registry's {@link SelfDescription} is the single schema source: it
 * drives validation, editor forms and docs (master §5.6 — never a second
 * hand-maintained copy).
 */
public interface ContentType {

    /** Type name ({@code item}, {@code block}, …) — also the registry id value. */
    String name();

    /** Pack directory this type's files live in (master §7.2: {@code items}). */
    String directory();

    /** The one schema source for this type. */
    SelfDescription describe();

    /**
     * Whether a file of this type may hold one resource as its top-level fields, with the file name as its id
     * ({@code currencies/gems.yml} with {@code integration: xp} is {@code pack:gems}), beside the keyed form every
     * type takes (master section 7.2, logic.md section 18, #469). Self-description, so the editor and the code-mode
     * SDK learn which directories accept the flat form from the registry and keep no list of them (#475). The
     * pipeline's rule is {@code PackLayout.SINGLE_RESOURCE_DIRS}; a type reports it, it does not restate it.
     */
    default boolean flatFile() {
        return false;
    }

    /**
     * How a resource of this type is read inside a logic expression, as a template over {@code {pack}},
     * {@code {id}} and {@code {<field>}} (a field of the resource), or {@code null} when a resource of this type
     * is not something an expression reads. A declared variable reads as {@code vars.{scope}.{pack}.{id}}
     * (logic.md section 3.3); a reference to it everywhere else is {@code pack:id}. Self-description, so a
     * consumer that writes expressions does not keep the spelling by hand (issue 355).
     */
    default String expressionForm() {
        return null;
    }

    /**
     * Parses one resource keyed in a file of this type into a typed
     * definition, resolving {@code template:} inheritance against the
     * passed template pool. Diamond inheritance is a parse failure (empty
     * result — the validator quarantines). Parsing never throws; fail-soft
     * is the pipeline's job (D6).
     */
    Optional<Object> parse(NamespacedId id, Map<String, Object> definition, Map<String, Map<String, Object>> templates);

    /**
     * Optional display metadata from the type's parsed representation. A
     * default keeps existing addons source-compatible and leaves generic
     * summary fallback to the authoring layer.
     */
    default Optional<ResourcePresentation> presentation(Object parsedDefinition) {
        return Optional.empty();
    }

    /**
     * Non-fatal problems inside a definition that still compiles: a per-part
     * quarantine, such as one bad GUI layer while the rest of the GUI stays.
     * The compiler publishes these against the resource and keeps the
     * definition. The default reports none, so existing types are unchanged.
     */
    default List<Diagnostic> diagnose(Object parsedDefinition) {
        return List.of();
    }

    /**
     * Compiles one definition into pack output through {@link PackEmitter}.
     * The compiler calls this per definition, in graph order; hooks stay
     * deterministic (same definition + allocation → same output, D7).
     * Stable durable values come from {@link Allocations} — hooks never
     * derive ids themselves. Expected rejections throw
     * {@link CompileRejectException}; anything else is treated as a crash.
     */
    void compile(
            Object definition,
            NamespacedId allocatedModelId,
            PackEmitter emitter,
            Allocations allocations);
}
