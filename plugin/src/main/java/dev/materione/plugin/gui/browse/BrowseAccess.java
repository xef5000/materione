package dev.materione.plugin.gui.browse;

import java.util.List;

/**
 * The browser's permission nodes (M6). Two, because seeing the catalogue and taking from it are different powers:
 *
 * <ul>
 *   <li>{@link #OPEN} ({@code materione.browse}) opens the browser, with {@code /materione browse} (and its
 *       {@code browser} alias) and its search. Default: operators.</li>
 *   <li>{@link #TAKE} ({@code materione.browse.get}) takes items from it: a click or a shift-click hands the item to the
 *       player. Without it the browser still lists and searches, and a take is refused with
 *       {@link #TAKE_DENIED}. Default: operators.</li>
 * </ul>
 *
 * <p>Both nodes are declared at enable with the operator default (as {@code materione.give} is); grant them to players
 * through the permissions plugin. Pure constants with no server types, so the nodes are testable headless.
 */
public final class BrowseAccess {

    /** Opens the browser ({@code /materione browse [query]}). */
    public static final String OPEN = "materione.browse";

    /** Takes items out of the browser (a click or a shift-click). */
    public static final String TAKE = "materione.browse.get";

    /** Shown to a player who clicks an entry without {@link #TAKE}. */
    public static final String TAKE_DENIED = "You need materione.browse.get to take items from the browser.";

    /** Shown to a player who opens the browser without {@link #OPEN}. */
    public static final String OPEN_DENIED = "no permission (materione.browse)";

    private BrowseAccess() {}

    /** Every node this surface declares, in declaration order. */
    public static List<String> nodes() {
        return List.of(OPEN, TAKE);
    }
}
