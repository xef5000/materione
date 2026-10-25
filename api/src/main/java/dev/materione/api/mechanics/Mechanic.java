package dev.materione.api.mechanics;

import dev.materione.api.registry.SelfDescription;

/**
 * One mechanic in the {@code mechanics} registry (runtime-systems §2.3,
 * plugin.md §3) — the public addon seam. Built-ins and addons register
 * through the identical path; entries are self-described so the web editor
 * renders parameter forms from the description alone.
 *
 * <p>A mechanic is a factory, not an item binding. Parsing runs off-thread
 * and must be pure; listeners and owned resources belong to its runtime.
 * Built-ins implement this same contract as external plugins.
 */
public interface Mechanic {

    /** The mechanic's name as used in item YAML ({@code - soulbound: {}}). */
    String name();

    /** Editor/doc metadata: display name, summary, parameter schema. */
    SelfDescription describe();

    /**
     * Whether an item may declare this mechanic more than once. Repeated
     * declarations are independent bindings, processed in authored order.
     * Stateful single-slot mechanics should retain the default.
     */
    default boolean repeatable() {
        return false;
    }

    /**
     * Compiles one item's parameters into an immutable binding. Reject bad
     * content with {@link IllegalArgumentException}; the pipeline diagnoses
     * and skips that declaration without disabling its siblings.
     *
     * <p>The default keeps metadata-only implementations binary-compatible,
     * but explicitly rejects their use instead of pretending they execute.
     */
    default MechanicInstance parse(MechanicConfiguration configuration) {
        throw new UnsupportedOperationException("mechanic does not implement parsing");
    }

    /**
     * Creates one runtime per registration, never one per item or reload.
     * Do not mutate live content here. Register listeners through the returned
     * runtime and track other resources through the context.
     */
    default MechanicRuntime createRuntime(MechanicContext context) {
        return new MechanicRuntime() {};
    }
}
