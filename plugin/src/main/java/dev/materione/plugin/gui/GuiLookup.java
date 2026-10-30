package dev.materione.plugin.gui;

import dev.materione.api.NamespacedId;
import dev.materione.core.runtime.RuntimeRegistries;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The live GUI with an id, read from the registries that are live <em>now</em>. Everything that
 * acts on a session asks here at the moment it acts and never holds a {@link RuntimeGuis.Live}, so a
 * live reload (gui.md §5) is seen by the very next click, tick or refresh while the session, which is
 * keyed by id, carries on.
 */
@FunctionalInterface
public interface GuiLookup {

    /** The live GUI, or empty when it is unknown or its last compile quarantined it. */
    Optional<RuntimeGuis.Live> find(NamespacedId id);

    /** The ids of every live GUI, for resolving a bare name. Empty for a lookup that cannot list. */
    default Collection<NamespacedId> ids() {
        return List.of();
    }

    /**
     * Resolves what an author wrote after {@code gui.open}: a qualified id ({@code shop:menu}) is
     * taken as written; a bare name ({@code menu}) means the GUI of that name in {@code near}'s pack
     * when there is one, else the only live GUI of that name. More than one candidate, or none, is empty:
     * nothing is guessed.
     *
     * @param near the GUI the reference was written in (its pack is preferred), or null
     */
    default Optional<NamespacedId> resolve(String written, NamespacedId near) {
        String text = written.trim();
        if (text.indexOf(':') >= 0) {
            try {
                return Optional.of(NamespacedId.parse(text)).filter(id -> find(id).isPresent());
            } catch (IllegalArgumentException malformed) {
                return Optional.empty();
            }
        }
        if (near != null) {
            NamespacedId sibling = NamespacedId.of(near.namespace(), text);
            if (find(sibling).isPresent()) {
                return Optional.of(sibling);
            }
        }
        List<NamespacedId> matches = ids().stream().filter(id -> id.value().equalsIgnoreCase(text)).toList();
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    /** The lookup over the live registries (re-read on every call, never cached). */
    static GuiLookup over(Supplier<RuntimeRegistries> live) {
        return new GuiLookup() {
            @Override
            public Optional<RuntimeGuis.Live> find(NamespacedId id) {
                return view().gui(id);
            }

            @Override
            public Collection<NamespacedId> ids() {
                return view().all().stream().map(RuntimeGuis.Live::id).toList();
            }

            private RuntimeGuis view() {
                RuntimeRegistries registries = live.get();
                RuntimeGuis view = registries == null ? null : registries.view(RuntimeGuis.KEY);
                return view == null ? RuntimeGuis.empty() : view;
            }
        };
    }
}
