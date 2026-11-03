package dev.materione.plugin.logic.action.gui;

import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.plugin.gui.GuiBridge;
import dev.materione.plugin.gui.GuiRun;
import dev.materione.plugin.gui.list.GuiPages;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@code gui.page: {list: players, to: next}}: moves a list of the open GUI to another page (logic.md §6.7). {@code to}
 * is {@code next}, {@code previous}, {@code first}, {@code last}, a page number, or a template that renders one of
 * those. The run must belong to the open window, as for {@code gui.refresh}. The window shows the new page and
 * {@code gui.page} runs; a page outside the list is clamped, not refused.
 */
public final class PageGuiAction implements ActionType<PageGuiAction.Params> {

    private static final Map<String, GuiPages.Move> MOVES = Map.of(
            "next", GuiPages.Move.NEXT,
            "previous", GuiPages.Move.PREVIOUS,
            "first", GuiPages.Move.FIRST,
            "last", GuiPages.Move.LAST);

    /** Where the step goes, as authored. */
    public sealed interface To permits Keyword, Page, Computed {}

    /** A word: next, previous, first or last. */
    public record Keyword(GuiPages.Move move) implements To {}

    /** A page number, from 1. */
    public record Page(int number) implements To {}

    /** A template, rendered when the step runs to a word or a page number. */
    public record Computed(TemplateText text) implements To {}

    /**
     * The parsed step.
     *
     * @param list the list layer's id
     * @param to where to go
     */
    public record Params(String list, To to) {}

    private final GuiBridge bridge;

    public PageGuiAction(GuiBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public String id() {
        return "gui.page";
    }

    @Override
    public ActionDescription describe() {
        return ActionDescription.of(new SelfDescription("Page list",
                "Moves a list of the open GUI to another page: next, previous, first, last, or a page number. "
                        + "The window shows the page and gui.page runs",
                List.of(
                        new ParameterSchema("list", ParameterType.STRING, true, null,
                                "The list layer's id", null, null),
                        new ParameterSchema("to", ParameterType.STRING, true, null,
                                "next, previous, first, last, or a page number; a template may give one", null, null)),
                "1.0.0"), LogicCategory.GUI);
    }

    @Override
    public Threading threading() {
        return Threading.PLAYER_REGION;
    }

    @Override
    public Params parse(ParamReader params) {
        String list = params.text("list");
        Object raw = params.raw("to").orElseThrow(() -> new IllegalArgumentException(
                "to is required: next, previous, first, last, or a page number"));
        if (raw instanceof String text) {
            GuiPages.Move move = MOVES.get(text.trim().toLowerCase(Locale.ROOT));
            if (move != null) {
                return new Params(list, new Keyword(move));
            }
            if (text.contains("${")) {
                return new Params(list, new Computed(params.template("to")));
            }
            throw new IllegalArgumentException(
                    "to is next, previous, first, last, or a page number, not '" + text + "'");
        }
        return new Params(list, new Page(params.requiredInteger("to", 1, Integer.MAX_VALUE)));
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        GuiRun run;
        try {
            run = GuiRun.ofOpenSession(bridge, context);
        } catch (GuiRun.Refused refused) {
            return ActionOutcome.failed(refused.getMessage());
        }
        GuiPages.Target target;
        try {
            target = targetOf(params.to(), context);
        } catch (IllegalArgumentException notAPage) {
            return ActionOutcome.failed(notAPage.getMessage());
        }
        Optional<String> failure = run.services().pages().page(run.viewer(), run.session(), params.list(), target);
        return failure.map(ActionOutcome::failed).orElse(ActionOutcome.OK);
    }

    /** The move a step means: a template is rendered now and must read as a word or a page number. */
    private static GuiPages.Target targetOf(To to, LogicContext context) {
        return switch (to) {
            case Keyword keyword -> GuiPages.Target.of(keyword.move());
            case Page page -> GuiPages.Target.page(page.number());
            case Computed computed -> {
                String text = computed.text().render(context).trim();
                GuiPages.Move move = MOVES.get(text.toLowerCase(Locale.ROOT));
                if (move != null) {
                    yield GuiPages.Target.of(move);
                }
                try {
                    yield GuiPages.Target.page(Integer.parseInt(text));
                } catch (NumberFormatException notANumber) {
                    throw new IllegalArgumentException("to is next, previous, first, last, or a page number, not '"
                            + text + "'");
                }
            }
        };
    }
}
