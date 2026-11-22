package dev.materione.api.protection;

import dev.materione.api.registry.SelfDescription;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * The SPI of the protection port (protection.md section 3): one provider's answers to "may this actor change this place?".
 * An adapter for a region or claim plugin implements it, and an addon registers its own through
 * {@link ProtectionApi#register}. Every question has a default that abstains, so a provider answers only the questions its
 * plugin can answer.
 *
 * <p><b>Contract.</b>
 * <ul>
 *   <li><b>Answer, do not act.</b> A method changes nothing in the world and sends nothing to the player; it is a pure read of
 *       the provider's own data, which makes a preview ("would this be allowed?") the same call as a check.
 *   <li><b>Abstain when not yours.</b> A place no region of the plugin covers is {@link ProtectionAnswer#abstain}, not allow:
 *       allow says "this plugin knows the place and permits it".
 *   <li><b>No actor.</b> An {@linkplain ProtectionActor#isNone() actor-less} question asks about the place alone ("may the
 *       world change this by itself?"); a provider answers deny for a place it protects from everyone but its members, and
 *       abstain for the rest.
 *   <li><b>Threads.</b> {@link #safety()} declares where the provider may be asked; the service honours it. A method that
 *       needs the actor's live {@code Player} reads {@link ProtectionActor#online()}, which is empty across a region
 *       boundary, and falls back to the offline record.
 *   <li><b>Failure.</b> A method that throws is a failed call: the service treats it as a deny for that one call and reports
 *       {@code MTR-0122}, rate-limited. It never crashes the server.
 * </ul>
 */
public interface ProtectionPort {

    /** The provider's display name, as the answers and the self-description show it ({@code WorldGuard}). */
    String name();

    /** What the provider is and what it covers, for the doctor, the logs and the editor. */
    SelfDescription describe();

    /** Where the provider may be asked. Cheap, and safe to read from any thread. The default is the owning region. */
    default ProtectionSafety safety() {
        return ProtectionSafety.OWNING_REGION;
    }

    /** May {@code actor} break the block at {@code location}? */
    default ProtectionAnswer canBreak(ProtectionActor actor, Location location) {
        return ProtectionAnswer.abstain(name());
    }

    /** May {@code actor} place a block, or a furniture cluster, at {@code location}? */
    default ProtectionAnswer canPlace(ProtectionActor actor, Location location) {
        return ProtectionAnswer.abstain(name());
    }

    /** May {@code actor} open or press the block or entity at {@code location}? */
    default ProtectionAnswer canInteract(ProtectionActor actor, Location location) {
        return ProtectionAnswer.abstain(name());
    }

    /** May {@code attacker} hurt {@code victim}? The location is the victim's; the attacker may be none. */
    default ProtectionAnswer canDamage(ProtectionActor attacker, Entity victim) {
        return ProtectionAnswer.abstain(name());
    }

    /** May {@code actor} use an item or an ability at {@code location} (shoot, cast)? */
    default ProtectionAnswer canUse(ProtectionActor actor, Location location) {
        return ProtectionAnswer.abstain(name());
    }
}
