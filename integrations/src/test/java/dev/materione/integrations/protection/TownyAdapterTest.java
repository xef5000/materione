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

/** The Towny adapter's logic over a test double of Towny (no plugin, no server). */
class TownyAdapterTest {

    private final List<ProtectionAction> permissionsAsked = new ArrayList<>();
    private boolean permitted = true;
    private boolean preventsDamage;
    private boolean claimed = true;

    private final ProtectionPort port = TownyAdapter.port(new TownyAdapter.Api() {
        @Override
        public boolean permitted(ProtectionAction action, Player player, Location location) {
            permissionsAsked.add(action);
            return permitted;
        }

        @Override public boolean preventsDamage(Player attacker, Entity victim) { return preventsDamage; }

        @Override public boolean claimed(Location location) { return claimed; }
    });

    @Test
    void anOnlinePlayersBreakPlaceInteractAndUseAreTownysPermission() {
        assertThat(port.canBreak(Doubles.online(), location()).verdict()).isEqualTo(Verdict.ALLOW);
        assertThat(port.canPlace(Doubles.online(), location()).verdict()).isEqualTo(Verdict.ALLOW);
        assertThat(port.canInteract(Doubles.online(), location()).verdict()).isEqualTo(Verdict.ALLOW);
        assertThat(port.canUse(Doubles.online(), location()).verdict()).isEqualTo(Verdict.ALLOW);
        assertThat(permissionsAsked).containsExactly(ProtectionAction.BREAK, ProtectionAction.PLACE,
                ProtectionAction.INTERACT, ProtectionAction.USE);

        permitted = false;
        var denied = port.canBreak(Doubles.online(), location());
        assertThat(denied.denied()).isTrue();
        assertThat(denied.provider()).isEqualTo("Towny");
    }

    @Test
    void damageIsTheCombatRulesVerdict() {
        assertThat(port.canDamage(Doubles.online(), victim()).verdict()).isEqualTo(Verdict.ALLOW);
        preventsDamage = true;
        assertThat(port.canDamage(Doubles.online(), victim()).denied()).isTrue();
        assertThat(port.canDamage(ProtectionActor.none(), victim()).verdict()).isEqualTo(Verdict.ABSTAIN);
    }

    @Test
    void noActorIsDeniedOnATownBlockAndNotCoveredInTheWilderness() {
        assertThat(port.canPlace(ProtectionActor.none(), location()).denied()).isTrue();
        claimed = false;
        assertThat(port.canPlace(ProtectionActor.none(), location()).verdict()).isEqualTo(Verdict.ABSTAIN);
    }

    @Test
    void aPlayerKnownByIdGetsNoOpinionBecauseTheCacheIsForConnectedPlayers() {
        permitted = false;

        assertThat(port.canBreak(Doubles.offline(), location()).verdict()).isEqualTo(Verdict.ABSTAIN);
        assertThat(permissionsAsked).isEmpty();
    }
}
