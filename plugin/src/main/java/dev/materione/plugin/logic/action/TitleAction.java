package dev.materione.plugin.logic.action;

import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Quantity;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.logic.Subjects;
import dev.materione.api.logic.TargetKind;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.plugin.logic.support.Quantities;
import dev.materione.plugin.logic.support.ResolvedText;
import dev.materione.platform.text.MaterioneText;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

/**
 * {@code title: {title, subtitle, fade_in, stay, fade_out}}: shows a title card to the player. The
 * three timings are in ticks (20 per second); the defaults are vanilla's 10, 70 and 20. Each timing takes a
 * number or a template that renders to one (logic.md section 42, #470): a literal is checked at load, and a timing that
 * renders out of range refuses the step under {@code MTR-0120}, with no title shown.
 */
public final class TitleAction implements ActionType<TitleAction.Params> {

    /** The longest timing accepted: one hour of ticks, far past any sensible title. */
    static final int MAX_TICKS = 72_000;

    /** The parsed step. */
    public record Params(TemplateText title, Optional<TemplateText> subtitle, Quantity fadeIn, Quantity stay,
            Quantity fadeOut) {}

    @Override
    public String id() {
        return "title";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Title",
                "Shows a title and optional subtitle on the player's screen",
                List.of(
                        new ParameterSchema("title", ParameterType.STRING, true, null,
                                "Main line; formatting and & colour codes allowed", null, null).acceptingExpression(),
                        new ParameterSchema("subtitle", ParameterType.STRING, false, null,
                                "Second line", null, null).acceptingExpression(),
                        new ParameterSchema("fade_in", ParameterType.INTEGER, false, "10",
                                "Fade-in in ticks; or a template that renders to them", null, null).acceptingExpression(),
                        new ParameterSchema("stay", ParameterType.INTEGER, false, "70",
                                "Time fully shown, in ticks; or a template that renders to them", null, null)
                                .acceptingExpression(),
                        new ParameterSchema("fade_out", ParameterType.INTEGER, false, "20",
                                "Fade-out in ticks; or a template that renders to them", null, null).acceptingExpression()),
                "2.0.0"), LogicCategory.PLAYER, "title").withTargets(TargetKind.PLAYER);
    }

    @Override
    public Threading threading() {
        return Threading.PLAYER_REGION;
    }

    @Override
    public Params parse(ParamReader params) {
        return new Params(
                params.template("title"),
                params.optionalTemplate("subtitle"),
                Quantity.integerOr(params, "fade_in", 10, 0, MAX_TICKS),
                Quantity.integerOr(params, "stay", 70, 0, MAX_TICKS),
                Quantity.integerOr(params, "fade_out", 20, 0, MAX_TICKS));
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        Player player = Subjects.player(context).orElse(null);
        if (player == null) {
            return ActionOutcome.failed("event has no player");
        }
        OptionalInt fadeIn = params.fadeIn().wholeValue(context);
        if (fadeIn.isEmpty()) {
            return ActionOutcome.refused(DiagnosticCodes.QUANTITY_REFUSED, params.fadeIn().refusal("fade_in", context));
        }
        OptionalInt stay = params.stay().wholeValue(context);
        if (stay.isEmpty()) {
            return ActionOutcome.refused(DiagnosticCodes.QUANTITY_REFUSED, params.stay().refusal("stay", context));
        }
        OptionalInt fadeOut = params.fadeOut().wholeValue(context);
        if (fadeOut.isEmpty()) {
            return ActionOutcome.refused(DiagnosticCodes.QUANTITY_REFUSED, params.fadeOut().refusal("fade_out", context));
        }
        Component main = MaterioneText.parse(params.title().render(context));
        Component sub = params.subtitle()
                .map(text -> MaterioneText.parse(text.render(context)))
                .orElse(Component.empty());
        player.showTitle(Title.title(main, sub, Title.Times.times(
                ticks(fadeIn.getAsInt()), ticks(stay.getAsInt()), ticks(fadeOut.getAsInt()))));
        return ActionOutcome.OK;
    }

    private static Duration ticks(int ticks) {
        return Duration.ofMillis(ticks * 50L);
    }

    @Override
    public CompletionStage<ActionOutcome> executeResumable(Params params, LogicContext context, ReadExecutor reads) {
        if (Subjects.player(context).isEmpty()) {
            return CompletableFuture.completedFuture(ActionOutcome.failed("event has no player"));
        }
        var scoped = reads.captureOrigin();
        return ResolvedText.resolve(params.title(), context, scoped)
                .thenCompose(title -> ResolvedText.resolve(params.subtitle(), context, scoped)
                        .thenCompose(subtitle -> Quantities.resolve(context, scoped,
                                params.fadeIn(), params.stay(), params.fadeOut())
                                .thenCompose(timings -> reads.on(threading(), () -> execute(
                                        new Params(title, subtitle, timings[0], timings[1], timings[2]), context)))));
    }
}
