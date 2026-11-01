package dev.materione.plugin.logic.currency;

import dev.materione.api.logic.ReadUnavailableException;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.EconomyResult;
import dev.materione.plugin.logic.GatewayRunScheduler;
import java.util.Objects;
import java.util.function.Supplier;
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
