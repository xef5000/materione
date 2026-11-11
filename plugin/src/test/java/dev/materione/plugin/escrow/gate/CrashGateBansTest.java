package dev.materione.plugin.escrow.gate;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

/**
 * The crash gate parks a thread, which no other escrow class may do (I4): {@link CrashGate} is the only parking site of
 * the escrow code, and it is inert unless the JVM carries the test property. Proven against a planted violation, so
 * the rule cannot rot into one that can no longer fail.
 */
class CrashGateBansTest {

    private static final DescribedPredicate<JavaMethodCall> PARKS =
            DescribedPredicate.describe("a thread park or sleep", call -> {
                String owner = call.getTargetOwner().getFullName();
                return owner.equals(LockSupport.class.getName()) && call.getName().startsWith("park")
                        || owner.equals(Thread.class.getName()) && call.getName().equals("sleep");
            });

    private static ArchRule onlyTheGateParks(String pkg) {
        return noClasses().that().resideInAPackage(pkg)
                .and().doNotHaveFullyQualifiedName(CrashGate.class.getName())
                .should().callMethodWhere(PARKS)
                .because("a thread that parks stops the server: CrashGate is the one place that may, and only under"
                        + " -Dmaterione.escrow.gate");
    }

    @Test
    void onlyTheCrashGateParksAThreadInTheEscrowCode() {
        JavaClasses escrow = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("dev.materione.plugin.escrow");
        assertThat(escrow.size()).isGreaterThan(30);
        onlyTheGateParks("dev.materione.plugin.escrow..").check(escrow);
    }

    @Test
    void theRuleFailsOnAPlantedParker() {
        JavaClasses planted = new ClassFileImporter().importClasses(PlantedParker.class);
        assertThatThrownBy(() -> onlyTheGateParks("dev.materione.plugin.escrow..").check(planted))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("LockSupport.park");
    }

    /** Fixture: a class that parks its thread. */
    static final class PlantedParker {
        void violate() {
            LockSupport.park();
        }
    }
}
