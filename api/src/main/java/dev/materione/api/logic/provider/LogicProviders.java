package dev.materione.api.logic.provider;

import java.util.Optional;
import org.bukkit.plugin.Plugin;

/**
 * The provider slots behind the logic actions (logic.md §4.5), reached through
 * {@link dev.materione.api.logic.LogicApi#providers()}. Each slot holds one provider: a new
 * registration replaces the previous one, and closing the registration restores what it replaced
 * (the permission slot falls back to the session default; the others become empty).
 *
 * <p>Lookups are made at use. An action asks for the provider each time it runs or reports
 * availability, so installing a plugin that supplies a provider needs no reload of the actions
 * (only of the integration that registers it, which Materione does on enable and on reload).
 *
 * <p>A registration is owned by its plugin and is removed when the owner disables, as the
 * registries are.
 */
public interface LogicProviders {

    /** The active economy, or empty when none is registered. */
    Optional<EconomyProvider> economy();

    /** The active permission provider; always present (the session default when nothing else is). */
    PermissionProvider permissions();

    /** The active server transfer, or empty when none is configured or installed. */
    Optional<ServerTransfer> transfer();

    /**
     * Makes {@code provider} the economy, replacing any other.
     *
     * @throws IllegalStateException when {@code owner} is disabled
     */
    ProviderRegistration setEconomy(Plugin owner, EconomyProvider provider);

    /**
     * Makes {@code provider} the permission provider, replacing any other.
     *
     * @throws IllegalStateException when {@code owner} is disabled
     */
    ProviderRegistration setPermissions(Plugin owner, PermissionProvider provider);

    /**
     * Makes {@code transfer} the server transfer, replacing any other.
     *
     * @throws IllegalStateException when {@code owner} is disabled
     */
    ProviderRegistration setTransfer(Plugin owner, ServerTransfer transfer);

    /** A provider registration. Closing it is idempotent. */
    interface ProviderRegistration extends AutoCloseable {

        @Override
        void close();
    }
}
