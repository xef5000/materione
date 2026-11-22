package dev.materione.integrations.protection;

import dev.materione.api.protection.ProtectionAction;
import dev.materione.api.protection.ProtectionPort;
import dev.materione.api.protection.ProtectionSafety;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Container;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;
import world.bentobox.bentobox.BentoBox;
import world.bentobox.bentobox.api.flags.Flag;
import world.bentobox.bentobox.api.user.User;
import world.bentobox.bentobox.database.objects.Island;
import world.bentobox.bentobox.lists.Flags;

/**
 * The BentoBox adapter (protection.md section 9), written from BentoBox's public API: the protected island at a location
 * ({@code IslandsManager#getProtectedIslandAt}) and {@code Island#isAllowed(User, Flag)} with the flag that matches the question
 * (break blocks, place blocks, containers, doors, trapdoors, levers, buttons, and the hurt-animals, hurt-monsters,
 * hurt-villagers and PvP flags). It covers every BentoBox game mode (BSkyBlock, AOneBlock and the rest) through the one API.
 *
 * <p>A place outside every protected island is not covered (abstain). A user can be a connected or an offline player, so both are
 * judged by their island rank; no actor is judged by the island's settings alone ({@code Island#isAllowed(Flag)}, the rank
 * a visitor has). Damage by no one abstains, and so does item use.
 */
public final class BentoBoxAdapter implements Backend {

    /** The plugin's name as its {@code plugin.yml} declares it. */
    public static final String PLUGIN = "BentoBox";

    /**
     * What the adapter asks BentoBox: true allowed, false denied, null when no protected island covers the place or no
     * flag fits the question. The real one is {@link Real}.
     */
    public interface Api {
        Boolean online(ProtectionAction action, Player player, Location location, Entity victim);

        Boolean offline(ProtectionAction action, OfflinePlayer player, Location location, Entity victim);

        Boolean nobody(ProtectionAction action, Location location, Entity victim);
    }

    private static final String DENIED = "the island's protection settings deny it";

    private final Api api;

    public BentoBoxAdapter(Api api) {
        this.api = api;
    }

    /** The port over the real BentoBox; call only while it is enabled. */
    public static ProtectionPort create() {
        return port(new Real());
    }

    /** The port over {@code api}. */
    public static ProtectionPort port(Api api) {
        return new BackendPort(PLUGIN, "Asks BentoBox's island protection flags (break, place, containers, doors, hurting "
                + "animals, villagers and monsters, PvP) for any game mode.", ProtectionSafety.OWNING_REGION,
                new BentoBoxAdapter(api));
    }

    @Override
    public Decision online(ProtectionAction action, Player player, Location location, Entity victim) {
        return from(api.online(action, player, location, victim));
    }

    @Override
    public Decision offline(ProtectionAction action, OfflinePlayer player, Location location, Entity victim) {
        return from(api.offline(action, player, location, victim));
    }

    @Override
    public Decision nobody(ProtectionAction action, Location location, Entity victim) {
        return action == ProtectionAction.DAMAGE ? Decision.abstain() : from(api.nobody(action, location, victim));
    }

    private static Decision from(Boolean allowed) {
        return allowed == null ? Decision.abstain() : Decision.of(allowed, DENIED);
    }

    /** The calls into BentoBox. */
    static final class Real implements Api {

        Real() {
            BentoBox.getInstance().getIslands(); // fails here, inside the integration guard, when BentoBox is not ready
        }

        @Override
        public Boolean online(ProtectionAction action, Player player, Location location, Entity victim) {
            return ask(action, User.getInstance(player), location, victim);
        }

        @Override
        public Boolean offline(ProtectionAction action, OfflinePlayer player, Location location, Entity victim) {
            return ask(action, User.getInstance(player), location, victim);
        }

        @Override
        public Boolean nobody(ProtectionAction action, Location location, Entity victim) {
            return ask(action, null, location, victim);
        }

        private static Boolean ask(ProtectionAction action, User user, Location location, Entity victim) {
            Optional<Island> island = BentoBox.getInstance().getIslands().getProtectedIslandAt(location);
            Flag flag = island.isPresent() ? flag(action, location, victim) : null;
            if (flag == null) {
                return null;
            }
            // a setting flag (PvP, the hurt flags with no owner) is the island's, not a rank's
            return user == null ? island.get().isAllowed(flag) : island.get().isAllowed(user, flag);
        }

        private static Flag flag(ProtectionAction action, Location location, Entity victim) {
            return switch (action) {
                case BREAK -> Flags.BREAK_BLOCKS;
                case PLACE -> Flags.PLACE_BLOCKS;
                case INTERACT -> interact(location);
                case DAMAGE -> damage(victim, location);
                case USE -> null;
            };
        }

        private static Flag interact(Location location) {
            var state = location.getBlock().getState(false);
            if (state instanceof Container) {
                return Flags.CONTAINER;
            }
            var data = location.getBlock().getBlockData();
            Material type = data.getMaterial();
            if (type == Material.LEVER) {
                return Flags.LEVER;
            }
            if (type.name().endsWith("_BUTTON")) {
                return Flags.BUTTON;
            }
            if (data instanceof Openable) {
                return type.name().endsWith("TRAPDOOR") ? Flags.TRAPDOOR : Flags.DOOR;
            }
            return null;
        }

        private static Flag damage(Entity victim, Location location) {
            if (victim instanceof Player) {
                return switch (location.getWorld().getEnvironment()) {
                    case NETHER -> Flags.PVP_NETHER;
                    case THE_END -> Flags.PVP_END;
                    default -> Flags.PVP_OVERWORLD;
                };
            }
            if (victim instanceof Villager) {
                return Flags.HURT_VILLAGERS;
            }
            if (victim instanceof Tameable tamed && tamed.isTamed()) {
                return Flags.HURT_TAMED_ANIMALS;
            }
            if (victim instanceof Animals) {
                return Flags.HURT_ANIMALS;
            }
            return victim instanceof Monster ? Flags.HURT_MONSTERS : null;
        }
    }
}
