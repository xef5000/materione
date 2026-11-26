package dev.materione.plugin.logic.action.view;

import dev.materione.api.NamespacedId;
import dev.materione.api.content.ViewFacet;
import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.variable.StackRef;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.plugin.view.ViewBridge;
import dev.materione.plugin.view.ViewKinds;
import dev.materione.plugin.view.ViewParams;
import dev.materione.plugin.view.ViewRun;
import dev.materione.plugin.view.open.OpenRequest;
import dev.materione.plugin.view.open.OpenResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * {@code view.open: shop} or {@code view.open: {view: shop, args: {category: tools}}}: opens a view for the player
 * through the kind's opener and the shared {@code OpenGate} (logic.md §6.1, views.md §3.6), replacing the window they
 * have of that kind. One verb for every {@code opened} kind (a GUI now, a dialog with #6): it resolves the reference
 * across the registered kinds and hands the open to the one that owns the definition, so it names no kind. It works
 * from any rule (an item, a block, a click), not only from inside a view; when the run is in a view, that view goes on
 * the player's view history.
 *
 * <p>The name is a qualified id, or a bare name that means the definition of that name in the run's own pack, else the
 * only definition of that name. Naming a view whose kind is not {@code opened} (a HUD, which shows itself) is a load
 * diagnostic when the id is written statically, and fails here when it is not.
 */
public final class OpenViewAction implements ActionType<OpenViewAction.Params> {

    /** The parsed step: the view as written, and the argument templates. */
    public record Params(String view, String kind, Map<String, TemplateText> args) {

        public Params {
            args = Map.copyOf(args);
        }
    }

    private final ViewBridge bridge;

    public OpenViewAction(ViewBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public String id() {
        return "view.open";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Open view",
                "Opens a view for the player, replacing the one they have of that kind; "
                        + "the old one goes on the view history",
                List.of(
                        new ParameterSchema("view", ParameterType.REFERENCE, true, null,
                                "The view to open: pack:id, or a name in this view's pack", null, null)
                                .withWidget("reference", "view"),
                        new ParameterSchema("kind", ParameterType.STRING, false, null,
                                "The view kind (gui, dialog) when the id is shared by two kinds, which an id alone cannot "
                                        + "tell apart", null, null),
                        new ParameterSchema("args", ParameterType.RAW, false, null,
                                "Arguments the view opens with, name to text; templates allowed", null, null)),
                "1.0.0"), LogicCategory.GUI, "view");
    }

    @Override
    public Threading threading() {
        return Threading.PLAYER_REGION;
    }

    @Override
    public Params parse(ParamReader params) {
        String view = params.text("view");
        var args = new LinkedHashMap<String, TemplateText>();
        if (params.has("args")) {
            ParamReader section = params.section("args");
            for (String name : section.keys()) {
                args.put(name, section.template(name));
            }
        }
        return new Params(view, ViewParams.optional(params, "kind"), args);
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        Prepared prepared = prepare(params, context);
        if (prepared.failure() != null) {
            return ActionOutcome.failed(prepared.failure());
        }
        var args = new LinkedHashMap<String, String>();
        params.args().forEach((name, template) -> args.put(name, template.render(context)));
        return open(prepared, args);
    }

    @Override
    public CompletionStage<ActionOutcome> executeResumable(
            Params params, LogicContext context, ReadExecutor reads) {
        return reads.on(threading(), () -> prepare(params, context)).thenCompose(prepared -> {
            if (prepared.failure() != null) {
                return CompletableFuture.completedFuture(ActionOutcome.failed(prepared.failure()));
            }
            CompletionStage<Map<String, String>> arguments = CompletableFuture.completedFuture(new LinkedHashMap<>());
            for (var entry : params.args().entrySet()) {
                arguments = arguments.thenCompose(values -> entry.getValue().renderResumable(context, reads)
                        .thenApply(value -> {
                            values.put(entry.getKey(), value);
                            return values;
                        }));
            }
            return arguments.thenCompose(args -> reads.on(threading(), () -> open(prepared, args)));
        });
    }

    private Prepared prepare(Params params, LogicContext context) {
        ViewRun run;
        try {
            run = ViewRun.ofPlayer(bridge, context);
        } catch (ViewRun.Refused refused) {
            return new Prepared(null, null, null, refused.getMessage());
        }
        NamespacedId near = run.record() == null ? null : run.record().definition();
        String nearKind = run.record() == null ? null : run.record().kind();
        ViewKinds.Resolution resolution = run.services().kinds().resolve(params.view(), near, nearKind, params.kind());
        if (!resolution.ok()) {
            return new Prepared(run, null, null, resolution.problem());
        }
        ViewKinds.Resolved target = resolution.found();
        if (target.kind().facet().viewerRule() != ViewFacet.ViewerRule.OPENED) {
            return new Prepared(run, null, null, "'" + params.view() + "' is a " + target.kind().contentType()
                    + ", which shows itself to players for whom its condition holds; no verb opens it");
        }
        return new Prepared(run, target, context.variables().stack().orElse(null), null);
    }

    private static ActionOutcome open(Prepared prepared, Map<String, String> args) {
        OpenResult result = prepared.target().kind().open(OpenRequest.of(
                prepared.run().viewer(), prepared.target().kind().contentType(), prepared.target().id(), args,
                OpenRequest.Cause.ACTION, prepared.stack()));
        return result.ok() ? ActionOutcome.OK : ActionOutcome.failed(result.reason());
    }

    private record Prepared(ViewRun run, ViewKinds.Resolved target, StackRef stack, String failure) {}
}
