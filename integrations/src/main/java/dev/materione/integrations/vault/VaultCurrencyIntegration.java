package dev.materione.integrations.vault;

import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.CurrencyIntegration;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import net.milkbowl.vault.economy.Economy;

/**
 * The {@code vault} currency integration (logic.md §16): the economy Vault exposes, through Vault's own
 * service registry. It registers under {@code materione:vault} only while Vault is enabled
 * ({@link dev.materione.integrations.Integrations}), so a server without Vault never loads these classes.
 *
 * <p>It takes no keys of its own: whatever economy the server registered with Vault is the currency. The
 * constructor loads Vault's {@code Economy} type, so an incompatible Vault fails inside the integration
 * guard, not later inside an action.
 */
public final class VaultCurrencyIntegration implements CurrencyIntegration {

    public VaultCurrencyIntegration() {
        Economy.class.getName();
    }

    @Override
    public String id() {
        return "vault";
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription("Vault economy",
                "The economy a server registers with Vault. It has no keys of its own.",
                List.<ParameterSchema>of(), "2.0.0");
    }

    @Override
    public CurrencyBackend bind(ParamReader config) {
        return new VaultCurrencyBackend();
    }
}
