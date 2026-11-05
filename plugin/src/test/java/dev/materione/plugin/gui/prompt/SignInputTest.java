package dev.materione.plugin.gui.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.logic.Availability;
import dev.materione.api.logic.InputMethod;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The sign method on this build (logic.md §29 decision 14): it is registered, it is unavailable with its reason, and it
 * never asks or touches the world. Its first design placed a real sign block and restored it; that is gone.
 */
class SignInputTest {

    private final SignInput sign = new SignInput();

    @Test
    void theMethodIsRegisteredAsSignAndSaysItClosesTheWindow() {
        assertThat(sign.id()).isEqualTo("sign");
        assertThat(sign.describe().closesWindow()).isTrue();
    }

    @Test
    void itIsUnavailableWithTheReasonThatNamesTheMissingClientOnlyEditor() {
        Availability availability = sign.availability();

        assertThat(availability.available()).isFalse();
        assertThat(availability.reason()).contains("openVirtualSign").contains("chat or anvil");
    }

    @Test
    void askingDirectlyRefusesWithTheSameReasonAndAsksNobody() {
        List<String> messages = new ArrayList<>();
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> sign.ask(PromptServiceTest.player(id, messages), "Name it",
                new InputMethod.Reply() {
                    @Override
                    public void answer(String text) {
                        throw new AssertionError("nothing can answer: no editor was opened");
                    }

                    @Override
                    public void decline(String reason) {
                        throw new AssertionError("nothing can decline: no editor was opened");
                    }
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(SignInput.UNAVAILABLE_REASON);
        assertThat(messages).isEmpty();
    }

    @Test
    void dismissingWithNothingOpenIsHarmless() {
        sign.dismiss(PromptServiceTest.player(UUID.randomUUID(), new ArrayList<>()));
    }
}
