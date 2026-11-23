package dev.materione.plugin.plantedview;

import dev.materione.plugin.gui.session.GuiViewerState;
import dev.materione.plugin.view.ViewerRecord;

/** Fixture for the ban proof: a class outside the view and GUI runtimes that calls a no-argument mutator. */
public final class PlantedViewerMutation {

    public void violate(ViewerRecord record) {
        GuiViewerState.of(record).forgetWindow();
    }
}
