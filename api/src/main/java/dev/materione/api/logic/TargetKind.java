package dev.materione.api.logic;

import java.util.Locale;

/**
 * What an action can act on besides the run's own player (logic.md §39): the kinds an
 * {@link ActionDescription#targetable()} declares. {@link #PLAYER} is an online player; {@link #ENTITY} is any
 * other entity in a loaded world. A player is also an entity, so an action that declares {@code ENTITY} admits
 * players too; one that declares only {@code PLAYER} refuses a zombie (the action fails, it does not guess).
 */
public enum TargetKind {

    /** An online player. */
    PLAYER,

    /** Any entity: a mob, an armor stand, a projectile; a player too. */
    ENTITY;

    /** The lower-case word the wire and the self-description carry ({@code player}). */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
