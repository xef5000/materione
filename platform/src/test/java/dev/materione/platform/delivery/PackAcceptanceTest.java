package dev.materione.platform.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The gate glyph rendering rides on: a codepoint is only sent to clients
 * whose current pack generation actually loaded. The dangerous direction
 * is a stale LOADED outliving the send that replaced it.
 */
class PackAcceptanceTest {

    @Test
    void playerIsRenderCapableOnlyAfterALoadedReport() {
        var acceptance = new PackAcceptance();
        var player = UUID.randomUUID();

        assertThat(acceptance.canRender(player)).isFalse();
        acceptance.sent(player);
        assertThat(acceptance.canRender(player)).isFalse();
        acceptance.accepted(player); // download in flight, not yet usable
        assertThat(acceptance.canRender(player)).isFalse();
        acceptance.loaded(player);
        assertThat(acceptance.canRender(player)).isTrue();
    }

    @Test
    void aNewSendInvalidatesThePreviousLoadedGeneration() {
        var acceptance = new PackAcceptance();
        var player = UUID.randomUUID();
        acceptance.sent(player);
        acceptance.loaded(player);
        assertThat(acceptance.canRender(player)).isTrue();

        // Reload resend: whatever the client had is about to be replaced.
        acceptance.sent(player);
        assertThat(acceptance.canRender(player)).isFalse();
        acceptance.loaded(player);
        assertThat(acceptance.canRender(player)).isTrue();
    }

    @Test
    void declinedThenAcceptedAgainRecovers() {
        var acceptance = new PackAcceptance();
        var player = UUID.randomUUID();
        acceptance.sent(player);
        acceptance.failed(player);
        assertThat(acceptance.canRender(player)).isFalse();

        acceptance.sent(player);
        acceptance.loaded(player);
        assertThat(acceptance.canRender(player)).isTrue();
    }

    @Test
    void forgetDropsThePlayerEntirely() {
        var acceptance = new PackAcceptance();
        var player = UUID.randomUUID();
        acceptance.sent(player);
        acceptance.loaded(player);
        acceptance.forgot(player);
        assertThat(acceptance.canRender(player)).isFalse();
        assertThat(acceptance.snapshot()).isEmpty();
    }

    @Test
    void statusCountsAreObservable() {
        var acceptance = new PackAcceptance();
        var loaded = UUID.randomUUID();
        var pending = UUID.randomUUID();
        var failed = UUID.randomUUID();
        acceptance.sent(loaded);
        acceptance.loaded(loaded);
        acceptance.sent(pending);
        acceptance.sent(failed);
        acceptance.failed(failed);

        assertThat(acceptance.count(PackAcceptance.State.LOADED)).isEqualTo(1);
        assertThat(acceptance.count(PackAcceptance.State.PENDING)).isEqualTo(1);
        assertThat(acceptance.count(PackAcceptance.State.FAILED)).isEqualTo(1);
    }
}
