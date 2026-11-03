package dev.materione.plugin.logic.action.gui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ParamReader;
import dev.materione.behavior.logic.EngineExpressions;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.plugin.gui.GuiBridge;
import dev.materione.plugin.gui.list.GuiPages;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code gui.page} as authored (logic.md §6.7): its target parses once, and a bad one is refused at load. */
class PageGuiActionTest {

    private final PageGuiAction action = new PageGuiAction(new GuiBridge());

    private PageGuiAction.Params parse(Object to) {
        return action.parse(ParamReader.of(Map.of("list", "players", "to", to)));
    }

    @Test
    void aWordOrAPageNumberOrATemplateIsParsedToItsMove() {
        assertThat(parse("next")).isEqualTo(new PageGuiAction.Params("players",
                new PageGuiAction.Keyword(GuiPages.Move.NEXT)));
        assertThat(parse(" Previous ").to()).isEqualTo(new PageGuiAction.Keyword(GuiPages.Move.PREVIOUS));
        assertThat(parse("first").to()).isEqualTo(new PageGuiAction.Keyword(GuiPages.Move.FIRST));
        assertThat(parse("LAST").to()).isEqualTo(new PageGuiAction.Keyword(GuiPages.Move.LAST));
        assertThat(parse(3).to()).isEqualTo(new PageGuiAction.Page(3));
        PageGuiAction.Params computed = action.parse(ParamReader.of(
                Map.of("list", "players", "to", "${vars.page}"), EngineExpressions.INSTANCE));
        assertThat(computed.to()).isInstanceOf(PageGuiAction.Computed.class);
    }

    @Test
    void aStepThatNamesNoPageOrNoListIsRefusedWithTheReason() {
        assertThatThrownBy(() -> action.parse(ParamReader.of(Map.of("list", "players"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("to is required");
        assertThatThrownBy(() -> parse("sideways"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("next, previous, first, last, or a page number");
        assertThatThrownBy(() -> parse(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("to must be an integer");
        assertThatThrownBy(() -> action.parse(ParamReader.of(Map.of("to", "next"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("list is required");
    }

    @Test
    void theStepFailsSoftWhenTheGuiRuntimeIsNotBound() {
        var root = new LinkedHashMap<String, Object>();
        root.put("vars", new LinkedHashMap<String, Object>());
        ActionOutcome outcome = action.execute(parse("next"), new MapLogicContext(root));

        assertThat(outcome).isInstanceOf(ActionOutcome.Failed.class);
        assertThat(((ActionOutcome.Failed) outcome).reason()).isEqualTo("the GUI runtime is not available");
    }

    @Test
    void theActionDescribesItsTwoParameters() {
        assertThat(action.id()).isEqualTo("gui.page");
        assertThat(action.describe().self().parameters()).extracting(parameter -> parameter.id())
                .containsExactly("list", "to");
        assertThat(action.describe().category().id()).isEqualTo("gui");
    }
}
