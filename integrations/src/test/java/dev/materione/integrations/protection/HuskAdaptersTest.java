package dev.materione.integrations.protection;

import static dev.materione.integrations.protection.Doubles.location;
import static dev.materione.integrations.protection.Doubles.victim;
import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.protection.ProtectionAction;
import dev.materione.api.protection.ProtectionActor;
import dev.materione.api.protection.ProtectionAnswer.Verdict;
import dev.materione.api.protection.ProtectionPort;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/** The HuskClaims and HuskTowns adapters share one shape; each runs over a test double of its plugin. */
class HuskAdaptersTest {

    private final List<String> asked = new ArrayList<>();
    private boolean allowed = true;
    private boolean allowedWithNoUser = true;

    private final ProtectionPort claims = HuskClaimsAdapter.port(new HuskClaimsAdapter.Api() {
        @Override
        public boolean allowed(ProtectionAction action, Player player, Location location, Entity victim) {
            asked.add("claims user " + action);
            return allowed;
        }

        @Override
        public boolean allowedWithNoUser(ProtectionAction action, Location location) {
            asked.add("claims nobody " + action);
            return allowedWithNoUser;
        }
    });

    private final ProtectionPort towns = HuskTownsAdapter.port(new HuskTownsAdapter.Api() {
        @Override
        public boolean allowed(ProtectionAction action, Player player, Location location, Entity victim) {
            asked.add("towns user " + action);
            return allowed;
        }

        @Override
        public boolean allowedWithNoUser(ProtectionAction action, Location location) {
            asked.add("towns nobody " + action);
            return allowedWithNoUser;
        }
    });

    @Test
    void anOnlineUsersBreakPlaceInteractAndDamageAreTheOperationsVerdict() {
        for (ProtectionPort port : List.of(claims, towns)) {
            assertThat(port.canBreak(Doubles.online(), location()).verdict()).isEqualTo(Verdict.ALLOW);
            assertThat(port.canDamage(Doubles.online(), victim()).verdict()).isEqualTo(Verdict.ALLOW);
        }
        allowed = false;
        var denied = claims.canPlace(Doubles.online(), location());
        assertThat(denied.denied()).isTrue();
        assertThat(denied.provider()).isEqualTo("HuskClaims");
        assertThat(towns.canInteract(Doubles.online(), location()).provider()).isEqualTo("HuskTowns");
        assertThat(asked).contains("claims user BREAK", "towns user DAMAGE", "claims user PLACE", "towns user INTERACT");
    }

    @Test
    void noActorUsesTheOperationWithNoUser() {
        allowedWithNoUser = false;

        assertThat(claims.canBreak(ProtectionActor.none(), location()).denied()).isTrue();
        assertThat(towns.canPlace(ProtectionActor.none(), location()).denied()).isTrue();
        assertThat(asked).containsExactly("claims nobody BREAK", "towns nobody PLACE");
    }

    @Test
    void aPlayerKnownByIdItemUseAndActorlessDamageGetNoOpinion() {
        allowed = false;
        allowedWithNoUser = false;

        for (ProtectionPort port : List.of(claims, towns)) {
            assertThat(port.canBreak(Doubles.offline(), location()).verdict()).isEqualTo(Verdict.ABSTAIN);
            assertThat(port.canUse(Doubles.online(), location()).verdict()).isEqualTo(Verdict.ABSTAIN);
            assertThat(port.canUse(ProtectionActor.none(), location()).verdict()).isEqualTo(Verdict.ABSTAIN);
            assertThat(port.canDamage(ProtectionActor.none(), victim()).verdict()).isEqualTo(Verdict.ABSTAIN);
        }
        assertThat(asked).isEmpty();
    }
}
