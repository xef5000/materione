package dev.materione.plugin.gui;

import dev.materione.api.NamespacedId;
import dev.materione.api.content.ViewFacet;
import dev.materione.plugin.gui.live.GuiLiveRefresh;
import dev.materione.plugin.gui.open.GuiLifecycle;
import dev.materione.plugin.gui.open.GuiOpener;
import dev.materione.plugin.gui.session.GuiViewerState;
import dev.materione.plugin.view.ViewKind;
import dev.materione.plugin.view.ViewerRecord;
import dev.materione.plugin.view.open.OpenRequest;
import dev.materione.plugin.view.open.OpenResult;
import java.util.Objects;
import java.util.Optional;

/**
 * The container GUI as a {@link ViewKind} (views.md §3.2, §4.2): the contract {@code view.open}, {@code view.close}
 * and {@code view.back} reach it through. It holds no logic of its own; opening is {@link GuiOpener}, ending is
 * {@link GuiLifecycle}, rendering is {@link GuiLiveRefresh}, naming is {@link GuiLookup}.
 *
 * <p>Its viewers are {@code opened} (a verb attaches them), a change to what it reads re-renders it by default
 * (row V1 builds that), and the fewest ticks between two automatic renders of one viewer is 1.
 */
public final class GuiViewKind implements ViewKind {

    /** The kind's facet; {@link dev.materione.content.gui.GuiType#view()} declares the same one in the self-description. */
    public static final ViewFacet FACET = new ViewFacet(
            ViewFacet.ViewerRule.OPENED, ViewFacet.RerenderPolicy.ON_CHANGE, false, false);

    private final GuiLookup guis;
    private final GuiOpener opener;
    private final GuiLifecycle lifecycle;
    private final GuiLiveRefresh refresh;

    public GuiViewKind(GuiLookup guis, GuiOpener opener, GuiLifecycle lifecycle, GuiLiveRefresh refresh) {
        this.guis = Objects.requireNonNull(guis, "guis");
        this.opener = Objects.requireNonNull(opener, "opener");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.refresh = Objects.requireNonNull(refresh, "refresh");
    }

    @Override
    public String contentType() {
        return GuiViewerState.KIND;
    }

    @Override
    public ViewFacet facet() {
        return FACET;
    }

    @Override
    public int minInterval() {
        return 1;
    }

    @Override
    public Optional<NamespacedId> resolve(String written, NamespacedId near) {
        return guis.resolve(written, near);
    }

    @Override
    public OpenResult open(OpenRequest request) {
        return opener.open(request);
    }

    @Override
    public boolean close(Viewer viewer, ViewerRecord record) {
        return lifecycle.closeByAction(viewer, record);
    }

    @Override
    public boolean render(Viewer viewer, ViewerRecord record) {
        return refresh.refresh(viewer, record);
    }
}
