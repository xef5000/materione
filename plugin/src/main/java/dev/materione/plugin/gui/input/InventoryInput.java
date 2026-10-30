package dev.materione.plugin.gui.input;

import dev.materione.plugin.gui.GuiMenu;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.gui.session.GuiSession;
import dev.materione.plugin.gui.session.GuiSessions;
import java.util.Optional;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

/**
 * The adapter from a Bukkit click to the router (logic.md §9 rule 1): reads the event, classifies
 * the click, checks that the window's session is still the player's current one, and calls
 * {@link ClickRouter}. A window whose session has ended (it was replaced, or closed a tick ago) is
 * stale and its clicks are ignored; the policy table has already cancelled them.
 */
public final class InventoryInput implements GuiInput {

    private final GuiSessions sessions;
    private final ClickRouter router;

    public InventoryInput(GuiSessions sessions, ClickRouter router) {
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
                router.route(new GuiClick(viewer.get(), menu.session(), event.getSlot(), click.kind(), click.key())));
    }

    @Override
    public void outside(GuiMenu menu, InventoryClickEvent event) {
        Optional<Viewer> viewer = viewerOf(menu, event);
        if (viewer.isEmpty()) {
            return;
        }
        ClickClassifier.outside(event.getClick())
                .ifPresent(kind -> router.outside(viewer.get(), menu.session(), kind));
    }

    private Optional<Viewer> viewerOf(GuiMenu menu, InventoryClickEvent event) {
        GuiSession session = menu.session();
        if (!(event.getWhoClicked() instanceof Player player)
                || !sessions.current(player.getUniqueId()).filter(current -> current == session).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(Viewer.of(player));
    }
}
