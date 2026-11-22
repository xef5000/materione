package dev.materione.integrations.protection;

import dev.materione.api.protection.ProtectionAnswer;

/**
 * What one adapter's backend decides, before the provider's name is put on it (protection.md section 9): allow, deny with a
 * reason, or "not mine" (abstain). The adapters' own logic returns these, so a test of an adapter needs no plugin and no
 * server.
 */
public record Decision(ProtectionAnswer.Verdict verdict, String reason) {

    private static final Decision ALLOW = new Decision(ProtectionAnswer.Verdict.ALLOW, "");
    private static final Decision ABSTAIN = new Decision(ProtectionAnswer.Verdict.ABSTAIN, "");

    public static Decision allow() {
        return ALLOW;
    }

    public static Decision deny(String reason) {
        return new Decision(ProtectionAnswer.Verdict.DENY, reason == null ? "" : reason);
    }

    public static Decision abstain() {
        return ABSTAIN;
    }

    /** Allow when {@code allowed}, else deny with {@code reason}. */
    public static Decision of(boolean allowed, String reason) {
        return allowed ? ALLOW : deny(reason);
    }

    /** The answer a port gives, with the provider's {@code name} on it. */
    public ProtectionAnswer answer(String name) {
        return new ProtectionAnswer(verdict, name, reason);
    }
}
