package dev.materione.api.logic;

import org.bukkit.entity.Player;

/**
 * The facets every logic host agrees on. A runtime adds the ones it alone can fill (the GUI
 * session, a list entry) next to the type that backs them; these are the ones an action written
 * against the public API can rely on.
 *
 * <p>This class names Bukkit types, so the Bukkit-free engine never loads it; the engine reads the
 * facets it needs from the pure types that own them (for example {@link Cancellation#FACET}).
 */
public final class Facets {

    /**
     * The player the run belongs to (logic.md §5.2: runs belong to the player). Absent for runs with
     * no player cause (an explosion breaking a custom block); an action that needs one returns
     * {@link ActionOutcome#failed}.
     */
    public static final ContextFacet<Player> PLAYER = ContextFacet.of("materione:player", Player.class);

    /** The cancellation handle of the event that fired the rule; see {@link Cancellation}. */
    public static final ContextFacet<Cancellation> CANCELLATION = Cancellation.FACET;

    private Facets() {}
}
