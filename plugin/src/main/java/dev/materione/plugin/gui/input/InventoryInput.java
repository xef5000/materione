package dev.materione.plugin.gui.input;

import dev.materione.plugin.gui.GuiMenu;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.view.ViewerRecord;
import dev.materione.plugin.view.ViewRegistry;
import java.util.Optional;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import dev.materione.plugin.gui.session.GuiViewerState;

/**
 * The adapter from a Bukkit click to the router (logic.md §9 rule 1): reads the event, classifies
 * the click, checks that the window's session is still the player's current one, and calls
 * {@link ClickRouter}. A window whose session has ended (it was replaced, or closed a tick ago) is
 * stale and its clicks are ignored; the policy table has already cancelled them.
 */
public final class InventoryInput implements GuiInput {

    private final ViewRegistry sessions;
    private final ClickRouter router;

    public InventoryInput(ViewRegistry sessions, ClickRouter router) {
        this.sessions = sessions;
        this.router = router;
    }

    @Override
    public void click(GuiMenu menu, InventoryClickEvent event) {
        Optional<Viewer> viewer = viewerOf(menu, event);
        if (viewer.isEmpty()) {
            return;
        }
        ClickClassifier.classify(event.getClick(), event.getHotbarButton()).ifPresent(click ->
                router.route(new GuiClick(viewer.get(), menu.record(), event.getSlot(), click.kind(), click.key())));
    }

    @Override
    public void outside(GuiMenu menu, InventoryClickEvent event) {
        Optional<Viewer> viewer = viewerOf(menu, event);
        if (viewer.isEmpty()) {
            return;
        }
        ClickClassifier.outside(event.getClick())
                .ifPresent(kind -> router.outside(viewer.get(), menu.record(), kind));
    }

    private Optional<Viewer> viewerOf(GuiMenu menu, InventoryClickEvent event) {
        ViewerRecord session = menu.record();
        if (!(event.getWhoClicked() instanceof Player player)
                || !sessions.current(player.getUniqueId(), GuiViewerState.KIND).filter(current -> current == session).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(Viewer.of(player));
    }
}
