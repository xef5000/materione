package dev.materione.api.logic.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The provider result types refuse to say "failed" without saying why, and the default grant helpers hold. */
class ProviderTypesTest {

    private static final UUID ANY = UUID.nameUUIDFromBytes("any".getBytes());

    @Test
    void aFailedEconomyResultMustCarryAReason() {
        assertThat(new EconomyResult.Failed("bank closed").reason()).isEqualTo("bank closed");
        assertThatThrownBy(() -> new EconomyResult.Failed(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRefusedProviderRequestMustCarryAReasonAndOkDropsIt() {
        assertThat(ProviderResult.OK.ok()).isTrue();
        assertThat(ProviderResult.OK.reason()).isEmpty();
        assertThat(ProviderResult.failed("nope").ok()).isFalse();
        assertThatThrownBy(() -> new ProviderResult(false, "")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theDefaultPermissionProviderCannotExpireAGrant() {
        PermissionProvider provider = new PermissionProvider() {
            @Override
            public String name() {
                return "session";
            }

            @Override
            public ProviderResult grant(UUID player, String node) {
                return ProviderResult.OK;
            }

            @Override
            public ProviderResult revoke(UUID player, String node) {
                return ProviderResult.OK;
            }
        };

        ProviderResult expiring = provider.grantFor(ANY, "a.b", Duration.ofMinutes(5));

        assertThat(expiring.ok()).isFalse();
        assertThat(expiring.reason()).contains("cannot expire");
    }

    @Test
    void withGrantRevokesEvenWhenTheBodyThrowsAndRunsNothingWhenTheGrantIsRefused() {
        List<String> calls = new ArrayList<>();
        PermissionProvider refusing = new PermissionProvider() {
            @Override
            public String name() {
                return "refusing";
            }

            @Override
            public ProviderResult grant(UUID player, String node) {
                calls.add("grant");
                return ProviderResult.failed("no");
            }

            @Override
            public ProviderResult revoke(UUID player, String node) {
                calls.add("revoke");
                return ProviderResult.OK;
            }
        };
        PermissionProvider accepting = new PermissionProvider() {
            @Override
            public String name() {
                return "accepting";
            }

            @Override
            public ProviderResult grant(UUID player, String node) {
                calls.add("grant");
                return ProviderResult.OK;
            }

            @Override
            public ProviderResult revoke(UUID player, String node) {
                calls.add("revoke");
                return ProviderResult.OK;
            }
        };

        assertThat(refusing.withGrant(ANY, "a.b", () -> calls.add("body")).ok()).isFalse();
        assertThat(calls).containsExactly("grant");

        calls.clear();
        assertThatThrownBy(() -> accepting.withGrant(ANY, "a.b", () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(calls).containsExactly("grant", "revoke");
    }
}
