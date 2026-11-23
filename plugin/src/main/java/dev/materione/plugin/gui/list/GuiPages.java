package dev.materione.plugin.gui.list;

import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.gui.event.GuiEvents;
import dev.materione.plugin.gui.live.GuiLiveRefresh;
import dev.materione.plugin.view.ViewerRecord;
import java.util.Optional;
import dev.materione.plugin.gui.session.GuiViewerState;

/**
 * Moves a list of an open window to another page (logic.md §6.7): the one service behind {@code gui.page}. It stores
 * the new page in the session, repaints the window so the list shows it, and then fires {@code gui.page} with the page
 * the window now shows (a re-render may have clamped it). It decides nothing about the entries: the list renders them.
 *
 * <p>Runs on the viewer's thread, from a run that owns the session.
 */
public final class GuiPages {

    /** A page move: a step, the first or last page, or a page number. */
    public enum Move {
        NEXT,
        PREVIOUS,
        FIRST,
        LAST,
        NUMBER
    }

    /**
     * Where to go.
     *
     * @param move the move
     * @param number the page, for {@link Move#NUMBER}; ignored otherwise
     */
    public record Target(Move move, int number) {

        public static Target of(Move move) {
            return new Target(move, 0);
        }

        public static Target page(int number) {
            return new Target(Move.NUMBER, number);
        }
    }

    private final GuiEvents events;
    private final GuiLiveRefresh refresh;

    public GuiPages(GuiEvents events, GuiLiveRefresh refresh) {
        this.events = events;
        this.refresh = refresh;
    }

    /**
     * Moves {@code list} to {@code target} and shows it.
     *
     * @return the reason the page did not move, or empty when it did
     */
    public Optional<String> page(Viewer viewer, ViewerRecord session, String list, Target target) {
        RuntimeGuis.Live live = GuiViewerState.of(session).displayed().orElse(null);
        if (live == null) {
            return Optional.of("the window is not showing this GUI yet");
        }
        if (live.lists().stream().noneMatch(layer -> layer.layerId().equals(list))) {
            return Optional.of("no list named '" + list + "' in this GUI");
        }
        ListPage before = ListPage.of(GuiViewerState.of(session).pages().get(list));
        int wanted = switch (target.move()) {
            case NEXT -> before.number() + 1;
            case PREVIOUS -> before.number() - 1;
            case FIRST -> 1;
            case LAST -> before.count();
            case NUMBER -> target.number();
        };
        int number = ListPager.clamp(wanted, before.count());
        if (number == before.number()) {
            return Optional.empty(); // already on that page (a step past the last one): nothing moved, nothing fires
        }
        Object stored = GuiViewerState.of(session).pages().get(list);
        GuiViewerState.of(session).putPage(list, new ListPage(number, before.count(), before.size()).toMap());
        if (!refresh.repaint(viewer, session)) {
            restore(session, list, stored);
            return Optional.of("the window is not showing this GUI");
        }
        ListPage shown = ListPage.of(GuiViewerState.of(session).pages().get(list));
        events.paged(viewer, session, GuiViewerState.of(session).displayed().orElse(live), list, shown.number(), shown.count());
        return Optional.empty();
    }

    private static void restore(ViewerRecord session, String list, Object stored) {
        GuiViewerState.of(session).putPage(list, stored);
    }
}
