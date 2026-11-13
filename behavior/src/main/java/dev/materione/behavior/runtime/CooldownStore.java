package dev.materione.behavior.runtime;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rule throttling state: cooldown windows (per rule+scope key) and charge
 * budgets. In-memory and swept lazily on access — restarts reset cooldowns,
 * which is acceptable for v1 (documented; persistent charges arrive with
 * per-item-instance PDC work later).
 */
public final class CooldownStore {

    /** Sweep threshold: beyond this many live keys, expired entries are pruned on write. */
    private static final int SWEEP_THRESHOLD = 4096;

    private final Map<String, Long> cooldownUntil = new ConcurrentHashMap<>();
    private final Map<String, Integer> chargesUsed = new ConcurrentHashMap<>();

    /**
     * Returns true when the key may fire now, recording the new window
     * ({@code nowMs + cooldownMs}) when {@code cooldownMs > 0}.
     */
    public boolean tryFire(String key, long nowMs, long cooldownMs) {
        return tryFireRemaining(key, nowMs, cooldownMs) == 0;
    }

    /**
     * As {@link #tryFire}, but says how long a blocked key still waits: returns 0 when the key may fire now (the new
     * window is recorded), otherwise the milliseconds left in the open window, which is positive (logic.md §16, the
     * {@code cooldown.remaining} of an {@code on_cooldown} flow).
     */
    public long tryFireRemaining(String key, long nowMs, long cooldownMs) {
        if (cooldownMs <= 0) {
            return 0;
        }
        long[] remaining = {0};
        cooldownUntil.compute(key, (k, until) -> {
            if (until != null && nowMs < until) {
                remaining[0] = until - nowMs;
                return until;
            }
            return nowMs + cooldownMs;
        });
        return remaining[0];
    }

    /** True when the key has at least one unused charge of {@code max} (null max = unlimited). */
    public boolean trySpendCharge(String key, Integer max) {
        if (max == null || max <= 0) {
            return true;
        }
        int used = chargesUsed.merge(key, 1, Integer::sum);
        return used <= max;
    }

    /** Live keys — doctor/debug surface. */
    public int trackedKeys() {
        sweepIfNeeded(System.currentTimeMillis());
        return cooldownUntil.size() + chargesUsed.size();
    }

    private void sweepIfNeeded(long nowMs) {
        if (cooldownUntil.size() < SWEEP_THRESHOLD) {
            return;
        }
        Iterator<Map.Entry<String, Long>> it = cooldownUntil.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() < nowMs) {
                it.remove();
            }
        }
    }
}
