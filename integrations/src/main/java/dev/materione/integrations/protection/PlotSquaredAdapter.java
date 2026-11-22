package dev.materione.integrations.protection;

import com.plotsquared.bukkit.util.BukkitUtil;
import com.plotsquared.core.location.Location;
import com.plotsquared.core.plot.Plot;
import dev.materione.api.protection.ProtectionAction;
import dev.materione.api.protection.ProtectionPort;
import dev.materione.api.protection.ProtectionSafety;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * The PlotSquared adapter (protection.md section 9), written from PlotSquared's public API: the plot at a location
 * ({@code Location#getPlotArea}, {@code Location#getPlot}), whether a player is added to it ({@code Plot#isAdded}: owner, trusted or
 * member) and PlotSquared's own admin-bypass permissions ({@code plots.admin.build.other}, {@code .unowned}, {@code .road}) for an
 * online player.
 *
 * <p>A place outside every plot world is not covered (abstain). Inside one, the place is protected: a plot only its owner, trusted
 * and members change; an unowned plot and a road only an admin with the bypass permission changes. A player known by id is
 * judged by membership alone (no permissions can be read), and no actor is denied everywhere in a plot world. PlotSquared's
 * per-plot flags that let strangers build or use (place, break, use, device-interact) are not read: a flag-opened plot is still
 * covered by the event path. Damage and item use abstain.
 */
public final class PlotSquaredAdapter implements Backend {

    /** The plugin's name as its {@code plugin.yml} declares it. */
    public static final String PLUGIN = "PlotSquared";

    /** What a place is to PlotSquared. */
    public enum Kind {
        /** Not in a plot world. */
        NOT_PLOT_WORLD,
        /** In a plot world, on a road or outside any plot. */
        ROAD,
        /** In an unclaimed plot. */
        UNOWNED,
        /** In a claimed plot. */
        OWNED
    }

    /** What the adapter asks PlotSquared. The real one is {@link Real}. */
    public interface Api {
        Kind kind(org.bukkit.Location location);

        /** Whether {@code player} is the owner, trusted or a member of the plot at {@code location}. */
        boolean added(org.bukkit.Location location, UUID player);
    }

    private final Api api;

    public PlotSquaredAdapter(Api api) {
        this.api = api;
    }

    /** The port over the real PlotSquared; call only while it is enabled. */
    public static ProtectionPort create() {
        return port(new Real());
    }

    /** The port over {@code api}. */
    public static ProtectionPort port(Api api) {
        return new BackendPort(PLUGIN, "Asks PlotSquared who may change a plot, an unowned plot or a road: the owner, trusted "
                + "and members, and the admin bypass permissions.", ProtectionSafety.OWNING_REGION,
                new PlotSquaredAdapter(api));
    }

    @Override
    public Decision online(ProtectionAction action, Player player, org.bukkit.Location location, Entity victim) {
        return decide(action, location, player.getUniqueId(), player);
    }

    @Override
    public Decision offline(ProtectionAction action, OfflinePlayer player, org.bukkit.Location location, Entity victim) {
        return decide(action, location, player.getUniqueId(), null);
    }

    @Override
    public Decision nobody(ProtectionAction action, org.bukkit.Location location, Entity victim) {
        if (unanswered(action)) {
            return Decision.abstain();
        }
        return api.kind(location) == Kind.NOT_PLOT_WORLD ? Decision.abstain() : Decision.deny("the place is in a plot world");
    }

    private Decision decide(ProtectionAction action, org.bukkit.Location location, UUID id, Player online) {
        if (unanswered(action)) {
            return Decision.abstain();
        }
        Kind kind = api.kind(location);
        return switch (kind) {
            case NOT_PLOT_WORLD -> Decision.abstain();
            case ROAD -> bypass(online, "plots.admin.build.road", "the place is a road");
            case UNOWNED -> bypass(online, "plots.admin.build.unowned", "the plot is unowned");
            case OWNED -> api.added(location, id) ? Decision.allow()
                    : bypass(online, "plots.admin.build.other", "the plot belongs to someone else");
        };
    }

    private static boolean unanswered(ProtectionAction action) {
        return action == ProtectionAction.DAMAGE || action == ProtectionAction.USE;
    }

    private static Decision bypass(Player online, String permission, String reason) {
        return online != null && online.hasPermission(permission) ? Decision.allow() : Decision.deny(reason);
    }

    /** The calls into PlotSquared. */
    static final class Real implements Api {

        Real() {
            Plot.class.getName(); // fails here, inside the integration guard, when PlotSquared's API does not link
        }

        @Override
        public Kind kind(org.bukkit.Location location) {
            Location at = BukkitUtil.adapt(location);
            if (at.getPlotArea() == null) {
                return Kind.NOT_PLOT_WORLD;
            }
            Plot plot = at.getPlot();
            if (plot == null) {
                return Kind.ROAD;
            }
            return plot.hasOwner() ? Kind.OWNED : Kind.UNOWNED;
        }

        @Override
        public boolean added(org.bukkit.Location location, UUID player) {
            Plot plot = BukkitUtil.adapt(location).getPlot();
            return plot != null && plot.isAdded(player);
        }
    }
}
