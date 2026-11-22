package dev.materione.integrations.protection;

import dev.materione.api.protection.ProtectionAction;
import dev.materione.api.protection.ProtectionPort;
import dev.materione.api.protection.ProtectionSafety;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Location;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * The GriefPrevention adapter (protection.md section 9), written from GriefPrevention's public API: {@code allowBuild} and
 * {@code allowBreak} on the plugin (which also apply its claim-required world modes and the player's ignore-claims state) and
 * {@code allowContainers} or {@code allowAccess} on the claim at the place. Each returns the denial text or null, which is
 * the reason a deny carries.
 *
 * <p>Answers only an online player's break, place and interact, and a no-actor change (denied inside a claim, not covered
 * elsewhere: a claim protects its land from everyone but its trusted players). GriefPrevention's API takes a live
 * {@code Player}, so a player known by id only gets no opinion; damage and item use abstain, and the event path covers them
 * (GriefPrevention listens to those events itself).
 */
public final class GriefPreventionAdapter implements Backend {

    /** The plugin's name as its {@code plugin.yml} declares it. */
    public static final String PLUGIN = "GriefPrevention";

    /** What the adapter asks GriefPrevention. A denial is its text; null means allowed. The real one is {@link Real}. */
    public interface Api {
        /** The text that denies {@code player} building at {@code location}, or null. */
        String build(Player player, Location location);

        /** The text that denies {@code player} breaking the block at {@code location}, or null. */
        String breakBlock(Player player, Location location);

        /** The text that denies {@code player} opening or pressing what is at {@code location}, or null. */
        String interact(Player player, Location location);

        /** Whether a claim covers {@code location}. */
        boolean claimed(Location location);
    }

    private final Api api;

    public GriefPreventionAdapter(Api api) {
        this.api = api;
    }

    /** The port over the real GriefPrevention; call only while it is enabled. */
    public static ProtectionPort create() {
        return port(new Real());
    }

    /** The port over {@code api}. */
    public static ProtectionPort port(Api api) {
        return new BackendPort(PLUGIN, "Asks GriefPrevention's build, break, container and access checks, and treats a claim "
                + "as protected from an actor-less change.", ProtectionSafety.OWNING_REGION, new GriefPreventionAdapter(api));
    }

    @Override
    public Decision online(ProtectionAction action, Player player, Location location, Entity victim) {
        return switch (action) {
            case BREAK -> from(api.breakBlock(player, location));
            case PLACE -> from(api.build(player, location));
            case INTERACT -> api.claimed(location) ? from(api.interact(player, location)) : Decision.abstain();
            case DAMAGE, USE -> Decision.abstain();
        };
    }

    @Override
    public Decision nobody(ProtectionAction action, Location location, Entity victim) {
        if (action == ProtectionAction.DAMAGE || action == ProtectionAction.USE) {
            return Decision.abstain();
        }
        return api.claimed(location) ? Decision.deny("the place is inside a claim") : Decision.abstain();
    }

    private static Decision from(String denial) {
        return denial == null ? Decision.allow() : Decision.deny(denial.strip());
    }

    /** The calls into GriefPrevention. */
    static final class Real implements Api {

        Real() {
            if (GriefPrevention.instance == null) {
                throw new IllegalStateException("GriefPrevention is not initialised");
            }
        }

        @Override
        public String build(Player player, Location location) {
            return GriefPrevention.instance.allowBuild(player, location);
        }

        @Override
        public String breakBlock(Player player, Location location) {
            return GriefPrevention.instance.allowBreak(player, location.getBlock(), location);
        }

        @Override
        public String interact(Player player, Location location) {
            Claim claim = claimAt(location);
            if (claim == null) {
                return null;
            }
            boolean container = location.getBlock().getState(false) instanceof Container;
            return container ? claim.allowContainers(player) : claim.allowAccess(player);
        }

        @Override
        public boolean claimed(Location location) {
            return claimAt(location) != null;
        }

        private static Claim claimAt(Location location) {
            return GriefPrevention.instance.dataStore.getClaimAt(location, false, null);
        }
    }
}
