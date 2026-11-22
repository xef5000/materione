package dev.materione.integrations.protection;

import static dev.materione.integrations.protection.Doubles.location;
import static dev.materione.integrations.protection.Doubles.victim;
import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.protection.ProtectionAction;
import dev.materione.api.protection.ProtectionActor;
import dev.materione.api.protection.ProtectionAnswer;
import dev.materione.api.protection.ProtectionSafety;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/** The routing every adapter shares (protection.md section 9): five questions, three kinds of actor, one provider name. */
class BackendPortTest {

    private final List<String> calls = new ArrayList<>();

    private final Backend backend = new Backend() {
        @Override
        public Decision online(ProtectionAction action, Player player, Location location, Entity victim) {
            calls.add("online " + action + (victim != null ? " victim" : ""));
            return Decision.deny("online says no");
        }

        @Override
        public Decision offline(ProtectionAction action, OfflinePlayer player, Location location, Entity victim) {
            calls.add("offline " + action);
            return Decision.allow();
        }

        @Override
        public Decision nobody(ProtectionAction action, Location location, Entity victim) {
            calls.add("nobody " + action);
            return null; // a backend that answers nothing is an abstain, not a crash
        }
    };

    private final BackendPort port = new BackendPort("Stand-in", "what it covers", ProtectionSafety.OWNING_REGION, backend);

    @Test
    void eachQuestionGoesToTheBackendMethodOfItsActorAndCarriesTheProvidersName() {
        ProtectionAnswer denied = port.canBreak(Doubles.online(), location());
        assertThat(denied.denied()).isTrue();
        assertThat(denied.provider()).isEqualTo("Stand-in");
        assertThat(denied.reason()).isEqualTo("online says no");

        assertThat(port.canPlace(Doubles.offline(), location()).verdict()).isEqualTo(ProtectionAnswer.Verdict.ALLOW);
        assertThat(port.canInteract(ProtectionActor.none(), location()).verdict()).isEqualTo(ProtectionAnswer.Verdict.ABSTAIN);
        port.canUse(Doubles.online(), location());
        port.canDamage(Doubles.online(), victim());

        assertThat(calls).containsExactly("online BREAK", "offline PLACE", "nobody INTERACT", "online USE",
                "online DAMAGE victim");
    }

    @Test
    void theSelfDescriptionNamesTheProviderAndTheSafetyIsWhatItDeclared() {
        assertThat(port.name()).isEqualTo("Stand-in");
        assertThat(port.describe().displayName()).isEqualTo("Stand-in");
        assertThat(port.describe().description()).isEqualTo("what it covers");
        assertThat(port.safety()).isEqualTo(ProtectionSafety.OWNING_REGION);
    }

    @Test
    void aBackendThatImplementsOnlyOnlineAbstainsForTheRest() {
        Backend onlineOnly = (action, player, location, victim) -> Decision.allow();
        var narrow = new BackendPort("Narrow", "x", ProtectionSafety.ANY_THREAD, onlineOnly);

        assertThat(narrow.canBreak(Doubles.offline(), location()).verdict()).isEqualTo(ProtectionAnswer.Verdict.ABSTAIN);
        assertThat(narrow.canBreak(ProtectionActor.none(), location()).verdict()).isEqualTo(ProtectionAnswer.Verdict.ABSTAIN);
        assertThat(narrow.canBreak(Doubles.online(), location()).verdict()).isEqualTo(ProtectionAnswer.Verdict.ALLOW);
    }
}
