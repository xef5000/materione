package dev.materione.plugin.escrow;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import dev.materione.plugin.escrow.planted.PlantedImpure;
import org.junit.jupiter.api.Test;

/**
 * The build's teeth for gui-input-durability.md §5.6 and §8 (row M): the escrow model is pure. It depends on the JDK's
 * value types only: no Bukkit or Paper type, no file or channel IO, no thread or future, no clock, no random source,
 * and nothing else of this plugin. The runtime halves (rows L and P) live in sibling packages and may do what the
 * model may not; this guard is what keeps the model embeddable in the proof. The rules are proven against a planted
 * violation so a rule that can no longer fail is itself a test failure.
 */
class EscrowModelBansTest {

    private static final String MODEL = "dev.materione.plugin.escrow.model..";

    private static ArchRule onlyJdkValueTypes(String pkg) {
        return classes().that().resideInAPackage(pkg)
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        pkg, "java.lang..", "java.util", "java.util.function..", "java.util.zip..", "java.nio",
                        "java.nio.charset..", "java.security..", "java.util.stream..")
                .because("the escrow model is a pure function of its inputs: values in, values out");
    }

    private static ArchRule noClockNoRandomNoThreads(String pkg) {
        return noClasses().that().resideInAPackage(pkg)
                .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.Thread")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.util.Random")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.util.UUID")
                .orShould().callMethod(System.class, "currentTimeMillis")
                .orShould().callMethod(System.class, "nanoTime")
                .because("time and ids are passed in, so the model is deterministic and replayable by seed");
    }

    private static JavaClasses mainModel() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("dev.materione.plugin.escrow.model");
    }

    @Test
    void theModelDependsOnJdkValueTypesOnly() {
        JavaClasses model = mainModel();
        assertThat(model.size()).as("the model classes were found").isGreaterThan(10);
        onlyJdkValueTypes(MODEL).check(model);
    }

    @Test
    void theModelHasNoClockRandomNorThreads() {
        noClockNoRandomNoThreads(MODEL).check(mainModel());
    }

    @Test
    void theGuardsFailOnAPlantedImpureClass() {
        JavaClasses planted = new ClassFileImporter().importClasses(PlantedImpure.class);
        String pkg = "dev.materione.plugin.escrow.planted..";
        assertThatThrownBy(() -> onlyJdkValueTypes(pkg).check(planted))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("java.nio.file.Files")
                .hasMessageContaining("CompletableFuture")
                .hasMessageContaining("org.bukkit.Bukkit");
        assertThatThrownBy(() -> noClockNoRandomNoThreads(pkg).check(planted))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("currentTimeMillis");
    }
}
