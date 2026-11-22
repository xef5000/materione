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
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/** The BentoBox adapter's logic over a test double of BentoBox (no plugin, no server). */
class BentoBoxAdapterTest {

    private final List<String> asked = new ArrayList<>();
    /** What the island says; null is "no protected island here". */
    private Boolean answer = true;

    private final ProtectionPort port = BentoBoxAdapter.port(new BentoBoxAdapter.Api() {
        @Override
        public Boolean online(ProtectionAction action, Player player, Location location, Entity victim) {
            asked.add("online " + action);
            return answer;
        }

        @Override
        public Boolean offline(ProtectionAction action, OfflinePlayer player, Location location, Entity victim) {
            asked.add("offline " + action);
            return answer;
        }

        @Override
        public Boolean nobody(ProtectionAction action, Location location, Entity victim) {
            asked.add("nobody " + action);
            return answer;
        }
    });

    @Test
    void anIslandsFlagsAllowOrDenyAnOnlineOrOfflinePlayer() {
        assertThat(port.canBreak(Doubles.online(), location()).verdict()).isEqualTo(Verdict.ALLOW);
        answer = false;
        var denied = port.canPlace(Doubles.offline(), location());

        assertThat(denied.denied()).isTrue();
        assertThat(denied.provider()).isEqualTo("BentoBox");
        assertThat(denied.reason()).contains("island");
        assertThat(asked).containsExactly("online BREAK", "offline PLACE");
    }

    @Test
    void aPlaceWithNoProtectedIslandIsNotCovered() {
        answer = null;

        assertThat(port.canBreak(Doubles.online(), location()).verdict()).isEqualTo(Verdict.ABSTAIN);
        assertThat(port.canInteract(Doubles.offline(), location()).verdict()).isEqualTo(Verdict.ABSTAIN);
        assertThat(port.canBreak(ProtectionActor.none(), location()).verdict()).isEqualTo(Verdict.ABSTAIN);
    }

    @Test
    void noActorIsJudgedByTheIslandsSettingsAndActorlessDamageAbstains() {
        answer = false;

        assertThat(port.canBreak(ProtectionActor.none(), location()).denied()).isTrue();
        assertThat(port.canDamage(ProtectionActor.none(), victim()).verdict()).isEqualTo(Verdict.ABSTAIN);
        assertThat(asked).containsExactly("nobody BREAK");
    }

    @Test
    void damageByAPlayerIsAskedOfTheIsland() {
        answer = false;

        assertThat(port.canDamage(Doubles.online(), victim()).denied()).isTrue();
        assertThat(asked).containsExactly("online DAMAGE");
    }
}
