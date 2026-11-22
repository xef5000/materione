package dev.materione.api.content;

import java.util.Locale;
import java.util.Objects;

/**
 * The view facet of a content type's self-description (views.md §9, D5): what makes a content type a <em>view kind</em>,
 * a type whose resources render state for viewers. It is how the editor, the SDK generator and the open-reference
 * check learn that a type is a view kind, with no list of kinds written anywhere else.
 *
 * @param viewerRule how the kind forms its viewers (views.md §2, §3.5)
 * @param rerenderDefault whether a change to a view's reads re-renders its viewers on its own, absent a {@code rerender:}
 *     key in the file (views.md §5.5)
 * @param supportsTarget whether {@code view.open} and {@code view.close} may attach or detach another player with
 *     {@code target:} (views.md §3.6; row V4)
 * @param supportsKey whether the kind can be bound to a state key with {@code key:} (views.md §6.2; row V5)
 */
public record ViewFacet(ViewerRule viewerRule, RerenderPolicy rerenderDefault, boolean supportsTarget,
        boolean supportsKey) {

    /** How a kind forms its viewers (views.md §2). */
    public enum ViewerRule {
        /** A verb attaches the viewer ({@code view.open}); a close detaches it. The container GUI, the dialog. */
        OPENED,
        /** Continuously: every player for whom a condition holds. The HUD. No verb opens it. */
        PREDICATE,
        /** Players in range of a placement. Reserved for the map canvas (#453). */
        SPATIAL;

        /** The value the editor and the SDK read: {@code opened}, {@code predicate}, {@code spatial}. */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Whether a change to a view's reads re-renders its viewers on its own (views.md §5.5). */
    public enum RerenderPolicy {
        ON_CHANGE,
        OFF;

        /** The value a file writes in {@code rerender:}: {@code on_change}, {@code off}. */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public ViewFacet {
        Objects.requireNonNull(viewerRule, "viewerRule");
        Objects.requireNonNull(rerenderDefault, "rerenderDefault");
    }

    /** Whether {@code view.open} may open a view of this kind: its viewers are {@code opened}, not formed by a condition. */
    public boolean openable() {
        return viewerRule == ViewerRule.OPENED;
    }

    /** The view kinds among {@code types}, by content type name, in the types' order. */
    public static java.util.Map<String, ViewFacet> byType(java.util.Collection<? extends ContentType> types) {
        var out = new java.util.LinkedHashMap<String, ViewFacet>();
        for (ContentType type : types) {
            type.view().ifPresent(facet -> out.put(type.name(), facet));
        }
        return java.util.Collections.unmodifiableMap(out);
    }
}
