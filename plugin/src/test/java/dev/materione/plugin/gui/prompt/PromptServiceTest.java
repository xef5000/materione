package dev.materione.plugin.gui.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.Availability;
import dev.materione.api.logic.InputMethod;
import dev.materione.api.logic.InputMethodDescription;
import dev.materione.api.registry.SelfDescription;
import dev.materione.api.scheduling.ScheduledHandle;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.plugin.gui.GuiBridge;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** The prompt registry (logic.md §6.6), driven by hand: a gateway that queues what it is told to run later. */
class PromptServiceTest {

    private final UUID id = UUID.randomUUID();
    private final List<String> messages = new ArrayList<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final Gateway gateway = new Gateway();
    private final Player player = player(id, messages);
    private final PromptService service = new PromptService(plugin(), gateway, new GuiBridge(), diagnostics::add);
    private final FakeMethod method = new FakeMethod();

    private PromptService.Request request(String cancelWord, int timeout, List<PromptReply> replies, BooleanSupplier live) {
        return new PromptService.Request(player, method, "Search for an item", cancelWord, timeout, live, reply -> {
            replies.add(reply);
            return reply instanceof PromptReply.Answer ? ActionOutcome.OK : ActionOutcome.failed("unanswered");
        });
    }

    private PromptService.Request request(String cancelWord, int timeout, List<PromptReply> replies) {
        return request(cancelWord, timeout, replies, () -> true);
    }

    @Test
    void anAnswerIsTrimmedHandedToTheStepAndResumesTheRun() {
        var replies = new ArrayList<PromptReply>();
        CompletableFuture<ActionOutcome> outcome = service.begin(request("cancel", 60, replies)).toCompletableFuture();

        assertThat(outcome).isNotDone();
        gateway.run(1); // the method is asked once the prompt has started
        assertThat(method.asked).isEqualTo(1);
        assertThat(method.question).isEqualTo("Search for an item");
        method.reply.answer("  diamond  ");

        assertThat(replies).containsExactly(new PromptReply.Answer("diamond"));
        assertThat(outcome.join()).isEqualTo(ActionOutcome.OK);
        assertThat(method.dismissed).isEqualTo(1);
        assertThat(gateway.timeoutCancelled()).isTrue();
    }

    @Test
    void theCancelWordIsNotAnAnswerWhateverItsCase() {
        var replies = new ArrayList<PromptReply>();
        CompletableFuture<ActionOutcome> outcome = service.begin(request("cancel", 60, replies)).toCompletableFuture();
        gateway.run(1);

        method.reply.answer("CANCEL");

        assertThat(replies).containsExactly(new PromptReply.Unanswered("the player cancelled"));
        assertThat(outcome.join()).isEqualTo(ActionOutcome.failed("unanswered"));
        assertThat(method.dismissed).isEqualTo(1);
    }

    @Test
    void anEmptyEditorOrABlankAnswerIsNoAnswer() {
        var replies = new ArrayList<PromptReply>();
        service.begin(request("cancel", 60, replies));
        gateway.run(1);

        method.reply.answer("   ");

        assertThat(replies).containsExactly(new PromptReply.Unanswered("no answer was given"));
    }

    @Test
    void aTimeoutEndsTheQuestionUnansweredAndDismissesTheMethod() {
        var replies = new ArrayList<PromptReply>();
        CompletableFuture<ActionOutcome> outcome = service.begin(request("cancel", 45, replies)).toCompletableFuture();
        gateway.run(1);

        gateway.run(45 * 20L);

        assertThat(replies).containsExactly(new PromptReply.Unanswered("no answer in 45 seconds"));
        assertThat(outcome.join()).isEqualTo(ActionOutcome.failed("unanswered"));
        assertThat(method.dismissed).isEqualTo(1);
    }

    @Test
    void aSecondQuestionWhileOneWaitsIsRefusedAndTheFirstStillStands() {
        var first = new ArrayList<PromptReply>();
        var second = new ArrayList<PromptReply>();
        service.begin(request("cancel", 60, first));
        CompletableFuture<ActionOutcome> refused = service.begin(request("cancel", 60, second)).toCompletableFuture();

        assertThat(refused.join()).isEqualTo(ActionOutcome.failed("a prompt is already waiting for this player"));
        assertThat(second).isEmpty();
        assertThat(messages).anySatisfy(line -> assertThat(line).contains("already answering"));
        gateway.run(1);
        method.reply.answer("ok");
        assertThat(first).containsExactly(new PromptReply.Answer("ok"));
    }

    @Test
    void aQuestionWhoseRunEndedIsSupersededByTheNextOne() {
        boolean[] live = {true};
        var first = new ArrayList<PromptReply>();
        var second = new ArrayList<PromptReply>();
        CompletableFuture<ActionOutcome> earlier = service.begin(request("cancel", 60, first, () -> live[0]))
                .toCompletableFuture();
        live[0] = false; // the run that asked was cancelled

        service.begin(request("cancel", 60, second));

        assertThat(first).containsExactly(new PromptReply.Unanswered("the earlier question was superseded"));
        assertThat(earlier.join()).isEqualTo(ActionOutcome.failed("unanswered"));
    }

    @Test
    void aPlayerWhoLeavesEndsTheirQuestionWithoutShowingAnything() {
        var replies = new ArrayList<PromptReply>();
        CompletableFuture<ActionOutcome> outcome = service.begin(request("cancel", 60, replies)).toCompletableFuture();
        gateway.run(1);

        service.playerLeft(id);

        assertThat(replies).containsExactly(new PromptReply.Unanswered("the player left"));
        assertThat(outcome.join()).isEqualTo(ActionOutcome.failed("unanswered"));
        assertThat(method.dismissed).isEqualTo(1);
    }

    @Test
    void aMethodThatCannotStartIsReportedAndTheRunTakesItsElse() {
        var replies = new ArrayList<PromptReply>();
        method.failOnAsk = new IllegalStateException("there is no free block above the player");
        CompletableFuture<ActionOutcome> outcome = service.begin(request("cancel", 60, replies)).toCompletableFuture();

        gateway.run(1);

        assertThat(replies).containsExactly(new PromptReply.Unanswered(
                "the input method could not start: there is no free block above the player"));
        assertThat(outcome.join()).isEqualTo(ActionOutcome.failed("unanswered"));
        assertThat(diagnostics).hasSize(1);
        assertThat(diagnostics.get(0).code()).isEqualTo(DiagnosticCodes.PROMPT_METHOD_FAILED);
    }

    @Test
    void anUnavailableMethodIsRefusedBeforeAnythingIsHiddenOrPending() {
        var replies = new ArrayList<PromptReply>();
        method.availability = Availability.unavailable("no client-only editor on this build");

        CompletableFuture<ActionOutcome> outcome = service.begin(request("cancel", 60, replies)).toCompletableFuture();

        assertThat(outcome.join()).isEqualTo(
                ActionOutcome.failed("the input method is unavailable: no client-only editor on this build"));
        assertThat(replies).isEmpty();
        assertThat(method.asked).isZero();
        assertThat(method.dismissed).isZero();
        assertThat(gateway.pending(1)).isFalse();
        assertThat(gateway.pending(60 * 20L)).isFalse();
        assertThat(diagnostics).extracting(Diagnostic::code).containsExactly(DiagnosticCodes.PROMPT_METHOD_FAILED);
        assertThat(messages).anySatisfy(line -> assertThat(line).contains("cannot be asked right now"));
    }

    @Test
    void aMethodStartIsNeverRunInlineWithTheBegin() {
        var replies = new ArrayList<PromptReply>();
        service.begin(request("cancel", 60, replies));

        assertThat(method.asked).isZero();
        assertThat(gateway.pending(1)).isTrue();
    }

    @Test
    void theSameProblemIsReportedOnceAnInterval() {
        service.report(DiagnosticCodes.PROMPT_METHOD_FAILED, "first");
        service.report(DiagnosticCodes.PROMPT_METHOD_FAILED, "second");
        service.report(DiagnosticCodes.PROMPT_WINDOW_LOST, "another code");

        assertThat(diagnostics).extracting(Diagnostic::message).containsExactly("first", "another code");
    }

    @Test
    void aTimeoutOutsideTheAllowedRangeIsRefusedAtTheRequest() {
        assertThatThrownBy(() -> request("cancel", 0, new ArrayList<>()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request("cancel", PromptService.MAX_TIMEOUT_SECONDS + 1, new ArrayList<>()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- fakes ---------------------------------------------------------------------

    /** A method that records what it was asked and keeps the reply it was given. */
    static final class FakeMethod implements InputMethod {
        int asked;
        int dismissed;
        String question;
        Reply reply;
        RuntimeException failOnAsk;
        Availability availability = Availability.AVAILABLE;

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public InputMethodDescription describe() {
            return InputMethodDescription.of(SelfDescription.of("Fake", "test method"), true);
        }

        @Override
        public Availability availability() {
            return availability;
        }

        @Override
        public void ask(Player player, String prompt, Reply reply) {
            asked++;
            question = prompt;
            if (failOnAsk != null) {
                throw failOnAsk;
            }
            this.reply = reply;
        }

        @Override
        public void dismiss(Player player) {
            dismissed++;
        }
    }

    /** Runs executeEntity inline and queues the later tasks, so a test runs them when it says. */
    static final class Gateway implements SchedulingGateway {
        private final List<Queued> queued = new ArrayList<>();

        private static final class Queued {
            final long delay;
            final Runnable task;
            final Handle handle = new Handle();
            boolean ran;

            Queued(long delay, Runnable task) {
                this.delay = delay;
                this.task = task;
            }
        }

        private static final class Handle implements ScheduledHandle {
            boolean cancelled;

            @Override
            public void cancel() {
                cancelled = true;
            }
        }

        @Override
        public void executeSync(Plugin plugin, Location location, Runnable task) {
            task.run();
        }

        @Override
        public void executeEntity(Plugin plugin, Entity entity, Runnable task) {
            task.run();
        }

        @Override
        public void executeAsync(Plugin plugin, Runnable task) {
            task.run();
        }

        @Override
        public void executeGlobal(Plugin plugin, Runnable task) {
            task.run();
        }

        @Override
        public ScheduledHandle executeEntityLater(Plugin plugin, Entity entity, long delayTicks, Runnable task,
                Runnable retired) {
            var entry = new Queued(delayTicks, task);
            queued.add(entry);
            return entry.handle;
        }

        /** Runs the first queued task due after {@code delay} ticks that is still live, which must exist. */
        void run(long delay) {
            for (Queued entry : queued) {
                if (entry.delay == delay && !entry.ran && !entry.handle.cancelled) {
                    entry.ran = true;
                    entry.task.run();
                    return;
                }
            }
            throw new AssertionError("no live task queued after " + delay + " ticks");
        }

        /** Whether a task is still waiting to run after {@code delay} ticks. */
        boolean pending(long delay) {
            return queued.stream().anyMatch(entry -> entry.delay == delay && !entry.ran && !entry.handle.cancelled);
        }

        /** Whether a timeout (a task after more than one tick) was cancelled, as a question that ended does. */
        boolean timeoutCancelled() {
            return queued.stream().anyMatch(entry -> entry.delay > 1 && entry.handle.cancelled);
        }
    }

    static Player player(UUID id, List<String> messages) {
        return (Player) Proxy.newProxyInstance(PromptServiceTest.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> "Probe";
                    case "isOnline" -> true;
                    case "sendMessage" -> {
                        if (args != null && args.length == 1 && args[0] instanceof Component component) {
                            messages.add(PlainTextComponentSerializer.plainText().serialize(component));
                        }
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "FakePlayer";
                    default -> null;
                });
    }

    static Plugin plugin() {
        return (Plugin) Proxy.newProxyInstance(PromptServiceTest.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (proxy, method, args) -> null);
    }
}
