package dev.materione.api.logic.provider;

import java.util.Optional;
import org.bukkit.plugin.Plugin;

/**
 * The provider slots behind the logic actions (logic.md §4.5, §16), reached through
 * {@link dev.materione.api.logic.LogicApi#providers()}. Money is a catalog of named currencies
 * ({@link #currencies()}), defined by content and reached through the {@code currency-integrations}
 * registry. Permissions and server transfer are single slots: a new registration replaces the slot,
 * and closing the registration restores what it replaced (the permission slot falls back to the
 * session default, the transfer slot becomes empty).
 *
 * <p>Lookups are made at use, never cached by an action. A registration is owned by its plugin and is
 * removed when the owner disables, as the registries are.
 */
public interface LogicProviders {

    /** The defined currencies and their backends. */
    CurrencyCatalog currencies();

    /** The active permission provider; always present (the session default when nothing else is). */
    PermissionProvider permissions();

    /** The active server transfer, or empty when none is configured or installed. */
    Optional<ServerTransfer> transfer();

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
