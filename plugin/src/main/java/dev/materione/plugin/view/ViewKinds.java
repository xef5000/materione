package dev.materione.plugin.view;

import dev.materione.api.NamespacedId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The view kinds the runtime knows, by content type name (views.md §3.2). {@code view.open} resolves its
 * {@code view:} reference here, across every kind, so the verb never names a kind: a dialog or a map canvas that
 * registers a {@link ViewKind} is reachable from {@code view.open} with no change to the verb.
 */
public final class ViewKinds {

    /** A resolved reference: the kind that owns the definition and the definition's id. */
    public record Resolved(ViewKind kind, NamespacedId id) {}

    /** The outcome of resolving a reference: one match, or why not. */
    public record Resolution(Resolved found, String problem) {

        static Resolution of(Resolved found) {
            return new Resolution(found, null);
        }

        static Resolution failed(String problem) {
            return new Resolution(null, problem);
        }

        public boolean ok() {
            return found != null;
        }
    }

    private final ConcurrentHashMap<String, ViewKind> kinds = new ConcurrentHashMap<>();

    /** Registers a kind; a second registration of the same content type replaces the first. */
    public void register(ViewKind kind) {
        kinds.put(Objects.requireNonNull(kind, "kind").contentType(), kind);
    }

    /** The kind rendering a content type, if one is registered. */
    public Optional<ViewKind> of(String contentType) {
        return Optional.ofNullable(kinds.get(contentType));
    }

    /** Whether {@code contentType} names a registered {@code predicate} kind ({@link ViewKind#predicate()}). */
    public boolean predicate(String contentType) {
        ViewKind kind = kinds.get(contentType);
        return kind != null && kind.predicate();
    }

    /** Every registered kind. */
    public List<ViewKind> all() {
        return List.copyOf(kinds.values());
    }

    /**
     * Resolves a written reference across every kind (views.md §3.6). A reference that matches one definition of one
     * kind resolves; one that matches definitions of several kinds is ambiguous and is refused, because nothing is
     * guessed; one that matches none says so.
     *
     * @param near the definition the reference was written in, or null
     * @param nearKind the kind of {@code near}: its kind is tried first, so a bare name written in a GUI means the GUI
     *     of that name beside it before it means a dialog elsewhere
     */
    public Resolution resolve(String written, NamespacedId near, String nearKind) {
        return resolve(written, near, nearKind, null);
    }

    /**
     * As {@link #resolve(String, NamespacedId, String)}, with the kind named: the switch for an id that two kinds
     * share (a GUI `shop` and a dialog `shop` of one pack), which no spelling of the id alone can tell apart.
     *
     * @param onlyKind the content type name of the kind to look in ({@code gui}, {@code dialog}), or null for every kind
     */
    public Resolution resolve(String written, NamespacedId near, String nearKind, String onlyKind) {
        if (onlyKind != null && !kinds.containsKey(onlyKind)) {
            return Resolution.failed("no view kind '" + onlyKind + "'; the kinds are " + kinds.keySet().stream().sorted().toList());
        }
        var matches = new ArrayList<Resolved>();
        for (ViewKind kind : kinds.values()) {
            if (onlyKind != null && !kind.contentType().equals(onlyKind)) {
                continue;
            }
            kind.resolve(written, near).ifPresent(id -> matches.add(new Resolved(kind, id)));
        }
        if (matches.isEmpty()) {
            return Resolution.failed("no live " + (onlyKind != null ? onlyKind : "view") + " matches '" + written + "'");
        }
        if (matches.size() > 1 && nearKind != null) {
            // a sibling of the same kind in the same pack outranks the rest, as a bare name always has
            List<Resolved> sibling = matches.stream()
                    .filter(match -> match.kind().contentType().equals(nearKind)
                            && near != null && match.id().namespace().equals(near.namespace()))
                    .toList();
            if (sibling.size() == 1) {
                return Resolution.of(sibling.get(0));
            }
        }
        if (matches.size() > 1) {
            return Resolution.failed("'" + written + "' names more than one view (" + matches.stream()
                    .map(match -> match.kind().contentType() + " " + match.id()).sorted().toList()
                    + "); write the pack and id, or the kind: beside the view:");
        }
        return Resolution.of(matches.get(0));
    }
}
