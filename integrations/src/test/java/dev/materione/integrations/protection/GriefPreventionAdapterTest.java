package dev.materione.integrations.protection;

import static dev.materione.integrations.protection.Doubles.location;
import static dev.materione.integrations.protection.Doubles.victim;
import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.protection.ProtectionActor;
import dev.materione.api.protection.ProtectionAnswer.Verdict;
import dev.materione.api.protection.ProtectionPort;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/** The GriefPrevention adapter's logic over a test double of GriefPrevention (no plugin, no server). */
class GriefPreventionAdapterTest {

    private String buildDenial;
    private String breakDenial;
    private String interactDenial;
    private boolean claimed = true;

    private final ProtectionPort port = GriefPreventionAdapter.port(new GriefPreventionAdapter.Api() {
        @Override public String build(Player player, Location location) { return buildDenial; }
        @Override public String breakBlock(Player player, Location location) { return breakDenial; }
        @Override public String interact(Player player, Location location) { return interactDenial; }
        @Override public boolean claimed(Location location) { return claimed; }
    });

    @Test
    void breakAndPlaceAreGriefPreventionsOwnDenialTextOrAnAllow() {
        assertThat(port.canBreak(Doubles.online(), location()).verdict()).isEqualTo(Verdict.ALLOW);
        breakDenial = "  You don't have build permission here.\n";
        buildDenial = "Claims are required.";

        var broken = port.canBreak(Doubles.online(), location());
        assertThat(broken.denied()).isTrue();
        assertThat(broken.reason()).isEqualTo("You don't have build permission here.");
        assertThat(broken.provider()).isEqualTo("GriefPrevention");
        assertThat(port.canPlace(Doubles.online(), location()).reason()).isEqualTo("Claims are required.");
    }

    @Test
    void interactIsOnlyAskedInsideAClaim() {
        interactDenial = "no container trust";

        assertThat(port.canInteract(Doubles.online(), location()).denied()).isTrue();
        claimed = false;
        assertThat(port.canInteract(Doubles.online(), location()).verdict()).isEqualTo(Verdict.ABSTAIN);
    }

    @Test
    void noActorIsDeniedInsideAClaimAndNotCoveredOutside() {
        assertThat(port.canBreak(ProtectionActor.none(), location()).denied()).isTrue();
        claimed = false;
        assertThat(port.canBreak(ProtectionActor.none(), location()).verdict()).isEqualTo(Verdict.ABSTAIN);
    }

    @Test
    void aPlayerKnownByIdDamageAndItemUseGetNoOpinion() {
        breakDenial = "never asked";

        assertThat(port.canBreak(Doubles.offline(), location()).verdict()).isEqualTo(Verdict.ABSTAIN);
        assertThat(port.canDamage(Doubles.online(), victim()).verdict()).isEqualTo(Verdict.ABSTAIN);
        assertThat(port.canUse(Doubles.online(), location()).verdict()).isEqualTo(Verdict.ABSTAIN);
        assertThat(port.canUse(ProtectionActor.none(), location()).verdict()).isEqualTo(Verdict.ABSTAIN);
    }
}
