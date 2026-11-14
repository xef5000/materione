package dev.materione.core.pipeline.validate;

import dev.materione.core.pipeline.ContentResource;
import java.util.List;

/**
 * The content a resource's logic names by a static id: the {@code give_item: ruby} of a rule, the {@code open_gui}
 * of a GUI's click. Level 3 (reference resolution) checks each against the packs, so the pipeline and a
 * transaction's dry run find the same unresolved ones (codemode.md section 10, #363). What is a reference comes from
 * the self-description of the action or condition ({@link LogicVocabulary}); this module keeps no list of
 * parameter names.
 */
@FunctionalInterface
public interface StaticReferences {

    /** Names nothing: the validator of a host with no logic registries. */
    StaticReferences NONE = resource -> List.of();

    /**
     * One static reference.
     *
     * @param kind what the id names ({@code item}, {@code gui}), the vocabulary's word for it
     * @param written the id as authored (bare or qualified)
     * @param target the path of the value in the resource's definition, for the diagnostic's target
     * @param origin the action or condition the parameter belongs to ({@code give_item})
     */
    record Ref(String kind, String written, List<String> target, String origin) {}

    /** Every static reference in the resource's {@code behavior:} and {@code flows:}; none for a resource with no logic. */
    List<Ref> of(ContentResource resource);
}
