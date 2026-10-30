package dev.materione.plugin.logic.function;

import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.FunctionDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.provider.Currency;
import dev.materione.api.logic.provider.CurrencyCatalog;
import dev.materione.api.logic.provider.LogicProviders;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code format_currency(amount, currency)}: the amount as the currency shows it, its symbol before
 * the number, with thousands separators and the currency's decimals, for example {@code G$1,250}.
 * A negative amount puts the sign before the symbol. Null for a non-number, an undefined currency, or
 * a non-finite amount.
 *
 * <p>Pure: it reads the currency's own definition, never a player's balance. Pair it with
 * {@code balance(currency)} to show a balance.
 */
public final class FormatCurrencyFunction implements ExpressionFunction {

    private final LogicProviders providers;

    /** @param providers the currency catalog the name resolves against */
    public FormatCurrencyFunction(LogicProviders providers) {
        this.providers = Objects.requireNonNull(providers, "providers");
    }

    @Override
    public String id() {
        return "format_currency";
    }

    @Override
    public FunctionDescription describe() {
        return FunctionDescription.of(new SelfDescription("Format currency",
                "An amount in a currency's own notation: its symbol, thousands separators and decimals",
                List.of(
                        FunctionArgs.param("amount", ParameterType.DECIMAL, true, "The amount to format"),
                        FunctionArgs.param("currency", ParameterType.STRING, true, "Currency id, e.g. money or shop:gems")),
                FunctionArgs.SINCE), ParameterType.STRING);
    }

    @Override
    public Object call(List<Object> arguments, LogicContext context) {
        Optional<Double> amount = FunctionArgs.number(arguments.get(0));
        if (amount.isEmpty() || !Double.isFinite(amount.get()) || !(arguments.get(1) instanceof String name)) {
            return null;
        }
        CurrencyCatalog catalog = providers.currencies();
        Currency currency = catalog.resolve(name).orElse(null);
        if (currency == null) {
            return null;
        }
        String digits = String.format(Locale.ROOT, "%,." + currency.decimals() + "f", Math.abs(amount.get()));
        return (amount.get() < 0 ? "-" : "") + currency.symbol() + digits;
    }
}
