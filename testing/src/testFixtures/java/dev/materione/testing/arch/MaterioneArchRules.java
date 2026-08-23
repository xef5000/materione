package dev.materione.testing.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The Materione boundary rules (plugin architecture §11) — "violations fail
 * the build, not the review".
 *
 * <p>Scope notes:
 * <ul>
 *   <li>Rules govern <em>product</em> code — classes ending in {@code Test}
 *       are exempt (the test strategy itself needs threads and may touch
 *       schedulers; {@code plugin.md §10}).</li>
 *   <li>Rules are string/predicate based and never reference Bukkit classes
 *       by literal: these testFixtures load inside pure-JVM modules that have
 *       no paper-api on their test classpath.</li>
 *   <li>{@code dev.materione.testing} and {@code dev.materione.harness} are
 *       build infrastructure, never shipped — exempt where relevant.</li>
 * </ul>
 *
 * <p>M0 rule set (R1–R5). M1 adds R6 (validators are pure). Reserved:
 * wire-protocol ownership (M4, with the editor transport).
 */
public final class MaterioneArchRules {

    /**
     * R1 — only the scheduling gateway (and platform) may touch Bukkit schedulers.
     *
     * <p>Two layers, because a dependency-only rule has a blind spot: a bare
     * {@code Bukkit.getScheduler();} statement (result discarded) leaves no
     * dependency on {@code org.bukkit.scheduler} in the bytecode. The entry
     * point itself is therefore banned by call predicate.
     */
    public static final ArchRule NO_SCHEDULER_USAGE_OUTSIDE_GATEWAY = noClasses()
            .that().resideOutsideOfPackage("dev.materione.core.scheduling..")
            .and().resideOutsideOfPackage("dev.materione.platform..")
            .and().haveSimpleNameNotEndingWith("Test")
            .should().callMethodWhere(SchedulerEntryPredicate.INSTANCE)
            .orShould().dependOnClassesThat()
            .resideInAnyPackage("org.bukkit.scheduler..")
            .allowEmptyShould(true);

    /** R2 — only the platform module may query the server version. */
    public static final ArchRule NO_VERSION_QUERIES_OUTSIDE_PLATFORM = noClasses()
            .that().resideOutsideOfPackage("dev.materione.platform..")
            .and().haveSimpleNameNotEndingWith("Test")
            .should().callMethodWhere(VersionQueryPredicate.INSTANCE)
            .allowEmptyShould(true);

    /** R3 — api depends on nothing internal (master §10 dependency rules). */
    public static final ArchRule API_IS_INTERNAL_DEPENDENCY_FREE = noClasses()
            .that().resideInAPackage("dev.materione.api..")
            .and().haveSimpleNameNotEndingWith("Test")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                    "dev.materione.core..",
                    "dev.materione.platform..",
                    "dev.materione.plugin..",
                    "dev.materione.content..",
                    "dev.materione.behavior..",
                    "dev.materione.pack..",
                    "dev.materione.delivery..",
                    "dev.materione.web..",
                    "dev.materione.importers..",
                    "dev.materione.integrations..",
                    "dev.materione.harness..",
                    "dev.materione.testing..")
            .allowEmptyShould(true);

    /** R4 — the assembly module is a leaf: nothing may reach into it. */
    public static final ArchRule PLUGIN_IS_A_LEAF = noClasses()
            .that().resideOutsideOfPackage("dev.materione.plugin..")
            .should().dependOnClassesThat()
            .resideInAPackage("dev.materione.plugin..")
            .allowEmptyShould(true);

    /**
     * R5 — no raw thread creation outside the scheduling gateway. Harness and
     * testing are exempt (build infrastructure); test classes are exempt by
     * name filter.
     */
    public static final ArchRule NO_RAW_THREADS = noClasses()
            .that().resideOutsideOfPackage("dev.materione.core.scheduling..")
            .and().resideOutsideOfPackage("dev.materione.harness..")
            .and().resideOutsideOfPackage("dev.materione.testing..")
            .and().haveSimpleNameNotEndingWith("Test")
            .should().dependOnClassesThat()
            .belongToAnyOf(java.lang.Thread.class, java.util.concurrent.Executors.class)
            .allowEmptyShould(true);

    /**
     * R6 — validators are pure (plugin architecture §4.3): no file I/O.
     * Binds classes in a {@code ..validate..} package and classes whose name
     * ends in {@code Validator}. Validators receive their world through
     * parsed data (e.g. {@code ValidationEnvironment}); the filesystem
     * belongs to parse and compile.
     */
    public static final ArchRule NO_FILE_IO_IN_VALIDATORS = noClasses()
            .that(DescribedPredicate.or(
                    com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage("..validate.."),
                    com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith("Validator")))
            .should().dependOnClassesThat()
            .resideInAnyPackage("java.io..", "java.nio.file..")
            .allowEmptyShould(true);

    /**
     * R7 — the ECA engine is embeddable (D4): {@code dev.materione.behavior..}
     * has no Bukkit/Paper dependency. The engine parses rules, evaluates
     * expressions against opaque context trees and orchestrates action
     * executors that live outside it; Bukkit bindings live in {@code plugin/}
     * so addons can reuse the engine on the plain JVM.
     */
    public static final ArchRule BEHAVIOR_IS_EMBEDDABLE = noClasses()
            .that().resideInAPackage("dev.materione.behavior..")
            .and().haveSimpleNameNotEndingWith("Test")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.bukkit..", "io.papermc.paper..")
            .allowEmptyShould(true);

    private MaterioneArchRules() {}

    /** Matches calls to {@code org.bukkit.Bukkit#getBukkitVersion()}. */
    static final class VersionQueryPredicate extends DescribedPredicate<JavaMethodCall> {
        static final VersionQueryPredicate INSTANCE =
                new VersionQueryPredicate();

        private VersionQueryPredicate() {
            super("call Bukkit.getBukkitVersion()");
        }

        @Override
        public boolean test(JavaMethodCall call) {
            var target = call.getTarget();
            return target != null
                    && "org.bukkit.Bukkit".equals(target.getOwner().getName())
                    && "getBukkitVersion".equals(target.getName());
        }
    }

    /**
     * Matches calls obtaining the scheduler handle ({@code getScheduler()} on
     * {@code Bukkit} or {@code Server}) — the entry point R1 must ban even
     * when the returned handle is immediately discarded.
     */
    static final class SchedulerEntryPredicate extends DescribedPredicate<JavaMethodCall> {
        static final SchedulerEntryPredicate INSTANCE =
                new SchedulerEntryPredicate();

        private SchedulerEntryPredicate() {
            super("obtain the Bukkit scheduler via Bukkit/Server#getScheduler()");
        }

        @Override
        public boolean test(JavaMethodCall call) {
            var target = call.getTarget();
            if (target == null) {
                return false;
            }
            String owner = target.getOwner().getName();
            return "getScheduler".equals(target.getName())
                    && ("org.bukkit.Bukkit".equals(owner)
                            || "org.bukkit.Server".equals(owner));
        }
    }
}
