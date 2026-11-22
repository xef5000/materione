package dev.materione.integrations.protection;

import dev.materione.api.protection.ProtectionAction;
import dev.materione.api.protection.ProtectionPort;
import dev.materione.api.protection.ProtectionSafety;
import net.william278.husktowns.api.BukkitHuskTownsAPI;
import net.william278.husktowns.libraries.cloplib.operation.OperationType;
import net.william278.husktowns.claim.Position;
import org.bukkit.Location;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;

/**
 * The HuskTowns adapter (protection.md section 9), written from HuskTowns' public API: {@code isOperationAllowed} for an online
 * user, with the operation type that matches the question, and {@code isOperationAllowed(Position, OperationType)} for an
 * actor-less change. Its shape is HuskClaims', because both plugins share the operation model of the same library. HuskTowns takes
 * a connected user, so a player known by id only, and item use, get no opinion.
 */
public final class HuskTownsAdapter implements Backend {

    /** The plugin's name as its {@code plugin.yml} declares it. */
    public static final String PLUGIN = "HuskTowns";

    /** What the adapter asks HuskTowns: true is allowed. The real one is {@link Real}. */
    public interface Api {
        boolean allowed(ProtectionAction action, Player player, Location location, Entity victim);

        boolean allowedWithNoUser(ProtectionAction action, Location location);
    }

    private final Api api;

    public HuskTownsAdapter(Api api) {
        this.api = api;
    }

    /** The port over the real HuskTowns; call only while it is enabled. */
    public static ProtectionPort create() {
        return port(new Real());
    }

    /** The port over {@code api}. */
    public static ProtectionPort port(Api api) {
        return new BackendPort(PLUGIN, "Asks HuskTowns whether a town claim allows the operation: block break, place and "
                + "interact, container open and player damage, for a player or for no one.", ProtectionSafety.OWNING_REGION,
                new HuskTownsAdapter(api));
    }

    @Override
    public Decision online(ProtectionAction action, Player player, Location location, Entity victim) {
        if (action == ProtectionAction.USE) {
            return Decision.abstain();
        }
        return Decision.of(api.allowed(action, player, location, victim), "a town claim does not allow it");
    }

    @Override
    public Decision nobody(ProtectionAction action, Location location, Entity victim) {
        if (action == ProtectionAction.USE || action == ProtectionAction.DAMAGE) {
            return Decision.abstain();
        }
        return Decision.of(api.allowedWithNoUser(action, location), "a town claim does not allow it");
    }

    /** The calls into HuskTowns. */
    static final class Real implements Api {

        Real() {
            BukkitHuskTownsAPI.getInstance(); // fails here, inside the integration guard, when HuskTowns is not ready
        }

        @Override
        public boolean allowed(ProtectionAction action, Player player, Location location, Entity victim) {
            BukkitHuskTownsAPI api = BukkitHuskTownsAPI.getInstance();
            Position position = api.getPosition(location);
            return api.isOperationAllowed(api.getOnlineUser(player), operation(action, location, victim), position);
        }

        @Override
        public boolean allowedWithNoUser(ProtectionAction action, Location location) {
            BukkitHuskTownsAPI api = BukkitHuskTownsAPI.getInstance();
            return api.isOperationAllowed(api.getPosition(location), operation(action, location, null));
        }

        private static OperationType operation(ProtectionAction action, Location location, Entity victim) {
            return switch (action) {
                case BREAK -> OperationType.BLOCK_BREAK;
                case PLACE -> OperationType.BLOCK_PLACE;
                case INTERACT -> location.getBlock().getState(false) instanceof Container
                        ? OperationType.CONTAINER_OPEN : OperationType.BLOCK_INTERACT;
                case DAMAGE -> victim instanceof Player ? OperationType.PLAYER_DAMAGE_PLAYER
                        : victim instanceof Monster ? OperationType.PLAYER_DAMAGE_MONSTER
                        : OperationType.PLAYER_DAMAGE_ENTITY;
                case USE -> OperationType.BLOCK_INTERACT;
            };
        }
    }
}
