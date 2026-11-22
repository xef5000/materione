package dev.materione.integrations.protection;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.domains.Association;
import com.sk89q.worldguard.protection.association.Associables;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import dev.materione.api.protection.ProtectionAction;
import dev.materione.api.protection.ProtectionPort;
import dev.materione.api.protection.ProtectionSafety;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * The WorldGuard adapter (protection.md section 9), written from WorldGuard's public API: {@code WorldGuardPlugin#createProtectionQuery}
 * for an online player (its bypass permission, its build flags, its PvP and entity flags, exactly what WorldGuard applies to the
 * player's own action), a {@code RegionQuery} with the offline player wrapped by {@code wrapOfflinePlayer} for a player known by
 * id, and a {@code RegionQuery} with a constant non-member association for no actor ("may the world change this by itself?":
 * a region that denies non-members denies it, a region that allows everyone allows it).
 *
 * <p>Answers allow or deny for every place, because WorldGuard has no "not covered": a place with no region is allowed by its
 * defaults. Damage by no one, or by an offline player, abstains: WorldGuard's damage rules are about a player's blow.
 */
public final class WorldGuardAdapter implements Backend {

    /** The plugin's name as its {@code plugin.yml} declares it. */
    public static final String PLUGIN = "WorldGuard";

    /** What the adapter asks WorldGuard: true is allowed. The real one is {@link Real}; a test supplies its own. */
    public interface Api {
        boolean online(ProtectionAction action, Player player, Location location, Entity victim);

        boolean offline(ProtectionAction action, OfflinePlayer player, Location location);

        boolean nobody(ProtectionAction action, Location location);
    }

    private static final String DENIED = "a region's flags or membership deny it";

    private final Api api;

    public WorldGuardAdapter(Api api) {
        this.api = api;
    }

    /** The port over the real WorldGuard; call only while WorldGuard is enabled. */
    public static ProtectionPort create() {
        return port(new Real());
    }

    /** The port over {@code api}. */
    public static ProtectionPort port(Api api) {
        return new BackendPort(PLUGIN, "Asks WorldGuard's protection query: build, break, place and interact flags, region "
                + "membership, the bypass permission, and the PvP and entity-damage flags.", ProtectionSafety.OWNING_REGION,
                new WorldGuardAdapter(api));
    }

    @Override
    public Decision online(ProtectionAction action, Player player, Location location, Entity victim) {
        return Decision.of(api.online(action, player, location, victim), DENIED);
    }

    @Override
    public Decision offline(ProtectionAction action, OfflinePlayer player, Location location, Entity victim) {
        return action == ProtectionAction.DAMAGE ? Decision.abstain()
                : Decision.of(api.offline(action, player, location), DENIED);
    }

    @Override
    public Decision nobody(ProtectionAction action, Location location, Entity victim) {
        return action == ProtectionAction.DAMAGE ? Decision.abstain() : Decision.of(api.nobody(action, location), DENIED);
    }

    /** The calls into WorldGuard. */
    static final class Real implements Api {

        Real() {
            WorldGuardPlugin.inst(); // fails here, inside the integration guard, when WorldGuard's API does not link
        }

        @Override
        public boolean online(ProtectionAction action, Player player, Location location, Entity victim) {
            var query = WorldGuardPlugin.inst().createProtectionQuery();
            return switch (action) {
                case BREAK -> query.testBlockBreak(player, location.getBlock());
                case PLACE -> query.testBlockPlace(player, location, Material.STONE);
                case INTERACT, USE -> query.testBlockInteract(player, location.getBlock());
                case DAMAGE -> query.testEntityDamage(player, victim);
            };
        }

        @Override
        public boolean offline(ProtectionAction action, OfflinePlayer player, Location location) {
            LocalPlayer wrapped = WorldGuardPlugin.inst().wrapOfflinePlayer(player);
            return query().testBuild(BukkitAdapter.adapt(location), wrapped, flag(action));
        }

        @Override
        public boolean nobody(ProtectionAction action, Location location) {
            return query().testBuild(BukkitAdapter.adapt(location), Associables.constant(Association.NON_MEMBER),
                    flag(action));
        }

        private static RegionQuery query() {
            return WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        }

        private static StateFlag flag(ProtectionAction action) {
            return switch (action) {
                case BREAK -> Flags.BLOCK_BREAK;
                case PLACE -> Flags.BLOCK_PLACE;
                case INTERACT, USE, DAMAGE -> Flags.INTERACT;
            };
        }
    }
}
