package dev.materione.integrations.protection;

import dev.materione.api.protection.ProtectionAction;
import dev.materione.api.protection.ProtectionActor;
import dev.materione.api.protection.ProtectionAnswer;
import dev.materione.api.protection.ProtectionPort;
import dev.materione.api.protection.ProtectionSafety;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * A {@link ProtectionPort} over an adapter's {@link Backend}: it names the provider, declares where it may be asked, and routes
 * each of the port's five questions to the backend method that fits the actor (online, offline, nobody). Every adapter is one of
 * these, so the routing is written and tested once.
 */
public final class BackendPort implements ProtectionPort {

    private final String name;
    private final String description;
    private final ProtectionSafety safety;
    private final Backend backend;

    /**
     * @param name the provider's display name ({@code WorldGuard})
     * @param description one paragraph on what the adapter covers, for the self-description
     * @param safety where the plugin's API may be called
     * @param backend the adapter's logic
     */
    public BackendPort(String name, String description, ProtectionSafety safety, Backend backend) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = Objects.requireNonNull(description, "description");
        this.safety = Objects.requireNonNull(safety, "safety");
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription(name, description, List.of(), "2.0.0");
    }

    @Override
    public ProtectionSafety safety() {
        return safety;
    }

    @Override
    public ProtectionAnswer canBreak(ProtectionActor actor, Location location) {
        return route(ProtectionAction.BREAK, actor, location, null);
    }

    @Override
    public ProtectionAnswer canPlace(ProtectionActor actor, Location location) {
        return route(ProtectionAction.PLACE, actor, location, null);
    }

    @Override
    public ProtectionAnswer canInteract(ProtectionActor actor, Location location) {
        return route(ProtectionAction.INTERACT, actor, location, null);
    }

    @Override
    public ProtectionAnswer canDamage(ProtectionActor attacker, Entity victim) {
        return route(ProtectionAction.DAMAGE, attacker, victim.getLocation(), victim);
    }

    @Override
    public ProtectionAnswer canUse(ProtectionActor actor, Location location) {
        return route(ProtectionAction.USE, actor, location, null);
    }

    private ProtectionAnswer route(ProtectionAction action, ProtectionActor actor, Location location, Entity victim) {
        Decision decision;
        if (actor.online().isPresent()) {
            decision = backend.online(action, actor.online().get(), location, victim);
        } else if (actor.isNone()) {
            decision = backend.nobody(action, location, victim);
        } else {
            decision = backend.offline(action, actor.offline().orElseThrow(), location, victim);
        }
        return (decision == null ? Decision.abstain() : decision).answer(name);
    }
}
