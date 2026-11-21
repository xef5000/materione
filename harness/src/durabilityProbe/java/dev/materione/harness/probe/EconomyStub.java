package dev.materione.harness.probe;

import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.CurrencyIntegration;
import dev.materione.api.logic.provider.EconomyResult;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A test-only economy provider (logic.md section 42, #464): an implementation of Materione's own {@link CurrencyIntegration}
 * SPI with balances held in memory, registered by the durability probe when the probe enables. It is not Vault and ships
 * nowhere: the harness's {@code economy} fixture pack names it as {@code harness:stub}, so a money action moves real balances
 * the probe can read back. Only the probe reads or writes {@link #BALANCES}.
 */
public final class EconomyStub implements CurrencyIntegration {

    /** The balances, by player id. Every change goes through {@link Backend}, which holds the lock. */
    static final Map<UUID, Double> BALANCES = new ConcurrentHashMap<>();

    /**
     * The local name. The registry registers it as {@code harness:stub} (the probe's {@code NamespacedId}), and a currency file
     * writes {@code integration: harness:stub}; the registry requires the id's value to equal this name.
     */
    public static final String ID = "stub";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription("Harness stub economy",
                "A test-only economy: balances are held in memory by the harness probe", List.of(), "1.0.0");
    }

    @Override
    public CurrencyBackend bind(ParamReader config) {
        return new Backend();
    }

    /** The in-memory backend; a withdrawal the balance cannot cover takes nothing. */
    static final class Backend implements CurrencyBackend {

        @Override
        public EconomyResult balance(UUID player) {
            return new EconomyResult.Succeeded(BALANCES.getOrDefault(player, 0d));
        }

        @Override
        public EconomyResult has(UUID player, double amount) {
            double balance = BALANCES.getOrDefault(player, 0d);
            return balance >= amount
                    ? new EconomyResult.Succeeded(balance)
                    : new EconomyResult.InsufficientFunds(balance);
        }

        @Override
        public EconomyResult withdraw(UUID player, double amount) {
            synchronized (BALANCES) {
                EconomyResult held = has(player, amount);
                if (held instanceof EconomyResult.InsufficientFunds) {
                    return held;
                }
                double after = BALANCES.getOrDefault(player, 0d) - amount;
                BALANCES.put(player, after);
                return new EconomyResult.Succeeded(after);
            }
        }

        @Override
        public EconomyResult deposit(UUID player, double amount) {
            synchronized (BALANCES) {
                double after = BALANCES.getOrDefault(player, 0d) + amount;
                BALANCES.put(player, after);
                return new EconomyResult.Succeeded(after);
            }
        }
    }
}
