package dev.materione.core.content;

import dev.materione.api.NamespacedId;
import dev.materione.api.registry.SelfDescription;
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
     * Parses one resource keyed in a file of this type into a typed
     * definition, resolving {@code template:} inheritance against the
     * passed template pool. Diamond inheritance is a parse failure (empty
     * result — the validator quarantines). Parsing never throws; fail-soft
     * is the pipeline's job (D6).
     */
    Optional<Object> parse(NamespacedId id, Map<String, Object> definition, Map<String, Map<String, Object>> templates);

    /**
     * Compiles one definition into pack output through {@link PackEmitter}.
     * The compiler calls this per definition, in graph order; hooks stay
     * deterministic (same definition + allocation → same output, D7).
     */
    void compile(Object definition, NamespacedId allocatedModelId, PackEmitter emitter);
}
