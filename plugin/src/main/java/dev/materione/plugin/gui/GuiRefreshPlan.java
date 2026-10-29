package dev.materione.plugin.gui;

import dev.materione.api.NamespacedId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The decisions behind the live refresh (gui.md §5), kept free of Bukkit so they test on the
 * plain JVM. {@link GuiRuntime} applies them on each viewer's region thread.
 *
 * <p>Two questions. Across one swap, which GUIs changed or vanished ({@link #changes}). For one
 * viewer of a changed GUI, what must happen to the window they have open ({@link #viewerAction}).
 * A window's title and size are fixed when it is created, so a new title or new rows need a new
 * window; anything else (the items in the slots) is written into the open window in place.
 */
public final class GuiRefreshPlan {

    /** How one GUI changed between two applies. */
    public enum Change {
        /** Still live, with a different window: rows, title or items. */
        CHANGED,
        /** No longer live: removed from the pack, or quarantined by its compile. */
        REMOVED
    }

    /** What one open window needs. */
    public enum Action {
        /** Same title and rows: rewrite the slot contents of the open window in place. */
        UPDATE_CONTENTS,
        /** The title or the rows changed: open a new window, keeping the cursor item. */
        REOPEN,
        /** The GUI is no longer live: close the window and tell the viewer why. */
        CLOSE
    }

    private GuiRefreshPlan() {}

    /**
     * Every GUI live before the swap that changed or vanished at it, in the order they were
     * compiled. A GUI that is new in {@code after} is not a change: nobody views it yet.
     */
    public static Map<NamespacedId, Change> changes(RuntimeGuis before, RuntimeGuis after) {
        var changed = new LinkedHashMap<NamespacedId, Change>();
        for (RuntimeGuis.Live previous : before.all()) {
            Optional<RuntimeGuis.Live> now = after.gui(previous.id());
            if (now.isEmpty()) {
                changed.put(previous.id(), Change.REMOVED);
            } else if (!now.get().sameAs(previous)) {
                changed.put(previous.id(), Change.CHANGED);
            }
        }
        return changed;
    }

    /**
     * What a viewer's open window needs, given the title and rows it was created with and the
     * GUI as it is after the swap.
     */
    public static Action viewerAction(String openTitle, int openRows, Optional<RuntimeGuis.Live> now) {
        if (now.isEmpty()) {
            return Action.CLOSE;
        }
        RuntimeGuis.Live live = now.get();
        if (live.rows() != openRows || !live.title().equals(openTitle)) {
            return Action.REOPEN;
        }
        return Action.UPDATE_CONTENTS;
    }
}
