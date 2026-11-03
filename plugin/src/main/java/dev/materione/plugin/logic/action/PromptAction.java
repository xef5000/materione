package dev.materione.plugin.logic.action;

import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.InputMethod;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.LogicRegistry;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ParseRejection;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.variable.VariableRef;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.plugin.gui.prompt.PromptReply;
import dev.materione.plugin.gui.prompt.PromptService;
import dev.materione.plugin.gui.session.GuiSession;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.entity.Player;

/**
 * {@code prompt}: asks the player for a line of text and resumes with the answer (logic.md §6.6).
 *
 * <pre>{@code
 * - prompt: { method: chat, text: "Search for an item", var: session.search, timeout: 60, cancel_word: cancel }
 *   else:
 *     - message: "No search."
 * - gui.refresh
 * }</pre>
 *
 * <p>The step parks the run until the answer comes, and counts against the player's parked-run budget. The answer is
 * written to {@code var} (a run-local name, or {@code session.<name>} for the open GUI's variables) and the run goes on.
 * A question that goes unanswered (a cancel word, an empty answer, a timeout, a quit, a method that cannot start) runs
 * the {@code else:} steps and ends the run: the steps after the prompt were written to use the answer, so they never
 * run without one. The engine wraps the step in a {@code require} for this ({@link ActionType#takesElse()}).
 *
 * <p>One question per player at a time. A prompt started while another waits is refused: the run ends through its
 * {@code else:} and the player is told. A window the player has open is hidden for a chat or anvil question and
 * shown again with the answer, for the same session, so the rest of the run still reaches the GUI.
 *
 * <p>Declared variables cannot be answered into: a persistent target is rejected at load ({@code MTR-0082}), and so is
 * a method no one registered ({@code MTR-0081}).
 */
public final class PromptAction implements ActionType<PromptAction.Params> {

    /** Where the answer goes: a run-local variable, or a variable of the open GUI session. */
    public sealed interface Target permits Local, Session {}

    /** A run-local variable. */
    public record Local(String name) implements Target {}

    /** A variable of the open GUI session ({@code gui.session.<name>}). */
    public record Session(String name) implements Target {}

    /**
     * The parsed step.
     *
     * @param method the input method that asks
     * @param text the question, rendered when the step runs
     * @param target where the answer goes
     * @param timeoutSeconds how long the player may take to answer
     * @param cancelWord the word that cancels the question, or null for none
     */
    public record Params(InputMethod method, TemplateText text, Target target, int timeoutSeconds, String cancelWord) {}

    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final String DEFAULT_CANCEL_WORD = "cancel";

    private final LogicRegistry<InputMethod> methods;
    private final PromptService prompts;

    /**
     * @param methods the input methods a {@code method:} names, resolved at load
     * @param prompts the prompt registry the step asks through
     */
    public PromptAction(LogicRegistry<InputMethod> methods, PromptService prompts) {
        this.methods = methods;
        this.prompts = prompts;
    }

    @Override
    public String id() {
        return "prompt";
    }

    @Override
    public ActionDescription describe() {
        return ActionDescription.of(new SelfDescription("Prompt",
                "Asks the player for a line of text (in chat or in an anvil) and writes the answer to a "
                        + "variable; an unanswered question runs its else: and ends the run",
                List.of(
                        new ParameterSchema("method", ParameterType.STRING, false, "chat",
                                "How the player answers", List.of("chat", "sign", "anvil"), null),
                        new ParameterSchema("text", ParameterType.STRING, true, null,
                                "What the player is asked; a template may use variables", null, null),
                        new ParameterSchema("var", ParameterType.STRING, true, null,
                                "Where the answer goes: a run-local name, or session.<name> for the open GUI",
                                null, null),
                        new ParameterSchema("timeout", ParameterType.INTEGER, false,
                                String.valueOf(DEFAULT_TIMEOUT_SECONDS),
                                "Seconds the player has to answer, from 1 to 600", null, null),
                        new ParameterSchema("cancel_word", ParameterType.STRING, false, DEFAULT_CANCEL_WORD,
                                "A word that cancels the question; empty for none", null, null)),
                "1.0.0"), LogicCategory.GUI);
    }

    @Override
    public Threading threading() {
        return Threading.PLAYER_REGION;
    }

    @Override
    public boolean takesElse() {
        return true;
    }

    @Override
    public Params parse(ParamReader params) {
        String methodId = params.text("method", "chat");
        InputMethod method = methods.resolve(methodId).orElseThrow(() -> new ParseRejection(
                DiagnosticCodes.PROMPT_METHOD_UNKNOWN,
                "method '" + methodId + "' is not an input method (chat, sign or anvil)"));
        Target target = targetOf(params.text("var"), params.environment().pack());
        TemplateText text = params.template("text");
        int timeout = params.integer("timeout", DEFAULT_TIMEOUT_SECONDS, 1, PromptService.MAX_TIMEOUT_SECONDS);
        Object rawCancel = params.raw("cancel_word").orElse(DEFAULT_CANCEL_WORD);
        if (!(rawCancel instanceof String word)) {
            throw new IllegalArgumentException("cancel_word is a word, or an empty string for none");
        }
        String cancel = word.trim();
        return new Params(method, text, target, timeout, cancel.isEmpty() ? null : cancel);
    }

    /** The target of the answer: a persistent variable is refused, because a question is not a declared write. */
    private static Target targetOf(String var, String ownPack) {
        VariableRef ref;
        try {
            ref = VariableRef.parse(var, ownPack);
        } catch (IllegalArgumentException malformed) {
            throw new ParseRejection(DiagnosticCodes.PROMPT_TARGET_INVALID, malformed.getMessage());
        }
        return switch (ref) {
            case VariableRef.Local local -> new Local(local.name());
            case VariableRef.Session session -> new Session(session.name());
            case VariableRef.Persistent persistent -> throw new ParseRejection(DiagnosticCodes.PROMPT_TARGET_INVALID,
                    "'" + var + "' is a persistent variable; a prompt answers into a run-local or session variable");
        };
    }

    /** Only the flow interpreter runs a prompt: it parks there. Reached only by a caller that bypasses it. */
    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        return ActionOutcome.failed("a prompt waits for the player and runs only inside a flow");
    }

    /**
     * Starts the question on the player's thread and returns the stage the run parks on. The stage completes when the
     * question has ended: with {@link ActionOutcome#OK} once the answer is written, or with the reason it went
     * unanswered.
     */
    @Override
    public CompletionStage<ActionOutcome> executeResumable(Params params, LogicContext context, ReadExecutor reads) {
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) {
            return CompletableFuture.completedFuture(ActionOutcome.failed("event has no player"));
        }
        GuiSession session = context.facet(GuiSession.FACET).filter(GuiSession::isOpen).orElse(null);
        if (params.target() instanceof Session target && session == null) {
            String reason = "session." + target.name() + ": no open gui";
            prompts.report(DiagnosticCodes.PROMPT_METHOD_FAILED, "a prompt answers into session." + target.name()
                    + ", but no GUI is open for " + player.getName());
            return CompletableFuture.completedFuture(ActionOutcome.failed(reason));
        }
        String question = params.text().render(context);
        return prompts.begin(new PromptService.Request(player, params.method(), question, params.cancelWord(),
                params.timeoutSeconds(), reads::active, reply -> settle(params, context, session, reply)));
    }

    /** Writes the answer where the step says, or fails with the reason it went unanswered. Runs on the player's thread. */
    private static ActionOutcome settle(Params params, LogicContext context, GuiSession session, PromptReply reply) {
        if (reply instanceof PromptReply.Unanswered unanswered) {
            return ActionOutcome.failed(unanswered.reason());
        }
        String answer = ((PromptReply.Answer) reply).text();
        switch (params.target()) {
            case Local local -> context.locals().put(local.name(), answer);
            case Session target -> session.vars().put(target.name(), answer);
        }
        return ActionOutcome.OK;
    }
}
