package dev.materione.integrations.protection;

import dev.materione.api.protection.ProtectionAction;
import dev.materione.api.protection.ProtectionPort;
import dev.materione.api.protection.ProtectionSafety;
import net.william278.huskclaims.api.BukkitHuskClaimsAPI;
import net.william278.huskclaims.libraries.cloplib.operation.OperationType;
import net.william278.huskclaims.position.Position;
import org.bukkit.Location;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;

/**
 * The HuskClaims adapter (protection.md section 9), written from HuskClaims' public API: {@code isOperationAllowed} for an online
 * user, with the operation type that matches the question (block break, block place, block or container interact, and the
 * three player-damage kinds), and {@code isOperationAllowed(Position, OperationType)}, HuskClaims' own "operation with no user",
 * for an actor-less change. A claim at the place decides; where none covers it HuskClaims allows the operation, so the answer is
 * allow. HuskClaims takes a connected user, so a player known by id only, and item use, get no opinion.
 */
public final class HuskClaimsAdapter implements Backend {

    /** The plugin's name as its {@code plugin.yml} declares it. */
    public static final String PLUGIN = "HuskClaims";

    /** What the adapter asks HuskClaims: true is allowed. The real one is {@link Real}. */
    public interface Api {
        boolean allowed(ProtectionAction action, Player player, Location location, Entity victim);

        boolean allowedWithNoUser(ProtectionAction action, Location location);
    }

    private final Api api;

    public HuskClaimsAdapter(Api api) {
        this.api = api;
    }

    /** The port over the real HuskClaims; call only while it is enabled. */
    public static ProtectionPort create() {
        return port(new Real());
    }

    /** The port over {@code api}. */
    public static ProtectionPort port(Api api) {
        return new BackendPort(PLUGIN, "Asks HuskClaims whether a claim allows the operation: block break, place and interact, "
                + "container open and player damage, for a player or for no one.", ProtectionSafety.OWNING_REGION,
                new HuskClaimsAdapter(api));
    }

    @Override
    public Decision online(ProtectionAction action, Player player, Location location, Entity victim) {
        if (action == ProtectionAction.USE) {
            return Decision.abstain();
        }
        return Decision.of(api.allowed(action, player, location, victim), "a claim does not allow it");
    }

    @Override
    public Decision nobody(ProtectionAction action, Location location, Entity victim) {
        if (action == ProtectionAction.USE || action == ProtectionAction.DAMAGE) {
            return Decision.abstain();
        }
        return Decision.of(api.allowedWithNoUser(action, location), "a claim does not allow it");
    }

    /** The calls into HuskClaims. */
    static final class Real implements Api {

        Real() {
            BukkitHuskClaimsAPI.getInstance(); // fails here, inside the integration guard, when HuskClaims is not ready
        }

        @Override
        public boolean allowed(ProtectionAction action, Player player, Location location, Entity victim) {
            BukkitHuskClaimsAPI api = BukkitHuskClaimsAPI.getInstance();
            Position position = api.getPosition(location);
            return api.isOperationAllowed(api.getOnlineUser(player), operation(action, location, victim), position);
        }

        @Override
        public boolean allowedWithNoUser(ProtectionAction action, Location location) {
            BukkitHuskClaimsAPI api = BukkitHuskClaimsAPI.getInstance();
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
