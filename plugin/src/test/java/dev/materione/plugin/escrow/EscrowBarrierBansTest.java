package dev.materione.plugin.escrow;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import dev.materione.plugin.escrow.planted.PlantedSaver;
import dev.materione.plugin.escrow.runtime.Owner;
import dev.materione.plugin.escrow.runtime.PlayerOwner;
import dev.materione.plugin.escrow.runtime.SaveBarrier;
import java.util.List;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * The build's teeth for the one exception to D8 (gui-input-durability.md §5.4 tier 3, §13.9, §12 row B; architecture.md
 * D8): the platform's own player save is synchronous disk IO, so it may be called from exactly one place, and that
 * place may be reached from exactly one class, the barrier, which rate-limits it.
 *
 * <ul>
 *   <li>{@code Player#saveData} has <b>one call site in the whole codebase</b>: {@link PlayerOwner#save()};
 *   <li>{@link Owner#save()} is called by {@link SaveBarrier} and nothing else, so the cooldown, the "decided exits
 *       only" entry and the read-back cannot be bypassed;
 *   <li>nothing calls {@code Server#savePlayers} or {@code Bukkit#savePlayers}, the other synchronous way to save.
 * </ul>
 *
 * Every rule is proven against a planted violation, so a rule that can no longer fail is itself a test failure.
 */
class EscrowBarrierBansTest {

    private static final DescribedPredicate<JavaMethodCall> SAVES_A_PLAYER =
            DescribedPredicate.describe("Player#saveData",
                    call -> call.getName().equals("saveData") && call.getTargetOwner().isAssignableTo(Player.class));

    private static final DescribedPredicate<JavaMethodCall> SAVES_ALL_PLAYERS =
            DescribedPredicate.describe("Server#savePlayers / Bukkit#savePlayers",
                    call -> call.getName().equals("savePlayers")
                            && (call.getTargetOwner().isAssignableTo(org.bukkit.Server.class)
                                    || call.getTargetOwner().isEquivalentTo(org.bukkit.Bukkit.class)));

    private static final DescribedPredicate<JavaMethodCall> OWNER_SAVE =
            DescribedPredicate.describe("Owner#save",
                    call -> call.getName().equals("save") && call.getTargetOwner().isEquivalentTo(Owner.class));

    private static ArchRule onlyTheOwnerAdapterSavesAPlayer(String adapter) {
        return noClasses().that().doNotHaveFullyQualifiedName(adapter)
                .should().callMethodWhere(SAVES_A_PLAYER)
                .because("Player#saveData is the D8 exception: one call site, in the Bukkit adapter of Owner");
    }

    private static ArchRule onlyTheBarrierSavesThroughAnOwner(String barrier) {
        return noClasses().that().doNotHaveFullyQualifiedName(barrier)
                .should().callMethodWhere(OWNER_SAVE)
                .because("only the barrier may ask for the platform save: it holds the rate limit and the read-back");
    }

    private static ArchRule nobodySavesEveryone() {
        return noClasses().should().callMethodWhere(SAVES_ALL_PLAYERS)
                .because("Server#savePlayers is a synchronous save of every player: the one allowed save is per player");
    }

    private static JavaClasses product() {
        return new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("dev.materione");
    }

    @Test
    void playerSaveDataHasExactlyOneCallSiteAndItIsTheOwnerAdapter() {
        JavaClasses product = product();
        assertThat(product.size()).as("the product classes were found").isGreaterThan(500);

        onlyTheOwnerAdapterSavesAPlayer(PlayerOwner.class.getName()).check(product);

        List<JavaMethodCall> calls = product.stream()
                .flatMap(type -> type.getMethodCallsFromSelf().stream())
                .filter(SAVES_A_PLAYER)
                .toList();
        assertThat(calls).as("exactly one call of Player#saveData in the codebase").hasSize(1);
        assertThat(calls.get(0).getOrigin().getFullName()).isEqualTo(PlayerOwner.class.getName() + ".save()");
    }

    @Test
    void onlyTheBarrierAsksAnOwnerToSave() {
        JavaClasses product = product();
        onlyTheBarrierSavesThroughAnOwner(SaveBarrier.class.getName()).check(product);

        long calls = product.stream().flatMap(type -> type.getMethodCallsFromSelf().stream())
                .filter(OWNER_SAVE).count();
        assertThat(calls).as("the barrier calls Owner#save from one place").isEqualTo(1);
    }

    @Test
    void nothingSavesEveryPlayerSynchronously() {
        nobodySavesEveryone().check(product());
    }

    @Test
    void theRulesFailOnPlantedViolations() {
        JavaClasses planted = new ClassFileImporter().importClasses(PlantedSaver.class);
        assertThatThrownBy(() -> onlyTheOwnerAdapterSavesAPlayer(PlayerOwner.class.getName()).check(planted))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Player#saveData");
        assertThatThrownBy(() -> onlyTheBarrierSavesThroughAnOwner(SaveBarrier.class.getName()).check(planted))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Owner#save");
        assertThatThrownBy(() -> nobodySavesEveryone().check(planted))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("savePlayers");
    }
}
