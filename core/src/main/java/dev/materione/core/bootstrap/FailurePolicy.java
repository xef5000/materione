package dev.materione.core.bootstrap;

/** What happens when a bootstrap phase throws (plugin architecture §1.1). */
public enum FailurePolicy {
    /** Record a diagnostic, keep going — the invariant "no phase may crash onEnable". */
    DEGRADE,
    /** Stop bootstrapping and cleanly disable the plugin (e.g. floor check failed). */
    DISABLE
}
