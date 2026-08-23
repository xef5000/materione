package dev.materione.api.mechanics;

import dev.materione.api.registry.SelfDescription;

/**
 * One mechanic in the {@code mechanics} registry (runtime-systems §2.3,
 * plugin.md §3) — the public addon seam. Built-ins and addons register
 * through the identical path; entries are self-described so the web editor
 * renders parameter forms from the description alone.
 *
 * <p>The interface carries identity only: execution is wired natively per
 * mechanic (listeners resolving the live registries at event entry). A
 * future shared execution SPI grows here without breaking implementors.
 */
public interface Mechanic {

    /** The mechanic's name as used in item YAML ({@code - soulbound: {}}). */
    String name();

    /** Editor/doc metadata: display name, summary, parameter schema. */
    SelfDescription describe();
}
