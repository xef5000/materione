package dev.materione.plugin.logic.currency;

import dev.materione.api.logic.ReadUnavailableException;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.Currency;
import dev.materione.api.logic.provider.CurrencyCatalog;
import dev.materione.api.logic.provider.CurrencyIntegration;
import dev.materione.api.logic.provider.CurrencyLookupCapability;
import dev.materione.api.logic.provider.EconomyResult;
import dev.materione.plugin.logic.GatewayRunScheduler;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.entity.Player;

/** Synchronous read guard; it never schedules or waits for another owner. */
public final class CurrencyReads {
    private final boolean folia;
    private final GatewayRunScheduler.ThreadProbe probe;

    public CurrencyReads(boolean folia, GatewayRunScheduler.ThreadProbe probe) {
        this.folia = folia;
        this.probe = Objects.requireNonNull(probe, "probe");
    }

    /** Legacy Paper behaviour, including servers without an ownership probe. */
    public static CurrencyReads paper() {
        return new CurrencyReads(false, GatewayRunScheduler.ThreadProbe.SERVER);
    }

    /** Contains provider lookup failures without turning them into a negative balance. */
    public <T> T lookup(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (ReadUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException failure) {
            if (!folia) {
                throw failure;
            }
            throw new ReadUnavailableException("currency lookup failed: " + failure.getMessage());
        }
    }

    /** Proves ownership before a placeholder calls a binding or availability callback. */
    public void requirePlaceholderLookup(CurrencyLookupCapability capability, Player player) {
        if (!folia) {
            if (!probe.onGlobalThread()) {
                throw new ReadUnavailableException("currency placeholder requires the Paper main thread");
            }
            return;
        }
        if (capability == null || !capability.folia().available()) {
            throw new ReadUnavailableException(capability == null ? "currency lookup capability is missing"
                    : capability.folia().reason());
        }
        requireOwner(capability.owner(), player, "currency lookup");
    }

    /** Proves synchronous read ownership and identity safety, including Paper async callers. */
    public void requirePlaceholderRead(CurrencyBackend backend, Player player) {
        if (!folia && !probe.onGlobalThread()) {
            throw new ReadUnavailableException("currency placeholder requires the Paper main thread");
        }
        var capability = backend.readCapability();
        if (capability == null) {
            throw new ReadUnavailableException("currency read capability is missing");
        }
        if (folia && !capability.folia().available()) {
            throw new ReadUnavailableException(capability.folia().reason());
        }
        if (player == null) {
            if (capability.offlineOwner() == null || !backend.stableOfflineIdentityRead()) {
                throw new ReadUnavailableException("offline identity-safe currency reads are unavailable");
            }
            if (folia) {
                requireOwner(capability.offlineOwner(), null, "offline currency read");
            }
        } else if (!folia && !probe.ownsEntity(player)) {
            throw new ReadUnavailableException("currency placeholder requires the player's owner");
        } else if (folia) {
            if (!backend.stableOnlineIdentityRead()) {
                throw new ReadUnavailableException("online identity-safe currency reads are unavailable");
            }
            requireOwner(capability.owner(), player, "currency read");
        }
    }

    private void requireOwner(Threading owner, Player player, String operation) {
        boolean owned = switch (owner) {
            case ANY -> true;
            case GLOBAL -> probe.onGlobalThread();
            case PLAYER_REGION -> player != null && probe.ownsEntity(player);
        };
        if (!owned) {
            throw new ReadUnavailableException(operation + " requires " + owner);
        }
    }

    private record LookupPlan(CurrencyCatalog catalog, Currency currency,
            CurrencyIntegration integration, CurrencyLookupCapability capability) { }

    private LookupPlan plan(CurrencyCatalog catalog, Currency currency) {
        CurrencyIntegration integration = lookup(() -> catalog.integrationForRead(currency).orElse(null));
        if (integration == null) {
            throw new ReadUnavailableException("currency integration is unavailable");
        }
        CurrencyLookupCapability capability = lookup(integration::lookupCapability);
        if (capability == null || !capability.folia().available()) {
            throw new ReadUnavailableException(capability == null ? "currency lookup capability is missing"
                    : capability.folia().reason());
        }
        return new LookupPlan(catalog, currency, integration, capability);
    }

    private void requireCurrent(LookupPlan plan) {
        if (lookup(() -> plan.catalog().get(plan.currency().id()).orElse(null)) != plan.currency()
                || lookup(() -> plan.catalog().integrationForRead(plan.currency()).orElse(null)) != plan.integration()) {
            throw new ReadUnavailableException("currency binding changed before the read completed");
        }
    }

    private CurrencyBackend prepare(LookupPlan plan, Player player) {
        requireOwner(plan.capability().owner(), player, "currency lookup");
        requireCurrent(plan);
        CurrencyBackend backend = lookup(() -> plan.catalog().bindForRead(plan.currency(), plan.integration()).orElse(null));
        if (backend == null) {
            throw new ReadUnavailableException("currency backend is unavailable");
        }
        var capability = backend.readCapability();
        if (capability == null || !capability.folia().available()) {
            throw new ReadUnavailableException(capability == null ? "currency read capability is missing"
                    : capability.folia().reason());
        }
        requireCurrent(plan);
        verifyAvailable(plan, backend, player);
        return backend;
    }

    private void verifyAvailable(LookupPlan plan, CurrencyBackend backend, Player player) {
        requireOwner(plan.capability().owner(), player, "currency lookup");
        requireCurrent(plan);
        var available = lookup(backend::availability);
        if (available == null || !available.available()) {
            throw new ReadUnavailableException(available == null ? "currency availability is missing"
                    : available.reason());
        }
        requireCurrent(plan);
    }

    /** Read a resolved flow currency; on Folia binding and availability require their declared owner. */
    public EconomyResult readResolved(CurrencyCatalog catalog, Currency currency, Player player,
            Function<CurrencyBackend, EconomyResult> operation) {
        if (!folia) {
            CurrencyBackend backend = lookup(() -> catalog.backend(currency).orElse(null));
            return backend == null ? new EconomyResult.Failed("currency backend is unavailable")
                    : read(backend, player, () -> operation.apply(backend));
        }
        LookupPlan plan = plan(catalog, currency);
        CurrencyBackend backend = prepare(plan, player);
        EconomyResult result = read(backend, player, () -> {
            requireCurrent(plan);
            return operation.apply(backend);
        });
        verifyAvailable(plan, backend, player);
        return result;
    }

    /** Hops to the declared lookup owner, then read owner, then rechecks availability without blocking. */
    public CompletionStage<EconomyResult> scheduleResolved(CurrencyCatalog catalog, Currency currency, Player player,
            ReadExecutor executor, Function<CurrencyBackend, EconomyResult> operation) {
        if (!folia) {
            return executor.on(Threading.ANY, () -> readResolved(catalog, currency, player, operation));
        }
        final LookupPlan plan;
        try {
            plan = plan(catalog, currency);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        return executor.on(plan.capability().owner(), () -> prepare(plan, player))
                .thenCompose(backend -> schedule(backend, executor, () -> read(backend, player, () -> {
                    requireCurrent(plan);
                    return operation.apply(backend);
                })).thenCompose(result -> executor.on(plan.capability().owner(), () -> {
                    verifyAvailable(plan, backend, player);
                    return result;
                })));
    }

    public EconomyResult read(CurrencyBackend backend, Player player, Supplier<EconomyResult> operation) {
        if (!folia) {
            return operation.get();
        }
        try {
            return readOnFolia(backend, player, operation);
        } catch (ReadUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException failure) {
            throw new ReadUnavailableException("currency read failed: " + failure.getMessage());
        }
    }

    /** Reads a known offline identity only when the integration declares an offline owner. */
    public EconomyResult readOffline(CurrencyBackend backend, Supplier<EconomyResult> operation) {
        if (!folia) {
            return operation.get();
        }
        try {
            var capability = backend.readCapability();
            if (capability == null || !capability.folia().available() || capability.offlineOwner() == null) {
                throw new ReadUnavailableException(capability == null
                        ? "currency integration returned no read capability"
                        : !capability.folia().available() ? capability.folia().reason()
                        : "currency integration does not support offline reads");
            }
            if (capability.offlineOwner() == Threading.GLOBAL && !probe.onGlobalThread()) {
                throw new ReadUnavailableException("offline currency read requires GLOBAL");
            }
            EconomyResult result = operation.get();
            if (result == null) {
                throw new ReadUnavailableException("currency provider returned no result");
            }
            if (result instanceof EconomyResult.Failed failed) {
                throw new ReadUnavailableException(failed.reason());
            }
            return result;
        } catch (ReadUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException failure) {
            throw new ReadUnavailableException("offline currency read failed: " + failure.getMessage());
        }
    }

    /** Dispatches a declared offline read; the operation must re-resolve the live backend. */
    public <T> CompletionStage<T> scheduleOffline(
            CurrencyBackend backend, ReadExecutor executor, Supplier<T> operation) {
        try {
            Threading owner = Threading.ANY;
            if (folia) {
                var capability = backend.readCapability();
                if (capability == null || !capability.folia().available() || capability.offlineOwner() == null) {
                    throw new ReadUnavailableException(capability == null
                            ? "currency integration returned no read capability"
                            : !capability.folia().available() ? capability.folia().reason()
                            : "currency integration does not support offline reads");
                }
                owner = capability.offlineOwner();
            }
            return executor.on(owner, operation);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure instanceof ReadUnavailableException ? failure
                    : new ReadUnavailableException("currency read capability failed: " + failure.getMessage()));
        }
    }

    private EconomyResult readOnFolia(CurrencyBackend backend, Player player, Supplier<EconomyResult> operation) {
        var capability = backend.readCapability();
        if (capability == null) {
            throw new ReadUnavailableException("currency integration returned no read capability");
        }
        if (!capability.folia().available()) {
            throw new ReadUnavailableException(capability.folia().reason());
        }
        boolean owned = switch (capability.owner()) {
            case PLAYER_REGION -> probe.ownsEntity(player);
            case GLOBAL -> probe.onGlobalThread();
            case ANY -> true;
        };
        if (!owned) {
            throw new ReadUnavailableException("currency read requires " + capability.owner());
        }
        EconomyResult result = operation.get();
        if (result == null) {
            throw new ReadUnavailableException("currency provider returned no result");
        }
        if (result instanceof EconomyResult.Failed failed) {
            throw new ReadUnavailableException(failed.reason());
        }
        return result;
    }

    /** Dispatches a live read without blocking. The operation must re-resolve the live backend. */
    public <T> CompletionStage<T> schedule(CurrencyBackend backend, ReadExecutor executor, Supplier<T> operation) {
        try {
            Threading owner = Threading.ANY;
            if (folia) {
                var capability = backend.readCapability();
                if (capability == null) {
                    throw new ReadUnavailableException("currency integration returned no read capability");
                }
                if (!capability.folia().available()) {
                    throw new ReadUnavailableException(capability.folia().reason());
                }
                owner = capability.owner();
            }
            return executor.on(owner, operation);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure instanceof ReadUnavailableException ? failure
                    : new ReadUnavailableException("currency read capability failed: " + failure.getMessage()));
        }
    }

    /** On Folia an unavailable currency must not be interpreted as a negative or zero balance. */
    public void unavailable(String reason) {
        if (folia) {
            throw new ReadUnavailableException(reason);
        }
    }
}
