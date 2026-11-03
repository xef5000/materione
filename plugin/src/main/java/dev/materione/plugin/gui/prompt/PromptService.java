package dev.materione.plugin.gui.prompt;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCode;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.Availability;
import dev.materione.api.logic.InputMethod;
import dev.materione.api.scheduling.ScheduledHandle;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.plugin.gui.GuiBridge;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.gui.session.GuiSession;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The prompt registry (logic.md §6.6): one question at a time per player, the timeout, the settlement of the answer
 * and the window that a question hid. The {@code prompt} action parks its run on {@link #begin}; the input methods and
 * their listeners answer through the {@link InputMethod.Reply} this service gave them.
 *
 * <p>Threading. {@link #begin} and every settlement run on the player's thread: a method's reply hops there before it
 * settles, so the run's variables and the window are only ever touched by their owner. A pending prompt is held per
 * player; a second prompt while one waits is refused, and the refusal says so to the player.
 *
 * <p>Ends. A prompt ends once: with an answer, a cancel word or an empty answer (both unanswered), a timeout, the
 * player's quit, or a method that cannot start. Whichever ends it, the method is dismissed, the answer is written by
 * the step (through {@link Request#onReply}), and the window the prompt hid is shown again for the same session.
 */
public final class PromptService {

    /** The longest a player may be asked to answer, in seconds (logic.md §6.6). */
    public static final int MAX_TIMEOUT_SECONDS = 600;

    /** The ticks a method gets to start after its window has closed (the window closes on the next tick). */
    private static final long START_DELAY_WINDOW_CLOSED = 2;
    private static final long START_DELAY = 1;

    /** A code is published at most once per this interval: a broken method must not flood the log. */
    private static final long REPORT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);

    /**
     * One prompt as a step asks it. A window the player has open when the prompt starts is hidden for the question
     * (when the method closes windows) and shown again afterwards; the run's own session is not touched.
     *
     * @param player the player to ask
     * @param method the input method
     * @param prompt the text the prompt shows, rendered
     * @param cancelWord the word that cancels, or null for none
     * @param timeoutSeconds how long the player may take
     * @param live whether the run that asked is still waiting (false once it was cancelled)
     * @param onReply what the run does with the reply, on the player's thread: writes the answer, returns the outcome
     */
    public record Request(
            Player player, InputMethod method, String prompt, String cancelWord,
            int timeoutSeconds, BooleanSupplier live, Function<PromptReply, ActionOutcome> onReply) {

        public Request {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(prompt, "prompt");
            Objects.requireNonNull(live, "live");
            Objects.requireNonNull(onReply, "onReply");
            if (timeoutSeconds < 1 || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
                throw new IllegalArgumentException("a prompt times out after 1 to " + MAX_TIMEOUT_SECONDS + " seconds");
            }
        }
    }

    /** One prompt in flight. Touched only on the player's thread. */
    private static final class Pending {

        final Request request;
        final CompletableFuture<ActionOutcome> outcome = new CompletableFuture<>();
        final Viewer viewer;
        GuiSession hidden;
        ScheduledHandle timeout;
        boolean settled;

        Pending(Request request) {
            this.request = request;
            this.viewer = Viewer.of(request.player());
        }

        boolean live() {
            return !settled && request.live().getAsBoolean();
        }
    }

    private final Plugin plugin;
    private final SchedulingGateway scheduling;
    private final GuiBridge bridge;
    private final Consumer<Diagnostic> diagnostics;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<DiagnosticCode, Long> lastReported = new ConcurrentHashMap<>();

    /**
     * @param bridge where the window the prompt hides is found and shown again; before the GUI runtime is bound a
     *     prompt hides nothing
     * @param diagnostics where the prompt's run-time problems are published (rate-limited per code)
     */
    public PromptService(Plugin plugin, SchedulingGateway scheduling, GuiBridge bridge, Consumer<Diagnostic> diagnostics) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.scheduling = Objects.requireNonNull(scheduling, "scheduling");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    /**
     * Starts a prompt on the player's thread. The returned stage completes when the prompt has ended and the step's
     * outcome is known: an answer the step wrote is {@link ActionOutcome#OK}; anything else is what {@code onReply} said.
     * A second prompt for a player who is already being asked is refused at once.
     */
    public CompletionStage<ActionOutcome> begin(Request request) {
        UUID id = request.player().getUniqueId();
        Availability available = request.method().availability();
        if (!available.available()) {
            // refused before anything is hidden or pending: a method this build cannot run never touches the player's state
            report(DiagnosticCodes.PROMPT_METHOD_FAILED, "prompt method '" + request.method().id()
                    + "' is unavailable on this build: " + available.reason());
            tell(request.player(), "This question cannot be asked right now: " + available.reason(), NamedTextColor.RED);
            return CompletableFuture.completedFuture(
                    ActionOutcome.failed("the input method is unavailable: " + available.reason()));
        }
        Pending earlier = pending.get(id);
        if (earlier != null) {
            if (earlier.live()) {
                tell(request.player(), "You are already answering a question; finish that one first.", NamedTextColor.RED);
                return CompletableFuture.completedFuture(
                        ActionOutcome.failed("a prompt is already waiting for this player"));
            }
            settle(earlier, new PromptReply.Unanswered("the earlier question was superseded"));
        }
        var prompt = new Pending(request);
        pending.put(id, prompt);
        if (request.method().describe().closesWindow()) {
            prompt.hidden = hide(prompt.viewer);
        }
        long delay = prompt.hidden != null ? START_DELAY_WINDOW_CLOSED : START_DELAY;
        try {
            scheduling.executeEntityLater(plugin, request.player(), delay, () -> start(prompt),
                    () -> settle(prompt, new PromptReply.Unanswered("the player is gone")));
            prompt.timeout = scheduling.executeEntityLater(plugin, request.player(),
                    request.timeoutSeconds() * 20L,
                    () -> settle(prompt, new PromptReply.Unanswered("no answer in " + request.timeoutSeconds() + " seconds")),
                    () -> settle(prompt, new PromptReply.Unanswered("the player is gone")));
        } catch (RuntimeException failure) {
            report(DiagnosticCodes.PROMPT_METHOD_FAILED, "a prompt could not be scheduled: " + reasonOf(failure));
            settle(prompt, new PromptReply.Unanswered("the prompt could not be scheduled"));
        }
        return prompt.outcome;
    }

    /** The player left: their question ends unanswered, and nothing is shown again (they are not online). */
    public void playerLeft(UUID player) {
        Pending prompt = pending.get(player);
        if (prompt != null) {
            settle(prompt, new PromptReply.Unanswered("the player left"), false);
        }
    }

    /** Reports a problem a prompt found at run time, once per code per interval. */
    public void report(DiagnosticCode code, String message) {
        long now = System.nanoTime();
        Long last = lastReported.get(code);
        if (last != null && now - last < REPORT_INTERVAL_NANOS) {
            return;
        }
        lastReported.put(code, now);
        diagnostics.accept(new Diagnostic(code, code.defaultSeverity(), message, null, null, null));
    }

    // --- the ask -------------------------------------------------------------------

    /** Puts the question to the player, on their thread, once the window has gone. */
    private void start(Pending prompt) {
        if (prompt.settled) {
            return;
        }
        Request request = prompt.request;
        Player player = request.player();
        try {
            request.method().ask(player, request.prompt(), new InputMethod.Reply() {
                @Override
                public void answer(String text) {
                    hop(player, () -> settle(prompt, text == null || text.isBlank()
                            ? new PromptReply.Unanswered("no answer was given")
                            : resolved(prompt, text.trim())));
                }

                @Override
                public void decline(String reason) {
                    hop(player, () -> settle(prompt, new PromptReply.Unanswered(
                            reason == null || reason.isBlank() ? "no answer was given" : reason)));
                }
            });
        } catch (RuntimeException failure) {
            String reason = reasonOf(failure);
            report(DiagnosticCodes.PROMPT_METHOD_FAILED, "prompt method '" + request.method().id()
                    + "' could not start for " + player.getName() + ": " + reason);
            tell(player, "This question cannot be asked right now: " + reason, NamedTextColor.RED);
            settle(prompt, new PromptReply.Unanswered("the input method could not start: " + reason));
            return;
        }
        if (request.method().id().equals("chat") && request.cancelWord() != null) {
            tell(player, "Type " + request.cancelWord() + " to stop.", NamedTextColor.GRAY);
        }
    }

    /** A typed answer: a cancel word cancels it. */
    private static PromptReply resolved(Pending prompt, String text) {
        String cancel = prompt.request.cancelWord();
        if (cancel != null && text.equalsIgnoreCase(cancel)) {
            return new PromptReply.Unanswered("the player cancelled");
        }
        return new PromptReply.Answer(text);
    }

    // --- the end -------------------------------------------------------------------

    /**
     * Ends a prompt once: the step writes its answer, the method is dismissed, the hidden window comes back, and the
     * run is released with the step's outcome. A second call changes nothing.
     */
    private void settle(Pending prompt, PromptReply reply) {
        settle(prompt, reply, true);
    }

    private void settle(Pending prompt, PromptReply reply, boolean showWindowAgain) {
        if (prompt.settled) {
            return;
        }
        prompt.settled = true;
        Request request = prompt.request;
        pending.remove(request.player().getUniqueId(), prompt);
        if (prompt.timeout != null) {
            prompt.timeout.cancel();
        }
        ActionOutcome outcome;
        try {
            outcome = request.onReply().apply(reply);
        } catch (RuntimeException failure) {
            outcome = ActionOutcome.failed("the answer could not be stored: " + reasonOf(failure));
        }
        try {
            request.method().dismiss(request.player());
        } catch (RuntimeException failure) {
            report(DiagnosticCodes.PROMPT_METHOD_FAILED, "prompt method '" + request.method().id()
                    + "' could not be dismissed: " + reasonOf(failure));
        }
        if (prompt.hidden != null && showWindowAgain) {
            showAgain(prompt);
        }
        prompt.outcome.complete(outcome);
    }

    /** Hides the player's current window for the question, if one is open. Returns its session, or null. */
    private GuiSession hide(Viewer viewer) {
        GuiBridge.Services services = bridge.services().orElse(null);
        if (services == null) {
            return null;
        }
        GuiSession current = services.sessions().current(viewer.id()).filter(GuiSession::isOpen).orElse(null);
        if (current == null) {
            return null;
        }
        services.lifecycle().hideForPrompt(viewer, current);
        return current;
    }

    /** Shows the window a prompt hid again, for the same session, unless the player has moved on. */
    private void showAgain(Pending prompt) {
        GuiBridge.Services services = bridge.services().orElse(null);
        Player player = prompt.request.player();
        if (services == null || !player.isOnline()) {
            return;
        }
        services.lifecycle().showAfterPrompt(prompt.viewer, prompt.hidden, reason -> {
            report(DiagnosticCodes.PROMPT_WINDOW_LOST, "GUI " + prompt.hidden.gui()
                    + " could not be shown after a prompt: " + reason);
            tell(player, "The menu could not be shown again: " + reason, NamedTextColor.RED);
        });
    }

    // --- helpers -------------------------------------------------------------------

    /** Runs {@code task} on the player's thread (a method may answer from any thread). */
    private void hop(Player player, Runnable task) {
        scheduling.executeEntity(plugin, player, task);
    }

    private static void tell(Player player, String text, NamedTextColor color) {
        if (player.isOnline()) {
            player.sendMessage(Component.text(text, color));
        }
    }

    private static String reasonOf(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
