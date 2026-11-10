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
import dev.materione.plugin.escrow.io.EscrowLedgerStore;
import dev.materione.plugin.escrow.plantedio.PlantedIoHalf;
import dev.materione.plugin.escrow.plantedowner.PlantedOwnerHalf;
import dev.materione.plugin.escrow.runtime.EscrowLedgerListener;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * The build's teeth for gui-input-durability.md §5.6 and I4 (row L): no disk IO and no blocking wait on a region,
 * entity or main thread from escrow code. The escrow package has three halves with three different permissions:
 *
 * <ul>
 *   <li>{@code escrow.model}: pure ({@link EscrowModelBansTest});
 *   <li>{@code escrow.io}: the only half that touches files or blocks, and it may not see Bukkit at all, so it cannot
 *       do owner-thread work;
 *   <li>{@code escrow.runtime} (and rows P and B's owner-side classes, which live there): Bukkit yes, files no,
 *       blocking waits no, and it may not call {@code awaitPreload}: the pre-login listener is the one caller
 *       (that event is already off the main thread, F9).
 * </ul>
 *
 * Every rule is proven against a planted violation, so a rule that can no longer fail is itself a test failure.
 */
class EscrowIoBansTest {

    private static final String IO = "dev.materione.plugin.escrow.io..";
    private static final String RUNTIME = "dev.materione.plugin.escrow.runtime..";

    private static final DescribedPredicate<JavaMethodCall> BLOCKING_WAIT =
            DescribedPredicate.describe("a blocking wait (Future#get, Future#join, a latch or condition await)",
                    call -> {
                        String name = call.getName();
                        boolean future = call.getTargetOwner().isAssignableTo(Future.class)
                                && (name.equals("get") || name.equals("join"));
                        boolean await = name.equals("await")
                                && call.getTargetOwner().getPackageName().startsWith("java.util.concurrent");
                        return future || await;
                    });

    private static ArchRule ownerSideTouchesNoFileAndNeverBlocks(String pkg) {
        return noClasses().that().resideInAPackage(pkg)
                .should().dependOnClassesThat().resideInAnyPackage("java.io..", "java.nio.file..", "java.nio.channels..")
                .orShould().callMethodWhere(BLOCKING_WAIT)
                .because("an owner thread may not do disk IO or wait (D8, I4): ledger IO goes through"
                        + " EscrowLedgerStore futures, whose continuation hops back through the scheduling gateway");
    }

    private static ArchRule ioSideNeverSeesBukkit(String pkg) {
        return noClasses().that().resideInAPackage(pkg)
                .should().dependOnClassesThat().resideInAnyPackage("org.bukkit..", "io.papermc..")
                .because("the IO half runs on the IO executor: with no entity, inventory or world in reach it cannot"
                        + " do owner-thread work there");
    }

    private static ArchRule onlyTheListenerAwaitsAPreload(String... packages) {
        return noClasses().that().resideInAnyPackage(packages)
                .and().doNotHaveFullyQualifiedName(EscrowLedgerListener.class.getName())
                .should().callMethodWhere(new DescribedPredicate<JavaMethodCall>("EscrowLedgerStore#awaitPreload") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        return call.getTargetOwner().isEquivalentTo(EscrowLedgerStore.class)
                                && call.getName().equals("awaitPreload");
                    }
                })
                .because("awaitPreload blocks its caller: only the async pre-login handler may use it (F9)");
    }

    private static JavaClasses main(String pkg) {
        return new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests()).importPackages(pkg);
    }

    @Test
    void theOwnerSideTouchesNoFileAndNeverBlocks() {
        JavaClasses runtime = main("dev.materione.plugin.escrow.runtime");
        assertThat(runtime.size()).as("the runtime classes were found").isGreaterThan(0);
        ownerSideTouchesNoFileAndNeverBlocks(RUNTIME).check(runtime);
    }

    @Test
    void theIoSideNeverSeesBukkit() {
        JavaClasses io = main("dev.materione.plugin.escrow.io");
        assertThat(io.size()).as("the IO classes were found").isGreaterThan(5);
        ioSideNeverSeesBukkit(IO).check(io);
    }

    @Test
    void onlyTheListenerAwaitsAPreloadAcrossThePlugin() {
        JavaClasses plugin = main("dev.materione.plugin");
        assertThat(plugin.size()).isGreaterThan(100);
        onlyTheListenerAwaitsAPreload("dev.materione.plugin..").check(plugin);
    }

    @Test
    void theRulesFailOnPlantedViolations() {
        JavaClasses owner = new ClassFileImporter().importClasses(PlantedOwnerHalf.class);
        assertThatThrownBy(() -> ownerSideTouchesNoFileAndNeverBlocks("dev.materione.plugin.escrow.plantedowner..")
                        .check(owner))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("java.nio.file.Files")
                .hasMessageContaining("CompletableFuture.get")
                .hasMessageContaining("CompletableFuture.join")
                .hasMessageContaining("CountDownLatch.await");
        assertThatThrownBy(() -> onlyTheListenerAwaitsAPreload("dev.materione.plugin.escrow.plantedowner..")
                        .check(new ClassFileImporter().importClasses(PlantedOwnerHalf.class, EscrowLedgerStore.class)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("awaitPreload");
        JavaClasses io = new ClassFileImporter().importClasses(PlantedIoHalf.class);
        assertThatThrownBy(() -> ioSideNeverSeesBukkit("dev.materione.plugin.escrow.plantedio..").check(io))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("org.bukkit.entity.Player");
    }
}
