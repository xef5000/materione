package dev.materione.plugin.escrow.runtime;

/**
 * The live, owner-thread state of one player that a tier-1 step reads and changes (gui-input-durability.md I1): the
 * window's slots, the cursor and the player's inventory. Everything behind it must be touched on the player's own
 * thread, in the one step; the Bukkit implementation is {@link BukkitLive}, a test uses plain lists.
 *
 * @param <T> one stack
 */
public interface Live<T> {

    /** The player's storage slots: the hotbar is 0 to 8 and the main inventory 9 to 35 (Bukkit's order). */
    int STORAGE = 36;

    /** The offhand, in {@link #bag}'s index space. */
    int OFFHAND = 40;

    /** The window's own slot (raw slot of the top inventory); null when empty. */
    T top(int slot);

    void top(int slot, T stack);

    /** The stack on the cursor; null when empty. */
    T cursor();

    void cursor(T stack);

    /** One slot of the player's inventory (0 to 35 storage, {@link #OFFHAND}); null when empty. */
    T bag(int index);

    void bag(int index, T stack);
}
