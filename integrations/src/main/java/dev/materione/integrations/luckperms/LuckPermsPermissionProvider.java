package dev.materione.integrations.luckperms;

import dev.materione.api.logic.provider.PermissionProvider;
import dev.materione.api.logic.provider.ProviderResult;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import org.bukkit.Bukkit;

/**
 * Permission nodes through LuckPerms' API (logic.md §4.5). The only provider with native expiry,
 * so {@link #grantFor} is supported. LuckPerms writes asynchronously: the request is accepted once
 * LuckPerms has queued the change, and a write that later fails is logged, not returned, because
 * waiting would block the thread the action runs on.
 *
 * <p>{@link #revoke} removes the node in every context (server, world, temporary), not only the
 * global one, so a take always leaves the player without the node.
 */
public final class LuckPermsPermissionProvider implements PermissionProvider {

    private final Logger log;

    /**
     * Loads LuckPerms' {@code LuckPerms} type now, inside the {@code Integrations} guard that reports
     * a failure (see the Vault economy adapter).
     *
     * @param log where asynchronous write failures are reported (the host plugin's logger)
     */
    public LuckPermsPermissionProvider(Logger log) {
        LuckPerms.class.getName();
        this.log = log;
    }

    @Override
    public String name() {
        return "LuckPerms";
    }

    @Override
    public boolean available() {
        return Bukkit.getServicesManager().load(LuckPerms.class) != null;
    }

    @Override
    public ProviderResult grant(UUID player, String node) {
        return write(player, node, user -> user.data().add(Node.builder(node).build()));
    }

    @Override
    public ProviderResult grantFor(UUID player, String node, Duration duration) {
        if (duration.isNegative() || duration.isZero()) {
            return ProviderResult.failed("a temporary grant needs a duration above zero");
        }
        return write(player, node, user -> user.data().add(Node.builder(node).expiry(duration).build()));
    }

    @Override
    public ProviderResult revoke(UUID player, String node) {
        return write(player, node, user -> user.data().clear(candidate -> node.equals(candidate.getKey())));
    }

    private ProviderResult write(UUID player, String node, Consumer<User> change) {
        LuckPerms luckPerms = Bukkit.getServicesManager().load(LuckPerms.class);
        if (luckPerms == null) {
            return ProviderResult.failed("LuckPerms is not available");
        }
        luckPerms.getUserManager().modifyUser(player, change).whenComplete((ignored, failure) -> {
            if (failure != null) {
                log.log(Level.WARNING, "LuckPerms could not change " + node + " for " + player, failure);
            }
        });
        return ProviderResult.OK;
    }
}
