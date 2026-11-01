package dev.materione.plugin.runtime.behavior;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.logic.ReadUnavailableException;
import org.junit.jupiter.api.Test;

class PlaceholderSafetyTest {
    @Test
    void doesNotAssumeArbitraryFoliaExpansionsAreSafe() {
        assertThatCode(() -> PlaceholderResolver.requireSafe(false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> PlaceholderResolver.requireSafe(true))
                .isInstanceOf(ReadUnavailableException.class).hasMessageContaining("PlaceholderAPI");
    }
}
