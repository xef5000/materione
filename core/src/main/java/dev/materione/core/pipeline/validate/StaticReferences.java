package dev.materione.core.pipeline.validate;

import dev.materione.api.content.ViewFacet;
import dev.materione.core.pipeline.ContentResource;
import java.util.List;
import java.util.Map;

/**
 * The content a resource's logic names by a static id: the {@code give_item: ruby} of a rule, the {@code view.open}
 * of a GUI's click. Level 3 (reference resolution) checks each against the packs, so the pipeline and a
 * transaction's dry run find the same unresolved ones (codemode.md section 10, #363). What is a reference comes from
 * the self-description of the action or condition ({@link LogicVocabulary}); this module keeps no list of
 * parameter names.
 */
@FunctionalInterface
public interface StaticReferences {

    /** Names nothing: the validator of a host with no logic registries. */
    StaticReferences NONE = resource -> List.of();

    /** The reference kind that names a view of any kind ({@code view.open}'s {@code view:}; views.md section 9). */
    String VIEW = "view";

    /**
     * One static reference.
     *
     * @param kind what the id names ({@code item}, {@code gui}, {@code view}), the vocabulary's word for it
     * @param written the id as authored (bare or qualified)
     * @param target the path of the value in the resource's definition, for the diagnostic's target
     * @param origin the action or condition the parameter belongs to ({@code give_item})
     * @param viewKind for a {@link #VIEW} reference, the view kind the step restricts the id to with its own
     *         {@code kind:} (views.md section 16.2, V0-1); null when it names none
     */
    record Ref(String kind, String written, List<String> target, String origin, String viewKind) {

        public Ref(String kind, String written, List<String> target, String origin) {
            this(kind, written, target, origin, null);
        }
    }

    /** Every static reference in the resource's {@code behavior:} and {@code flows:}; none for a resource with no logic. */
    List<Ref> of(ContentResource resource);

    /**
     * The content types that are view kinds, by type name, with their facet (views.md section 9): what a reference of
     * kind {@link #VIEW} resolves against, so that {@code view.open} finds a GUI, and later a dialog, without this
     * module keeping a list of kinds. Empty for a host that does not say, in which case a {@code view} reference is not
     * judged.
     */
    default Map<String, ViewFacet> viewKinds() {
        return Map.of();
    }
}
