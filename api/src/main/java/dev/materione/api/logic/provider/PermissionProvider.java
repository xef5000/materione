package dev.materione.api.logic.provider;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Grants and revokes permission nodes for a player (logic.md §4.5; the {@code give_permission},
 * {@code take_permission} actions and {@code run_command as: op}). The default is a Bukkit
 * attachment, which lasts for the session only; LuckPerms and Vault adapters write to the
 * permission plugin instead, so grants persist.
 *
 * <p>Three shapes of grant:
 *
 * <ul>
 *   <li>{@link #grant} and {@link #revoke}: the permanent pair every provider implements.
 *   <li>{@link #grantFor(UUID, String, Duration)}: a grant that expires by itself. Only providers
 *       with native expiry support it (LuckPerms); the default answers "not supported".
 *   <li>{@link #withGrant}: the scoped form. The node is granted, the body runs, and the node is
 *       revoked afterwards even when the body throws. It is built from {@code grant} and
 *       {@code revoke}, so every provider gets it.
 * </ul>
 *
 * <p>Providers that run asynchronously (LuckPerms writes through its own scheduler) report the
 * request as accepted once it is queued; a later write failure is logged by the adapter.
 */
public interface PermissionProvider {

    /** The provider's display name, such as {@code Bukkit attachments}. */
    String name();

    /** Whether this provider can answer right now (see {@link EconomyProvider#available()}). */
    default boolean available() {
        return true;
    }

    /** Grants {@code node} to the player until it is revoked. */
    ProviderResult grant(UUID player, String node);

    /** Removes {@code node} from the player's own grants. */
    ProviderResult revoke(UUID player, String node);

    /**
     * Grants {@code node} for {@code duration}, after which the provider drops it. The default
     * refuses: a provider must have native expiry to offer this.
     */
    default ProviderResult grantFor(UUID player, String node, Duration duration) {
        Objects.requireNonNull(duration, "duration");
        return ProviderResult.failed(name() + " cannot expire a permission; grant it for good or use a provider with expiry");
    }

    /**
     * Grants {@code node}, runs {@code body}, then revokes the node, even when {@code body} throws.
     * A refused grant runs nothing and returns the refusal.
     */
    default ProviderResult withGrant(UUID player, String node, Runnable body) {
        Objects.requireNonNull(body, "body");
        ProviderResult granted = grant(player, node);
        if (!granted.ok()) {
            return granted;
        }
        try {
            body.run();
        } finally {
            revoke(player, node);
        }
        return ProviderResult.OK;
    }
}
