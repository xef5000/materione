package dev.materione.content.currency;

import dev.materione.api.NamespacedId;
import dev.materione.api.content.Allocations;
import dev.materione.api.content.ContentType;
import dev.materione.api.content.PackEmitter;
import dev.materione.api.logic.provider.Currency;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.content.Templates;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The named-currency content type (logic.md §16): {@code currencies/*.yml}, each file one currency
 * under its pack's namespace ({@code shop:gems}). A currency has a display name, a symbol, a number of
 * decimals, and the integration that answers for its money; the rest of the mapping belongs to that
 * integration and is kept as authored, so a currency round-trips losslessly and the integration reads
 * its own keys at bind time.
 *
 * <p>Nothing is emitted to the pack: money is runtime state, not a client asset. Parsing is pure and
 * never throws; a malformed currency parses to nothing and the pipeline quarantines it.
 */
public final class CurrencyType implements ContentType {

    /** Keys this type reads itself; every other key belongs to the integration. */
    static final Set<String> OWN_KEYS = Set.of("integration", "display_name", "symbol", "decimals");

    /** The reserved namespace of built-in currencies; packs may not define into it. */
    public static final String RESERVED_NAMESPACE = "materione";

    @Override
    public String name() {
        return "currency";
    }

    @Override
    public String directory() {
        return "currencies";
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription(
                "Currency",
                "A named currency (gems, coins) that give_money, take_money and the balance function use. "
                        + "The integration key chooses how its money is read and changed; the other keys belong to that integration.",
                List.of(
                        new ParameterSchema("integration", ParameterType.STRING, true, null,
                                "Integration that answers for this currency: vault, xp, custom, or an addon's id", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Integration", "economy", 10, false, "text", "", "custom")),
                        new ParameterSchema("display_name", ParameterType.STRING, false, null,
                                "Name shown to players (defaults to the id's name)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Display name", "economy", 20, false, "text", "", "Gems")),
                        new ParameterSchema("symbol", ParameterType.STRING, false, "",
                                "Prefix formatting puts before the amount", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Symbol", "economy", 30, false, "text", "", "G$")),
                        new ParameterSchema("decimals", ParameterType.INTEGER, false, "2",
                                "Digits after the point when formatting, 0 to 6", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Decimals", "economy", 40, true, "number", "", "2")),
                        new ParameterSchema("deposit-command", ParameterType.STRING, false, null,
                                "custom: console command that gives money; {player} and {amount} are filled in", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Deposit command", "economy", 50, true, "text", "", "gems give {player} {amount}")),
                        new ParameterSchema("withdraw-command", ParameterType.STRING, false, null,
                                "custom: console command that takes money; {player} and {amount} are filled in", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Withdraw command", "economy", 60, true, "text", "", "gems take {player} {amount}")),
                        new ParameterSchema("balance-placeholder", ParameterType.STRING, false, null,
                                "custom: PlaceholderAPI token that reads the balance as a number", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Balance placeholder", "economy", 70, true, "text", "", "%coinsengine_balance_raw_gems%"))),
                "2.0.0");
    }

    @Override
    public Optional<Object> parse(
            NamespacedId id, Map<String, Object> definition, Map<String, Map<String, Object>> templates) {
        if (RESERVED_NAMESPACE.equals(id.namespace())) {
            return Optional.empty();
        }
        var merged = Templates.merge(definition, id.namespace(), templates);
        if (merged == null) {
            return Optional.empty();
        }
        String integration = Templates.text(merged, "integration");
        if (integration == null || integration.isBlank()) {
            return Optional.empty();
        }
        int decimals = 2;
        Object rawDecimals = merged.get("decimals");
        if (rawDecimals != null) {
            if (!(rawDecimals instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue())) {
                return Optional.empty();
            }
            decimals = number.intValue();
        }
        if (decimals < 0 || decimals > Currency.MAX_DECIMALS) {
            return Optional.empty();
        }
        String displayName = Templates.text(merged, "display_name");
        String symbol = Templates.text(merged, "symbol");
        var config = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : merged.entrySet()) {
            if (!OWN_KEYS.contains(entry.getKey())) {
                config.put(entry.getKey(), entry.getValue());
            }
        }
        try {
            return Optional.of(new CurrencyDefinition(new Currency(
                    id,
                    displayName == null || displayName.isBlank() ? id.value() : displayName,
                    symbol == null ? "" : symbol,
                    decimals,
                    integration,
                    config)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Currencies emit nothing into the pack: money is runtime state (logic.md §16). */
    @Override
    public void compile(Object definition, NamespacedId allocatedModelId, PackEmitter emitter, Allocations allocations) {
        // intentionally empty
    }
}
