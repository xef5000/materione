package dev.materione.plugin.logic;

import dev.materione.api.logic.provider.CurrencyCatalog;
import dev.materione.api.logic.provider.LogicProviders;
import dev.materione.api.logic.provider.PermissionProvider;
import dev.materione.api.logic.provider.ServerTransfer;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.plugin.Plugin;

/**
 * The provider slots (logic.md §4.5, §16). Currencies are a catalog that the live runtime keeps
 * current, so the money slot is not a registration. The permission and transfer slots each hold the
 * latest registration; closing a registration clears the slot only if it still holds that registration,
 * so a replacement is never undone by the handle it replaced. The permission slot falls back to the
 * session default, which is always present, so {@link #permissions()} never returns null.
 */
final class LogicProvidersImpl implements LogicProviders {

    private final Object lock = new Object();
    private final CurrencyCatalog currencies;
    private final Slot<PermissionProvider> permissions;
    private final Slot<ServerTransfer> transfer = new Slot<>(null);

    /**
     * @param defaultPermissions what {@link #permissions()} answers when nothing else is registered
     * @param currencies the currency catalog the money actions read
     */
    LogicProvidersImpl(PermissionProvider defaultPermissions, CurrencyCatalog currencies) {
        this.permissions = new Slot<>(Objects.requireNonNull(defaultPermissions, "defaultPermissions"));
        this.currencies = Objects.requireNonNull(currencies, "currencies");
    }

    @Override
    public CurrencyCatalog currencies() {
        return currencies;
    }

    @Override
    public PermissionProvider permissions() {
        return permissions.get();
    }

    @Override
    public Optional<ServerTransfer> transfer() {
        return Optional.ofNullable(transfer.get());
    }

    @Override
    public ProviderRegistration setPermissions(Plugin owner, PermissionProvider provider) {
        return install(permissions, owner, Objects.requireNonNull(provider, "provider"), "permission");
    }

    @Override
    public ProviderRegistration setTransfer(Plugin owner, ServerTransfer provider) {
        return install(transfer, owner, Objects.requireNonNull(provider, "provider"), "transfer");
    }

    /** Owner disabled: the registrations it made leave their slots (the fallback answers instead). */
    void ownerDisabled(Plugin owner) {
        synchronized (lock) {
            permissions.clearOwnedBy(owner);
            transfer.clearOwnedBy(owner);
        }
    }

    private <T> ProviderRegistration install(Slot<T> slot, Plugin owner, T provider, String label) {
        Objects.requireNonNull(owner, "owner");
        if (!owner.isEnabled()) {
            throw new IllegalStateException(label + " provider owner '" + owner.getName() + "' is disabled");
        }
        synchronized (lock) {
            var entry = new Entry<>(owner, provider);
            slot.current = entry;
            return () -> {
                synchronized (lock) {
                    if (slot.current == entry) {
                        slot.current = null;
                    }
                }
            };
        }
    }

    private record Entry<T>(Plugin owner, T provider) {}

    /** One provider slot: the current registration, or the fallback when there is none. */
    private static final class Slot<T> {

        private final T fallback;
        private volatile Entry<T> current;

        Slot(T fallback) {
            this.fallback = fallback;
        }

        T get() {
            Entry<T> entry = current;
            return entry == null ? fallback : entry.provider();
        }

        /** Called under the owning lock. */
        void clearOwnedBy(Plugin owner) {
            Entry<T> entry = current;
            if (entry != null && entry.owner() == owner) {
                current = null;
            }
        }
    }
}
