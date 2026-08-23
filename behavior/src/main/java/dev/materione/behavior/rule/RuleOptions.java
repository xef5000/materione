package dev.materione.behavior.rule;

/** Rule throttling options: cooldown window and optional charge budget. */
public record RuleOptions(long cooldownMs, Integer charges, String scope) {

    /** Charge/scope key granularity. */
    public static final String PER_PLAYER = "player";
    public static final String PER_GLOBAL = "global";

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
