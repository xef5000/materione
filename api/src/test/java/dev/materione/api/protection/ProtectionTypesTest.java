package dev.materione.api.protection;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;

/** The protection port's value types (protection.md section 3): mode parsing, answers and actors. */
class ProtectionTypesTest {

    private static OfflinePlayer offline(UUID id) {
        return (OfflinePlayer) Proxy.newProxyInstance(OfflinePlayer.class.getClassLoader(),
                new Class<?>[] {OfflinePlayer.class}, (self, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "hashCode" -> 1;
                    case "equals" -> self == args[0];
                    default -> throw new AssertionError(method.getName());
                });
    }

    @Test
    void theModeParsesItsThreeNamesAndNothingElse() {
        assertThat(ProtectionMode.parse("event")).contains(ProtectionMode.EVENT);
        assertThat(ProtectionMode.parse(" Port ")).contains(ProtectionMode.PORT);
        assertThat(ProtectionMode.parse("BOTH")).contains(ProtectionMode.BOTH);
        assertThat(ProtectionMode.parse("strict")).isEmpty();
        assertThat(ProtectionMode.parse(3)).isEmpty();
        assertThat(ProtectionMode.parse(null)).isEmpty();
        assertThat(ProtectionMode.DEFAULT).isEqualTo(ProtectionMode.BOTH);
    }

    @Test
    void theModesSayWhatTheyAsk() {
        assertThat(ProtectionMode.EVENT.asksPort()).isFalse();
        assertThat(ProtectionMode.EVENT.runsEventPath()).isTrue();
        assertThat(ProtectionMode.PORT.asksPort()).isTrue();
        assertThat(ProtectionMode.PORT.runsEventPath()).isFalse();
        assertThat(ProtectionMode.BOTH.asksPort()).isTrue();
        assertThat(ProtectionMode.BOTH.runsEventPath()).isTrue();
    }

    @Test
    void anAnswerSaysWhoRefusedAndWhy() {
        assertThat(ProtectionAnswer.deny("WorldGuard", "build is denied").describeDenial())
                .isEqualTo("refused by WorldGuard (build is denied)");
        assertThat(ProtectionAnswer.deny("", "").describeDenial()).isEqualTo("refused by protection");
        assertThat(ProtectionAnswer.allow("X").denied()).isFalse();
        assertThat(ProtectionAnswer.abstain("X").denied()).isFalse();
        assertThat(new ProtectionAnswer(ProtectionAnswer.Verdict.ALLOW, null, null).provider()).isEmpty();
    }

    @Test
    void anActorIsNoneOrAnOfflinePlayerKnownById() {
        UUID id = UUID.randomUUID();
        assertThat(ProtectionActor.none().isNone()).isTrue();
        assertThat(ProtectionActor.none().id()).isEmpty();
        ProtectionActor known = ProtectionActor.of(offline(id));
        assertThat(known.isNone()).isFalse();
        assertThat(known.id()).contains(id);
        assertThat(known.online()).isEmpty();
        assertThat(known.asOffline(offline(id))).isSameAs(known);
        assertThat(ProtectionActor.none().asOffline(offline(id))).isSameAs(ProtectionActor.none());
    }
}
