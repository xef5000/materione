package dev.materione.plugin.view;

/**
 * Checks that viewer-local state changes only on its owner thread (views.md §5.6, D8). Production installs none:
 * the threading discipline is the gateway's, and a check on every mutation would be cost for nothing. The ban test
 * installs a recording guard and drives the runtime through a recording gateway, so a change that mutates a viewer
 * record from another thread fails the build, not a Folia server.
 */
@FunctionalInterface
public interface MutationGuard {

    /** Checks nothing. */
    MutationGuard NONE = (record, what) -> { };

    /**
     * Called by a {@link ViewerRecord} (or its payload) before it changes viewer-local state.
     *
     * @param record the record about to change
     * @param what a short name for the state, for the report
     */
    void mutating(ViewerRecord record, String what);
}
