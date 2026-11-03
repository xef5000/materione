package dev.materione.api.logic;

import org.bukkit.entity.Player;

/**
 * How a {@code prompt} step asks a player for a line of text and gets it back (logic.md §4.5, §6.6): the SPI
 * behind {@code method:}. The built-ins are {@code chat}, {@code sign} and {@code anvil}; the dialog method
 * arrives with dialogs (#6). An addon registers its own method under its namespace through
 * {@link LogicApi#inputMethods()}.
 *
 * <p>The run, the variable and the timeout belong to the prompt, not to the method. A method only puts a
 * question in front of the player and reports what they did:
 *
 * <ul>
 *   <li>{@link #ask} is called once, on the player's thread, after the prompt has hidden the player's window
 *       when {@link InputMethodDescription#closesWindow()} says so. It opens the method's UI or starts
 *       listening, and returns. It must not block. Throwing means the method could not start: the prompt
 *       reports it and the run takes the step's {@code else:}.
 *   <li>The method answers through the {@link Reply} it was given, exactly once, from any thread. The prompt
 *       hops to the player's thread before it settles the answer.
 *   <li>{@link #dismiss} is called once when the prompt ends, whatever ended it (an answer, a cancel word, a
 *       timeout, a quit), on the player's thread when the player is online. It undoes what {@code ask} opened.
 *       It must tolerate having nothing to undo.
 * </ul>
 *
 * <p>Parse is not the method's business: a {@code prompt} names its method at load, and an unknown name is
 * diagnosed there ({@code MTR-0081}).
 */
public interface InputMethod {

    /** The local name an author writes ({@code chat}, {@code sign}). */
    String id();

    /** The self-description: the parameter schema and whether the method closes the window. */
    InputMethodDescription describe();

    /** Whether the server can use this method now (for example a method that needs a plugin). */
    default Availability availability() {
        return Availability.AVAILABLE;
    }

    /**
     * Puts {@code prompt} in front of the player.
     *
     * @param player the player to ask; online and on their own thread
     * @param prompt the text the prompt shows, already rendered
     * @param reply where the answer goes; called exactly once
     * @throws IllegalStateException when the method cannot start for this player (the reason is reported)
     */
    void ask(Player player, String prompt, Reply reply);

    /** Undoes what {@link #ask} opened. Called once when the prompt ends; a no-op when nothing is open. */
    void dismiss(Player player);

    /** Where a method reports what the player did. */
    interface Reply {

        /** The player answered with {@code text}. An empty text is treated as no answer by the prompt. */
        void answer(String text);

        /** The player gave no answer (closed the editor, or the method gave up); {@code reason} is short and plain. */
        void decline(String reason);
    }
}
