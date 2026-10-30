package dev.materione.plugin.gui;

import dev.materione.plugin.gui.live.GuiLiveRefresh;
import dev.materione.plugin.gui.open.GuiLifecycle;
import dev.materione.plugin.gui.open.GuiOpener;
import dev.materione.plugin.gui.session.GuiSessions;
import java.util.Objects;
import java.util.Optional;

/**
 * How the GUI actions reach the GUI runtime. Actions are stateless singletons registered at boot, before
 * content loads and long before the runtime (which needs the live registries) exists, so they hold this
 * bridge and the runtime binds itself into it once it is built. An action that runs before that, or in a
 * host with no GUI runtime, fails soft with its reason instead of throwing.
 *
 * <p>It carries services only; the logic of an action is in the action, and the logic of opening,
 * closing and refreshing is in the collaborators it hands out.
 */
public final class GuiBridge {

    /** The collaborators an action needs. */
    public record Services(
            GuiLookup guis, GuiSessions sessions, GuiOpener opener, GuiLifecycle lifecycle, GuiLiveRefresh refresh) {

        public Services {
            Objects.requireNonNull(guis, "guis");
            Objects.requireNonNull(sessions, "sessions");
            Objects.requireNonNull(opener, "opener");
            Objects.requireNonNull(lifecycle, "lifecycle");
            Objects.requireNonNull(refresh, "refresh");
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
