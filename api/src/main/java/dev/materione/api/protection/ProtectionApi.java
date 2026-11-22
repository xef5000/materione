package dev.materione.api.protection;

import java.util.List;
import java.util.concurrent.CompletionStage;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * The service behind the port (protection.md section 3), reached through
 * {@link dev.materione.api.logic.LogicApi#protection()}: register a provider, ask every provider at once, read which are
 * present.
 *
 * <p><b>Combining.</b> {@link #ask} puts the question to every provider and merges the answers: any deny is a deny (the first
 * denying provider is named), else any allow is an allow, else the answer is an abstain. Which questions a verb asks, and
 * whether the event path also runs, is the {@link ProtectionMode}.
 *
 * <p><b>Threads.</b> {@link #ask} must be called on the thread that owns the query's location (it checks, and fails closed
 * with {@code MTR-0122} when it is not), because providers read that location. {@link #askAt} may be called from anywhere: it
 * hops to the owning region first.
 */
public interface ProtectionApi {

    /** The mode the server runs in ({@code protection_mode}). */
    ProtectionMode mode();

    /** The providers present now, in the order they are asked, with what each declared. */
    List<ProviderInfo> providers();

    /**
     * Adds {@code port} to the providers asked.
     *
     * @throws IllegalStateException when {@code owner} is disabled
     * @throws IllegalArgumentException when the provider can only be asked on the main thread and the server is Folia
     *         ({@code MTR-0124} is also published)
     */
    Registration register(Plugin owner, ProtectionPort port);

    /** Puts {@code query} to every provider, on the calling thread, which must own the location. */
    ProtectionAnswer ask(ProtectionQuery query);

    /** Puts {@code query} to every provider on the thread that owns its location, hopping when this one does not. */
    CompletionStage<ProtectionAnswer> askAt(ProtectionQuery query);

    /** May {@code actor} break the block at {@code location}? Calling thread owns the location. */
    default ProtectionAnswer canBreak(ProtectionActor actor, Location location) {
        return ask(ProtectionQuery.of(ProtectionAction.BREAK, actor, location));
    }

    /** May {@code actor} place a block at {@code location}? Calling thread owns the location. */
    default ProtectionAnswer canPlace(ProtectionActor actor, Location location) {
        return ask(ProtectionQuery.of(ProtectionAction.PLACE, actor, location));
    }

    /** May {@code actor} interact at {@code location}? Calling thread owns the location. */
    default ProtectionAnswer canInteract(ProtectionActor actor, Location location) {
        return ask(ProtectionQuery.of(ProtectionAction.INTERACT, actor, location));
    }

    /** May {@code attacker} hurt {@code victim}? Calling thread owns the victim. */
    default ProtectionAnswer canDamage(ProtectionActor attacker, Entity victim) {
        return ask(ProtectionQuery.damage(attacker, victim));
    }

    /** May {@code actor} use an item or ability at {@code location}? Calling thread owns the location. */
    default ProtectionAnswer canUse(ProtectionActor actor, Location location) {
        return ask(ProtectionQuery.of(ProtectionAction.USE, actor, location));
    }

    /**
     * A provider as the self-description lists it.
     *
     * @param name the provider's name
     * @param owner the plugin that registered it
     * @param safety where it may be asked
     * @param description one paragraph on what it covers
     */
    record ProviderInfo(String name, String owner, ProtectionSafety safety, String description) {}

    /** A provider registration. Closing it is idempotent. */
    interface Registration extends AutoCloseable {

        @Override
        void close();
    }
}
