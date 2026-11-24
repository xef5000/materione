package dev.materione.plugin.view;

import java.util.Objects;
import java.util.Optional;

/**
 * How the view verbs reach the view runtime. Actions are stateless singletons registered at boot, before content
 * loads and long before the runtime (which needs the live registries) exists, so they hold this bridge and the
 * runtime binds itself into it once it is built. An action that runs before that, or in a host with no view runtime,
 * fails soft with its reason instead of throwing.
 *
 * <p>It carries services only; the logic of an action is in the action, and the logic of opening, closing and
 * rendering a view is in the {@link ViewKind} that owns it.
 */
public final class ViewBridge {

    /** The collaborators a view verb needs. */
    public record Services(ViewRegistry views, ViewKinds kinds, ViewRefresher refresher) {

        public Services {
            Objects.requireNonNull(views, "views");
            Objects.requireNonNull(kinds, "kinds");
            Objects.requireNonNull(refresher, "refresher");
        }
    }

    private volatile Services services;

    /** Binds the runtime. Called once, when the runtime is built; a second call replaces the first. */
    public void bind(Services services) {
        this.services = Objects.requireNonNull(services, "services");
    }

    /** The bound services, or empty before the runtime exists. */
    public Optional<Services> services() {
        return Optional.ofNullable(services);
    }
}
