package dev.materione.plugin.gui;

import dev.materione.plugin.gui.input.InputSlotPort;
import dev.materione.plugin.gui.list.GuiPages;
import dev.materione.plugin.gui.live.GuiLiveRefresh;
import dev.materione.plugin.gui.open.GuiLifecycle;
import dev.materione.plugin.gui.open.GuiOpener;
import dev.materione.plugin.view.ViewBridge;
import dev.materione.plugin.view.ViewKinds;
import dev.materione.plugin.view.ViewRefresher;
import dev.materione.plugin.view.ViewRegistry;
import java.util.Objects;
import java.util.Optional;

/**
 * How the GUI actions reach the GUI runtime. Actions are stateless singletons registered at boot, before content
 * loads and long before the runtime (which needs the live registries) exists, so they hold this bridge and the
 * runtime binds itself into it once it is built. An action that runs before that, or in a host with no GUI
 * runtime, fails soft with its reason instead of throwing.
 *
 * <p>It carries services only; the logic of an action is in the action, and the logic of opening,
 * closing and refreshing is in the collaborators it hands out.
 *
 * <p>The GUI is the first view kind (views.md §3.2), so the bridge also carries the {@link ViewBridge} the generic
 * view verbs ({@code view.open}, {@code view.close}, {@code view.back}) hold: binding the GUI runtime binds the
 * registry and the kinds it registered into, which is how those verbs reach it. A later kind's runtime registers its
 * {@link dev.materione.plugin.view.ViewKind} in the same {@link ViewKinds}.
 */
public final class GuiBridge {

    /** The collaborators an action needs. */
    public record Services(
            GuiLookup guis, ViewRegistry views, ViewKinds kinds, GuiOpener opener, GuiLifecycle lifecycle,
            GuiLiveRefresh refresh, InputSlotPort inputs, GuiPages pages, ViewRefresher refresher) {

        public Services {
            Objects.requireNonNull(guis, "guis");
            Objects.requireNonNull(views, "views");
            Objects.requireNonNull(kinds, "kinds");
            Objects.requireNonNull(opener, "opener");
            Objects.requireNonNull(lifecycle, "lifecycle");
            Objects.requireNonNull(refresh, "refresh");
            Objects.requireNonNull(inputs, "inputs");
            Objects.requireNonNull(pages, "pages");
            Objects.requireNonNull(refresher, "refresher");
        }
    }

    private final ViewBridge view = new ViewBridge();
    private volatile Services services;

    /** Binds the runtime. Called once, when the runtime is built; a second call replaces the first. */
    public void bind(Services services) {
        this.services = Objects.requireNonNull(services, "services");
        view.bind(new ViewBridge.Services(services.views(), services.kinds(), services.refresher()));
    }

    /** The bound services, or empty before the runtime exists. */
    public Optional<Services> services() {
        return Optional.ofNullable(services);
    }

    /** The bridge of the generic view verbs, bound with this one. */
    public ViewBridge view() {
        return view;
    }
}
