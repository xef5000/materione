package dev.materione.plugin.view;

import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicContext;
import dev.materione.plugin.gui.Viewer;
import org.bukkit.entity.Player;

/**
 * What a view verb sees of the run it is in: the view services, the player, and the viewer record the run belongs to.
 * Shared by {@code view.open}, {@code view.close} and {@code view.back} so each of them is only its own verb
 * (logic.md §9 rule 2), and so "no open view" means the same thing in all of them (logic.md §5.2): a run whose record
 * has ended gets {@link Refused} with that reason, because runs belong to the player and outlive the window.
 *
 * @param record the run's viewer record; null when the run is not in a view or the verb did not ask for one
 */
public record ViewRun(ViewBridge.Services services, Viewer viewer, ViewerRecord record) {

    /** The verb cannot run; the message is its {@code FAILED} reason. */
    public static final class Refused extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public Refused(String reason) {
            super(reason, null, false, false);
        }
    }

    /** Resolves a run that needs the player only ({@code view.open}): it may start from an item, a block, a command or a closed window. */
    public static ViewRun ofPlayer(ViewBridge bridge, LogicContext context) {
        ViewBridge.Services services = bridge.services()
                .orElseThrow(() -> new Refused("the view runtime is not available"));
        Player player = context.facet(Facets.PLAYER).orElseThrow(() -> new Refused("event has no player"));
        return new ViewRun(services, Viewer.of(player), context.facet(ViewerRecord.FACET).orElse(null));
    }

    /**
     * Resolves a run that acts on its own open view ({@code view.back}, {@code view.close} with no {@code view:}): the
     * run must belong to a record that is still open and still the player's current one of its kind.
     */
    public static ViewRun ofOpenView(ViewBridge bridge, LogicContext context) {
        ViewRun run = ofPlayer(bridge, context);
        ViewerRecord record = run.record();
        if (record == null || !record.isOpen()
                || run.services().views().current(run.viewer().id(), record.kind())
                        .filter(current -> current == record).isEmpty()) {
            throw new Refused("no open view");
        }
        return run;
    }
}
