package dev.materione.plugin.gui;

import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicContext;
import dev.materione.plugin.gui.session.GuiViewerState;
import dev.materione.plugin.view.ViewerRecord;
import org.bukkit.entity.Player;

/**
 * What a GUI action sees of the run it is in: the GUI services, the player, and the session the run
 * belongs to. Shared by the four GUI actions so each of them is only its own verb (logic.md §9 rule 2),
 * and so "no open gui" means the same thing in all of them (logic.md §5.2): a run whose session has ended
 * gets {@link Refused} with that reason, because runs belong to the player and outlive the window.
 *
 * @param record the run's viewer record, open; null when the action did not ask for one
 */
public record GuiRun(GuiBridge.Services services, Viewer viewer, ViewerRecord record) {

    /** The action cannot run; the message is its {@code FAILED} reason. */
    public static final class Refused extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Refused(String reason) {
            super(reason, null, false, false);
        }
    }

    /**
     * Resolves a run that needs the player only: it may start from an item, a
     * block, a command or a closed window.
     */
    public static GuiRun ofPlayer(GuiBridge bridge, LogicContext context) {
        GuiBridge.Services services = bridge.services()
                .orElseThrow(() -> new Refused("the GUI runtime is not available"));
        Player player = context.facet(Facets.PLAYER).orElseThrow(() -> new Refused("event has no player"));
        return new GuiRun(services, Viewer.of(player), context.facet(ViewerRecord.FACET).orElse(null));
    }

    /**
     * Resolves a run that acts on its own open GUI window ({@code gui.refresh}, {@code gui.page},
     * {@code gui.slot_item}): the run must belong to a GUI viewer record that is still open and still the player's.
     */
    public static GuiRun ofOpenSession(GuiBridge bridge, LogicContext context) {
        GuiRun run = ofPlayer(bridge, context);
        ViewerRecord session = run.record();
        if (session == null || !session.isOpen()
                || !GuiViewerState.KIND.equals(session.kind())
                || run.services().views().current(run.viewer().id(), GuiViewerState.KIND)
                        .filter(current -> current == session).isEmpty()) {
            throw new Refused("no open gui");
        }
        return run;
    }
}
