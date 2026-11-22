package dev.materione.integrations.protection;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.TownyPermission;
import com.palmergames.bukkit.towny.utils.CombatUtil;
import com.palmergames.bukkit.towny.utils.PlayerCacheUtil;
import dev.materione.api.protection.ProtectionAction;
import dev.materione.api.protection.ProtectionPort;
import dev.materione.api.protection.ProtectionSafety;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * The Towny adapter (protection.md section 9), written from Towny's public API: {@code PlayerCacheUtil#getCachePermission}
 * (build, destroy, switch, item use: the permission Towny's own listeners test for a player) and {@code CombatUtil#preventDamageCall}
 * for damage. Towny's cache is for a connected player, so a player known by id only gets no opinion. No actor: a claimed town
 * block is protected from the world's own changes, the wilderness is not covered.
 */
public final class TownyAdapter implements Backend {

    /** The plugin's name as its {@code plugin.yml} declares it. */
    public static final String PLUGIN = "Towny";

    /** What the adapter asks Towny. The real one is {@link Real}. */
    public interface Api {
        /** Whether Towny permits {@code player} the action ({@code BREAK}, {@code PLACE}, {@code INTERACT} or {@code USE}) at the place. */
        boolean permitted(ProtectionAction action, Player player, Location location);

        /** Whether Towny prevents {@code attacker} hurting {@code victim}. */
        boolean preventsDamage(Player attacker, Entity victim);

        /** Whether a town claims the block at {@code location}. */
        boolean claimed(Location location);
    }

    private final Api api;

    public TownyAdapter(Api api) {
        this.api = api;
    }

    /** The port over the real Towny; call only while it is enabled. */
    public static ProtectionPort create() {
        return port(new Real());
    }

    /** The port over {@code api}. */
    public static ProtectionPort port(Api api) {
        return new BackendPort(PLUGIN, "Asks Towny's build, destroy, switch and item-use permissions and its damage rules for a "
                + "player, and treats a claimed town block as protected from an actor-less change.",
                ProtectionSafety.OWNING_REGION, new TownyAdapter(api));
    }

    @Override
    public Decision online(ProtectionAction action, Player player, Location location, Entity victim) {
        if (action == ProtectionAction.DAMAGE) {
            return api.preventsDamage(player, victim) ? Decision.deny("Towny prevents the damage") : Decision.allow();
        }
        return Decision.of(api.permitted(action, player, location), "the town's permissions deny it");
    }

    @Override
    public Decision nobody(ProtectionAction action, Location location, Entity victim) {
        if (action == ProtectionAction.DAMAGE || action == ProtectionAction.USE) {
            return Decision.abstain();
        }
        return api.claimed(location) ? Decision.deny("the place is a claimed town block") : Decision.abstain();
    }

    /** The calls into Towny. */
    static final class Real implements Api {

        Real() {
            TownyAPI.getInstance(); // fails here, inside the integration guard, when Towny's API does not link
        }

        @Override
        public boolean permitted(ProtectionAction action, Player player, Location location) {
            Material material = switch (action) {
                case PLACE -> Material.STONE; // the material to be placed is not part of the question
                case USE -> player.getInventory().getItemInMainHand().getType();
                default -> location.getBlock().getType();
            };
            TownyPermission.ActionType type = switch (action) {
                case BREAK -> TownyPermission.ActionType.DESTROY;
                case PLACE -> TownyPermission.ActionType.BUILD;
                case INTERACT -> TownyPermission.ActionType.SWITCH;
                case USE, DAMAGE -> TownyPermission.ActionType.ITEM_USE;
            };
            return PlayerCacheUtil.getCachePermission(player, location, material, type);
        }

        @Override
        public boolean preventsDamage(Player attacker, Entity victim) {
            return CombatUtil.preventDamageCall(attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK);
        }

        @Override
        public boolean claimed(Location location) {
            return !TownyAPI.getInstance().isWilderness(location);
        }
    }
}
