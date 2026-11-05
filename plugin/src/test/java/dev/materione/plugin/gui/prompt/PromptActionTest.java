package dev.materione.plugin.gui.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.InputMethod;
import dev.materione.api.logic.LogicRegistration;
import dev.materione.api.logic.LogicRegistry;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ParseRejection;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.registry.RegistryEntry;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.plugin.logic.action.PromptAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** The {@code prompt} action (logic.md §6.6): its parse accepts and rejects, and what it does with an answer. */
class PromptActionTest {

    /** The method every name resolves to: parse is what is under test, and the method's own id is not. */
    private final InputMethod chat = new PromptServiceTest.FakeMethod();

    private final LogicRegistry<InputMethod> methods = new LogicRegistry<>() {
        @Override
        public LogicRegistration register(Plugin owner, NamespacedId id, InputMethod type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<InputMethod> get(NamespacedId id) {
            return resolve(id.value());
        }

        @Override
        public Optional<InputMethod> resolve(String authoredId) {
            return "chat".equals(authoredId) || "sign".equals(authoredId) ? Optional.of(chat) : Optional.empty();
        }

        @Override
        public List<RegistryEntry<InputMethod>> entries() {
            return List.of();
        }
    };

    private final List<String> messages = new ArrayList<>();
    private final PromptServiceTest.Gateway gateway = new PromptServiceTest.Gateway();
    private final PromptService service = new PromptService(PromptServiceTest.plugin(), gateway,
            new dev.materione.plugin.gui.GuiBridge(), ignored -> { });
    private final PromptAction action = new PromptAction(methods, service);

    private PromptAction.Params parse(Map<String, Object> raw) {
        return action.parse(ParamReader.of(raw));
    }

    private static Map<String, Object> prompt(Map<String, Object> extra) {
        var raw = new java.util.LinkedHashMap<String, Object>(Map.of("text", "Search", "var", "session.search"));
        raw.putAll(extra);
        return raw;
    }

    @Test
    void aPromptParsesItsMethodVariableTimeoutAndCancelWord() {
        PromptAction.Params params = parse(prompt(Map.of("method", "chat", "timeout", 30, "cancel_word", "stop")));

        assertThat(params.method()).isSameAs(chat);
        assertThat(params.target()).isEqualTo(new PromptAction.Session("search"));
        assertThat(params.timeoutSeconds()).isEqualTo(30);
        assertThat(params.cancelWord()).isEqualTo("stop");
    }

    @Test
    void theDefaultsAreChatSixtySecondsAndCancel() {
        PromptAction.Params params = parse(Map.of("text", "Name it", "var", "answer"));

        assertThat(params.method()).isSameAs(chat);
        assertThat(params.target()).isEqualTo(new PromptAction.Local("answer"));
        assertThat(params.timeoutSeconds()).isEqualTo(60);
        assertThat(params.cancelWord()).isEqualTo("cancel");
    }

    @Test
    void anEmptyCancelWordMeansNoneAtAll() {
        assertThat(parse(prompt(Map.of("cancel_word", ""))).cancelWord()).isNull();
    }

    @Test
    void anUnknownMethodIsRejectedWithItsOwnCode() {
        assertThatRejected(prompt(Map.of("method", "telepathy")), DiagnosticCodes.PROMPT_METHOD_UNKNOWN);
    }

    @Test
    void aPersistentVariableIsNeverAnAnswerTarget() {
        assertThatRejected(Map.of("text", "x", "var", "player.coins"), DiagnosticCodes.PROMPT_TARGET_INVALID);
        assertThatRejected(Map.of("text", "x", "var", "server.shop.season"), DiagnosticCodes.PROMPT_TARGET_INVALID);
    }

    @Test
    void aTimeoutOutsideOneToSixHundredSecondsIsRefused() {
        assertThatIllegal(prompt(Map.of("timeout", 0)));
        assertThatIllegal(prompt(Map.of("timeout", 601)));
    }

    @Test
    void aPromptWithNoPlayerFailsWithItsReasonAndAsksNobody() {
        var context = new MapLogicContext(Map.of());
        ActionOutcome outcome = action.executeResumable(parse(Map.of("text", "x", "var", "answer")), context,
                ReadExecutor.INLINE).toCompletableFuture().join();

        assertThat(outcome).isEqualTo(ActionOutcome.failed("event has no player"));
    }

    @Test
    void aSessionAnswerWithNoOpenGuiFailsAndSaysSo() {
        Player player = PromptServiceTest.player(UUID.randomUUID(), messages);
        var context = new MapLogicContext(Map.of()).with(Facets.PLAYER, player);

        CompletableFuture<ActionOutcome> outcome = action.executeResumable(
                parse(prompt(Map.of())), context, ReadExecutor.INLINE).toCompletableFuture();

        assertThat(outcome.join()).isEqualTo(ActionOutcome.failed("session.search: no open gui"));
        assertThat(gateway.pending(1)).isFalse();
    }

    @Test
    void aRunLocalAnswerIsWrittenAndTheRunResumes() {
        Player player = PromptServiceTest.player(UUID.randomUUID(), messages);
        var context = new MapLogicContext(Map.of()).with(Facets.PLAYER, player);
        var asked = new PromptServiceTest.FakeMethod();
        var chatPrompt = new PromptAction(new LogicRegistry<InputMethod>() {
            @Override
            public LogicRegistration register(Plugin owner, NamespacedId id, InputMethod type) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<InputMethod> get(NamespacedId id) {
                return Optional.of(asked);
            }

            @Override
            public Optional<InputMethod> resolve(String authoredId) {
                return Optional.of(asked);
            }

            @Override
            public List<RegistryEntry<InputMethod>> entries() {
                return List.of();
            }
        }, service);
        CompletableFuture<ActionOutcome> outcome = chatPrompt.executeResumable(
                chatPrompt.parse(ParamReader.of(Map.of("text", "Name it", "var", "answer"))), context,
                ReadExecutor.INLINE).toCompletableFuture();

        gateway.run(1);
        asked.reply.answer("gem");

        assertThat(outcome.join()).isEqualTo(ActionOutcome.OK);
        assertThat(context.locals()).containsEntry("answer", "gem");
    }

    private void assertThatRejected(Map<String, Object> raw, dev.materione.api.diagnostics.DiagnosticCode code) {
        try {
            parse(raw);
            throw new AssertionError("the prompt should have been rejected");
        } catch (ParseRejection rejection) {
            assertThat(rejection.code()).isEqualTo(code);
        }
    }

    private void assertThatIllegal(Map<String, Object> raw) {
        try {
            parse(raw);
            throw new AssertionError("the prompt should have been refused");
        } catch (IllegalArgumentException refused) {
            assertThat(refused).isNotNull();
        }
    }
}
