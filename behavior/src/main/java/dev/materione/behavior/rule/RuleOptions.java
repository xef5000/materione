package dev.materione.behavior.rule;

/** Rule throttling options: cooldown window and optional charge budget. */
public record RuleOptions(long cooldownMs, Integer charges, String scope) {

    /** Charge/scope key granularity. */
    public static final String PER_PLAYER = "player";
    public static final String PER_GLOBAL = "global";

    /** The rule key of the flow that runs when the rule matched but its cooldown blocked it (logic.md §16). */
    public static final String ON_COOLDOWN = "on_cooldown";

    /** The context root an {@code on_cooldown} run reads; its only member is {@link #REMAINING}. */
    public static final String COOLDOWN_ROOT = "cooldown";

    /** {@code cooldown.remaining}: the whole seconds left in the window, rounded up. */
    public static final String REMAINING = "remaining";

    public static RuleOptions none() {
        return new RuleOptions(0, null, PER_PLAYER);
    }

    public boolean hasCooldown() {
        return cooldownMs > 0;
    }

    public boolean hasCharges() {
        return charges != null && charges > 0;
    }
}
