package dev.materione.api.protection;

import java.util.Objects;

/**
 * What a provider (or the service, over all of them) says about one question: allow, deny, or abstain, and who said it.
 * Abstain means "this is not mine to decide here" (no region of this plugin covers the place): it falls through to the
 * next provider and to the event path, and a verb treats it as permission to go on.
 *
 * @param verdict allow, deny or abstain
 * @param provider the provider's name; for a combined answer, the denying provider's, or the allowing ones' joined
 * @param reason one short phrase a trace or a message may show, or empty
 */
public record ProtectionAnswer(Verdict verdict, String provider, String reason) {

    /** The three answers. */
    public enum Verdict { ALLOW, DENY, ABSTAIN }

    public ProtectionAnswer {
        Objects.requireNonNull(verdict, "verdict");
        provider = provider == null ? "" : provider;
        reason = reason == null ? "" : reason;
    }

    public static ProtectionAnswer allow(String provider) {
        return new ProtectionAnswer(Verdict.ALLOW, provider, "");
    }

    public static ProtectionAnswer deny(String provider, String reason) {
        return new ProtectionAnswer(Verdict.DENY, provider, reason);
    }

    public static ProtectionAnswer abstain(String provider) {
        return new ProtectionAnswer(Verdict.ABSTAIN, provider, "");
    }

    /** Whether the answer is a deny. */
    public boolean denied() {
        return verdict == Verdict.DENY;
    }

    /** What a refused step says: {@code refused by WorldGuard (build is denied here)}. */
    public String describeDenial() {
        String who = provider.isBlank() ? "protection" : provider;
        return "refused by " + who + (reason.isBlank() ? "" : " (" + reason + ")");
    }
}
