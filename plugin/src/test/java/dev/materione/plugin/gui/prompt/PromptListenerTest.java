package dev.materione.plugin.gui.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.logic.InputMethod;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * The chat half of the prompt adapter (logic.md §6.6): a message while a question waits is the answer and is cancelled
 * so it is not broadcast; any other message passes. A real {@link AsyncChatEvent} is built around a player stand-in.
 */
class PromptListenerTest {

    private final UUID id = UUID.randomUUID();
    private final List<String> messages = new ArrayList<>();
    private final Player player = PromptServiceTest.player(id, messages);
    private final PromptServiceTest.Gateway gateway = new PromptServiceTest.Gateway();
    private final ChatInput chat = new ChatInput();
    private final PromptListener listener = new PromptListener(PromptServiceTest.plugin(), gateway,
            new PromptService(PromptServiceTest.plugin(), gateway, new dev.materione.plugin.gui.GuiBridge(),
                    ignored -> { }),
            chat, new AnvilInput());

    private AsyncChatEvent chatting(String text) {
        Component message = Component.text(text);
        return new AsyncChatEvent(true, player, Set.of(), null, message, message, null);
    }

    @Test
    void aMessageWhileAQuestionWaitsIsTheAnswerAndIsNotBroadcast() {
        var answers = new ArrayList<String>();
        chat.ask(player, "Search for an item", new InputMethod.Reply() {
            @Override
            public void answer(String text) {
                answers.add(text);
            }

            @Override
            public void decline(String reason) {
                answers.add("declined: " + reason);
            }
        });
        AsyncChatEvent event = chatting("diamond");

        listener.onChat(event);

        assertThat(event.isCancelled()).isTrue();
        assertThat(answers).containsExactly("diamond");
        assertThat(chat.awaits(id)).isFalse();
    }

    @Test
    void aMessageWithNoQuestionWaitingIsBroadcastAsUsual() {
        AsyncChatEvent event = chatting("hello everyone");

        listener.onChat(event);

        assertThat(event.isCancelled()).isFalse();
    }

    @Test
    void theAnswerIsDeliveredOnlyOnceEvenWhenTwoMessagesFollow() {
        var answers = new ArrayList<String>();
        chat.ask(player, "Search", new InputMethod.Reply() {
            @Override
            public void answer(String text) {
                answers.add(text);
            }

            @Override
            public void decline(String reason) {
                answers.add("declined");
            }
        });

        listener.onChat(chatting("iron"));
        AsyncChatEvent second = chatting("gold");
        listener.onChat(second);

        assertThat(answers).containsExactly("iron");
        assertThat(second.isCancelled()).isFalse();
    }
}
