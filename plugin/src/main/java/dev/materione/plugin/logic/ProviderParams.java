package dev.materione.plugin.logic;

import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.provider.Currency;
import dev.materione.api.logic.provider.CurrencyCatalog;
import java.util.regex.Pattern;

/**
 * Parameter rules shared by the provider actions (logic.md §4.5, §9 rule 2). They live in the
 * parent package, not in {@code action}, because one action class may not depend on another.
 */
public final class ProviderParams {

    /** Largest amount an economy step may name: a guard against typos, not a Vault limit. */
    public static final double MAX_AMOUNT = 1.0E12;

    /** A permission node: dot-separated segments of letters, digits, dashes, underscores or {@code *}. */
    private static final Pattern NODE = Pattern.compile("[A-Za-z0-9_*-]+(\\.[A-Za-z0-9_*-]+)*");

    private ProviderParams() {}

    /**
     * The {@code amount} of a money step: required, finite, above zero and at most
     * {@link #MAX_AMOUNT}.
     *
     * @throws IllegalArgumentException with an author-facing message
     */
    public static double amount(ParamReader params) {
        if (params.raw("amount").isEmpty()) {
            throw new IllegalArgumentException("amount is required");
        }
        double value = params.decimal("amount", 0, 0, MAX_AMOUNT);
        if (value <= 0) {
            throw new IllegalArgumentException("amount must be above 0");
        }
        return value;
    }

    /**
     * The optional {@code currency} of a money step: the authored name, or null when omitted (the default
     * currency). Shape only; whether the name is defined is answered when the step runs, because the
     * currencies a pack defines are live state (logic.md §17, the ordering note there).
     */
    public static String currency(ParamReader params) {
        return params.optionalText("currency").orElse(null);
    }

    /**
     * The currency a step means: the one named, or the default when {@code authored} is null.
     *
     * @return the currency, or null when the name matches nothing defined
     */
    public static Currency resolve(CurrencyCatalog catalog, String authored) {
        return authored == null ? catalog.defaultCurrency() : catalog.resolve(authored).orElse(null);
    }

    /**
     * The {@code permission} of a permission step: required and shaped like a node.
     *
     * @throws IllegalArgumentException with an author-facing message
     */
    public static String node(ParamReader params) {
        String node = params.text("permission");
        if (!NODE.matcher(node).matches()) {
            throw new IllegalArgumentException(
                    "permission must be a node such as example.fly (letters, digits, dots, dashes, underscores)");
        }
        return node;
    }
}
