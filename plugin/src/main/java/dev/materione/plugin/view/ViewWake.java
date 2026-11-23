package dev.materione.plugin.view;

/**
 * What the write observer calls when a write takes a viewer record or a candidate from clean to dirty (views.md
 * section 5.4): schedule one render on the owner's thread. It is called once per clean-to-dirty transition, on the
 * writer's thread, and must not touch viewer-local state.
 */
@FunctionalInterface
public interface ViewWake {

    /** No scheduler: the bit is set and nothing renders. */
    ViewWake NONE = target -> { };

    void wake(ViewDirty target);
}
