package dev.materione.integrations.protection;

import static dev.materione.integrations.protection.Doubles.location;
import static dev.materione.integrations.protection.Doubles.victim;
import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.protection.ProtectionAction;
import dev.materione.api.protection.ProtectionActor;
import dev.materione.api.protection.ProtectionAnswer.Verdict;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/** The WorldGuard adapter's logic over a test double of WorldGuard (no WorldGuard, no server). */
class WorldGuardAdapterTest {

    private final List<String> asked = new ArrayList<>();
    private boolean allowed = true;

    private final WorldGuardAdapter.Api api = new WorldGuardAdapter.Api() {
        @Override
        public boolean online(ProtectionAction action, Player player, Location location, Entity victim) {
            asked.add("online " + action);
            return allowed;
        }

        @Override
        public boolean offline(ProtectionAction action, OfflinePlayer player, Location location) {
            asked.add("offline " + action);
            return allowed;
        }

        @Override
        public boolean nobody(ProtectionAction action, Location location) {
            asked.add("nobody " + action);
            return allowed;
        }
    };

    private final dev.materione.api.protection.ProtectionPort port = WorldGuardAdapter.port(api);

    @Test
    void anOnlinePlayerIsAskedAsThemselvesAndTheVerdictIsAllowOrDeny() {
        assertThat(port.canBreak(Doubles.online(), location()).verdict()).isEqualTo(Verdict.ALLOW);
        allowed = false;
        var answer = port.canPlace(Doubles.online(), location());

        assertThat(answer.verdict()).isEqualTo(Verdict.DENY);
        assertThat(answer.provider()).isEqualTo("WorldGuard");
        assertThat(answer.reason()).contains("region");
        assertThat(asked).containsExactly("online BREAK", "online PLACE");
    }

    @Test
    void aPlayerKnownByIdAndNoActorAreAskedThroughTheirOwnQueries() {
        allowed = false;

        assertThat(port.canInteract(Doubles.offline(), location()).denied()).isTrue();
        assertThat(port.canBreak(ProtectionActor.none(), location()).denied()).isTrue();

        assertThat(asked).containsExactly("offline INTERACT", "nobody BREAK");
    }

    @Test
    void damageAndPvpAreAskedOfAnOnlinePlayerOnly() {
        allowed = false;

        assertThat(port.canDamage(Doubles.online(), victim()).denied()).isTrue();
        assertThat(port.canDamage(Doubles.offline(), victim()).verdict()).isEqualTo(Verdict.ABSTAIN);
        assertThat(port.canDamage(ProtectionActor.none(), victim()).verdict()).isEqualTo(Verdict.ABSTAIN);

        assertThat(asked).containsExactly("online DAMAGE");
    }
}
