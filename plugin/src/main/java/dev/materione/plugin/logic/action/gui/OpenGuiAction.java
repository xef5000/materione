package dev.materione.plugin.logic.action.gui;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.plugin.gui.GuiBridge;
import dev.materione.plugin.gui.GuiRun;
import dev.materione.plugin.gui.open.OpenRequest;
import dev.materione.plugin.gui.open.OpenResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * {@code gui.open: shop} or {@code gui.open: {gui: shop, args: {category: tools}}}: opens another GUI
 * for the player through the opener (logic.md §6.1), replacing the window they have. It works from any
 * rule (an item, a block, a click), not only from inside a GUI; when the run is in a GUI, that GUI goes
 * on the back stack. The name is a qualified id, or a bare name that means the GUI of that name in this
 * GUI's pack, else the only GUI of that name.
 */
public final class OpenGuiAction implements ActionType<OpenGuiAction.Params> {

    /** The parsed step: the GUI as written, and the argument templates. */
    public record Params(String gui, Map<String, TemplateText> args) {

        public Params {
            args = Map.copyOf(args);
        }
    }

    private final GuiBridge bridge;

    public OpenGuiAction(GuiBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public String id() {
        return "gui.open";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Open GUI",
                "Opens a GUI for the player, replacing the window they have; the old one goes on the back stack",
                List.of(
                        new ParameterSchema("gui", ParameterType.REFERENCE, true, null,
                                "The GUI to open: pack:id, or a name in this GUI's pack", null, null),
                        new ParameterSchema("args", ParameterType.RAW, false, null,
                                "Arguments the GUI opens with, name to text; templates allowed", null, null)),
                "1.0.0"), LogicCategory.GUI, "gui");
    }

    @Override
    public Threading threading() {
        return Threading.PLAYER_REGION;
    }

    @Override
    public Params parse(ParamReader params) {
        String gui = params.text("gui");
        var args = new LinkedHashMap<String, TemplateText>();
        if (params.has("args")) {
            ParamReader section = params.section("args");
            for (String name : section.keys()) {
                args.put(name, section.template(name));
            }
        }
        return new Params(gui, args);
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        GuiRun run;
        try {
            run = GuiRun.ofPlayer(bridge, context);
        } catch (GuiRun.Refused refused) {
            return ActionOutcome.failed(refused.getMessage());
        }
        NamespacedId near = run.session() == null ? null : run.session().gui();
        Optional<NamespacedId> target = run.services().guis().resolve(params.gui(), near);
        if (target.isEmpty()) {
            return ActionOutcome.failed("no live GUI matches '" + params.gui() + "'");
        }
        var args = new LinkedHashMap<String, String>();
        params.args().forEach((name, template) -> args.put(name, template.render(context)));
        OpenResult result = run.services().opener().open(
                OpenRequest.of(run.viewer(), target.get(), args, OpenRequest.Cause.ACTION));
        return result.ok() ? ActionOutcome.OK : ActionOutcome.failed(result.reason());
    }

    @Override public CompletionStage<ActionOutcome> executeResumable(
            Params params, LogicContext context, ReadExecutor reads) {
        return reads.on(threading(), () -> {
            try {
                GuiRun run = GuiRun.ofPlayer(bridge, context);
                NamespacedId near = run.session() == null ? null : run.session().gui();
                return new Prepared(run, run.services().guis().resolve(params.gui(), near).orElse(null), null);
            } catch (GuiRun.Refused refused) {
                return new Prepared(null, null, refused.getMessage());
            }
        }).thenCompose(prepared -> {
            if (prepared.failure() != null) {
                return CompletableFuture.completedFuture(ActionOutcome.failed(prepared.failure()));
            }
            if (prepared.target() == null) {
                return CompletableFuture.completedFuture(
                        ActionOutcome.failed("no live GUI matches '" + params.gui() + "'"));
            }
            CompletionStage<Map<String, String>> arguments = CompletableFuture.completedFuture(new LinkedHashMap<>());
            for (var entry : params.args().entrySet()) {
                arguments = arguments.thenCompose(values -> entry.getValue().renderResumable(context, reads)
                        .thenApply(value -> {
                            values.put(entry.getKey(), value);
                            return values;
                        }));
            }
            return arguments.thenCompose(args -> reads.on(threading(), () -> {
                OpenResult result = prepared.run().services().opener().open(
                        OpenRequest.of(prepared.run().viewer(), prepared.target(), args, OpenRequest.Cause.ACTION));
                return result.ok() ? ActionOutcome.OK : ActionOutcome.failed(result.reason());
            }));
        });
    }

    private record Prepared(GuiRun run, NamespacedId target, String failure) {}
}
