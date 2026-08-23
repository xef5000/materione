package dev.materione.core.runtime;

import dev.materione.api.NamespacedId;
import dev.materione.core.blocks.BlockStateSpaces;
import java.util.List;

/**
 * One custom block as the world runtime serves it (runtime-systems §4): the
 * durable state slot its host blockstate encodes, plus placement policy.
 * Immutable; rebuilt wholesale by each apply (shadow-swap), never mutated.
 * The {@code slot} is the value the allocation table assigned under kind
 * {@code block:<host>} and is stable across reloads by construction (D7).
 */
public record RuntimeBlock(
        NamespacedId id,
        BlockStateSpaces.Technique technique,
        String host,              // vanilla host block id, lowercase
        int slot,                 // allocated state slot; -1 for TECHNICAL (PDC-identified)
        List<NamespacedId> drops,
        boolean blastResistant,
        String movePolicy) {      // keep | break

    public RuntimeBlock {
        drops = List.copyOf(drops);
    }

    /** True when placements of this block are identified through their blockstate slot. */
    public boolean allocatesState() {
        return BlockStateSpaces.allocatesState(technique);
    }
}
