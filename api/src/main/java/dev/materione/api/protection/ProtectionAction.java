package dev.materione.api.protection;

/**
 * What the actor wants to do at a place. The five questions of the port (protection.md section 3): each is one method of
 * {@link ProtectionPort}, and {@link ProtectionQuery} names one.
 */
public enum ProtectionAction {

    /** Remove or break the block at the location. */
    BREAK,
    /** Place or replace a block (or a furniture cluster) at the location. */
    PLACE,
    /** Open, press or otherwise use the block or entity at the location (a chest, a door, a lever). */
    INTERACT,
    /** Hurt the entity: the victim is on the query, the location is where it stands. */
    DAMAGE,
    /** Use an item or an ability at the location (shoot a projectile, spend a charge). */
    USE
}
