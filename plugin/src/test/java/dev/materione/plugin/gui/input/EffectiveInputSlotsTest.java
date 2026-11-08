package dev.materione.plugin.gui.input;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.gui.TextureSource;
import dev.materione.content.gui.GuiCompiler;
import dev.materione.plugin.gui.GuiFixture;
import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.plugin.gui.render.GuiRenderer;
import dev.materione.plugin.gui.render.RenderReporter;
import dev.materione.plugin.gui.render.Rendered;
import dev.materione.plugin.gui.session.GuiSession;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EffectiveInputSlotsTest {
    @Test
    void inputOwnershipFollowsVisibleCurrentFrameAndReturnsWhenUncovered() {
        var fixture = new GuiFixture();
        var definition = fixture.definition("""
                shop:
                  title: Shop
                  container: chest_1
                  layers:
                    - {id: wells, type: slots, region: container, input: true}
                    - id: tab
                      type: button
                      x: 8
                      y: 18
                      width: 16
                      height: 16
                      visible_if: "gui.session.tab == true"
                      materione:overlay_frames:
                        every: 4
                        layers: [{x: 8}, {x: 26}]
                """);
        var compiled = GuiCompiler.compile(definition, 0xE200, TextureSource.NONE,
                (layer, frame) -> 0xE201 + frame).model();
        var candidates = InputSlots.compile(compiled.inputs(), compiled.id(), null, false);
        assertThat(candidates.isInput(0)).isTrue();
        var hidden = new Rendered(Map.of(), Set.of("tab"), Map.of(), "", Map.of());
        var frame0 = new Rendered(Map.of(), Set.of(), Map.of(), "", Map.of("tab", Set.of(0)));
        var frame1 = new Rendered(Map.of(), Set.of(), Map.of(), "", Map.of("tab", Set.of(1)));
        assertThat(candidates.effective(compiled.clicks(), hidden).isInput(0)).isTrue();
        assertThat(candidates.effective(compiled.clicks(), frame0).isInput(0)).isFalse();
        assertThat(candidates.effective(compiled.clicks(), frame1).isInput(0)).isTrue();
        assertThat(candidates.effective(compiled.clicks(), frame1).isInput(1)).isFalse();
        assertThat(candidates.only(Set.of(1)).isInput(0)).isFalse();

        var live = RuntimeGuis.build(Map.of(compiled.id(), compiled), id -> java.util.Optional.empty(),
                id -> java.util.Optional.empty(), ignored -> { }, fixture.logic.lookup()).gui(compiled.id()).orElseThrow();
        var session = new GuiSession(UUID.randomUUID(), "Probe", compiled.id(), Map.of());
        var renderer = new GuiRenderer(RenderReporter.SILENT);
        session.vars().put("tab", false);
        var hiddenRender = renderer.render(fixture.viewer, session, live, true, 0);
        assertThat(live.inputs().effective(live.clicks(), hiddenRender).isInput(0)).isTrue();
        session.vars().put("tab", true);
        var firstFrame = renderer.render(fixture.viewer, session, live, true, 0);
        var secondFrame = renderer.render(fixture.viewer, session, live, true, 4);
        assertThat(live.inputs().effective(live.clicks(), firstFrame).isInput(0)).isFalse();
        assertThat(live.inputs().effective(live.clicks(), secondFrame).isInput(0)).isTrue();
    }
}
