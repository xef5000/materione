package dev.materione.plugin.logic.action;

import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.plugin.logic.support.ResolvedText;
import dev.materione.platform.text.MaterioneText;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

/**
 * {@code title: {title, subtitle, fade_in, stay, fade_out}}: shows a title card to the player. The
 * three timings are in ticks (20 per second); the defaults are vanilla's 10, 70 and 20.
 */
public final class TitleAction implements ActionType<TitleAction.Params> {

    /** The longest timing accepted: one hour of ticks, far past any sensible title. */
    static final int MAX_TICKS = 72_000;

    /** The parsed step. */
    public record Params(TemplateText title, Optional<TemplateText> subtitle, int fadeIn, int stay, int fadeOut) {}

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
                                "Main line; formatting and & colour codes allowed", null, null),
                        new ParameterSchema("subtitle", ParameterType.STRING, false, null,
                                "Second line", null, null),
                        new ParameterSchema("fade_in", ParameterType.INTEGER, false, "10",
                                "Fade-in in ticks", null, null),
                        new ParameterSchema("stay", ParameterType.INTEGER, false, "70",
                                "Time fully shown, in ticks", null, null),
                        new ParameterSchema("fade_out", ParameterType.INTEGER, false, "20",
                                "Fade-out in ticks", null, null)),
                "2.0.0"), LogicCategory.PLAYER, "title");
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
                params.integer("fade_in", 10, 0, MAX_TICKS),
                params.integer("stay", 70, 0, MAX_TICKS),
                params.integer("fade_out", 20, 0, MAX_TICKS));
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) {
            return ActionOutcome.failed("event has no player");
        }
        Component main = MaterioneText.parse(params.title().render(context));
        Component sub = params.subtitle()
                .map(text -> MaterioneText.parse(text.render(context)))
                .orElse(Component.empty());
        player.showTitle(Title.title(main, sub, Title.Times.times(
                ticks(params.fadeIn()), ticks(params.stay()), ticks(params.fadeOut()))));
        return ActionOutcome.OK;
    }

    private static Duration ticks(int ticks) {
        return Duration.ofMillis(ticks * 50L);
    }

    @Override
    public CompletionStage<ActionOutcome> executeResumable(Params params, LogicContext context, ReadExecutor reads) {
        if (context.facet(Facets.PLAYER).isEmpty()) {
            return CompletableFuture.completedFuture(ActionOutcome.failed("event has no player"));
        }
        var scoped = reads.captureOrigin();
        return ResolvedText.resolve(params.title(), context, scoped)
                .thenCompose(title -> ResolvedText.resolve(params.subtitle(), context, scoped)
                        .thenCompose(subtitle -> scoped.on(threading(), () -> execute(
                                new Params(title, subtitle, params.fadeIn(), params.stay(), params.fadeOut()), context))));
    }
}
