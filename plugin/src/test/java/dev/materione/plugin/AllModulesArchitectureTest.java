package dev.materione.plugin;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import dev.materione.testing.arch.MaterioneArchRules;

/**
 * Whole-jar tier (plugin architecture §11): the plugin's test classpath
 * transitively holds every product module, so the cross-module rules run
 * here against all classes at once.
 */
@AnalyzeClasses(packages = "dev.materione")
class AllModulesArchitectureTest {

    @ArchTest
    static final ArchRule noSchedulerUsageOutsideGateway = MaterioneArchRules.NO_SCHEDULER_USAGE_OUTSIDE_GATEWAY;

    @ArchTest
    static final ArchRule noVersionQueriesOutsidePlatform = MaterioneArchRules.NO_VERSION_QUERIES_OUTSIDE_PLATFORM;

    @ArchTest
    static final ArchRule apiIsInternalDependencyFree = MaterioneArchRules.API_IS_INTERNAL_DEPENDENCY_FREE;

    @ArchTest
    static final ArchRule pluginIsALeaf = MaterioneArchRules.PLUGIN_IS_A_LEAF;

    @ArchTest
    static final ArchRule noRawThreads = MaterioneArchRules.NO_RAW_THREADS;

    @ArchTest
    static final ArchRule noFileIoInValidators = MaterioneArchRules.NO_FILE_IO_IN_VALIDATORS;

    /** M3 (R8): the wire protocol is reachable only from {@code dev.materione.web}. */
    @ArchTest
    static final ArchRule wireProtocolOnlyInWeb = MaterioneArchRules.WIRE_PROTOCOL_ONLY_IN_WEB;

    /** API-first (R9, api.md §4.2): the web editor consumes only {@code dev.materione.api..}. */
    @ArchTest
    static final ArchRule webOnlyConsumesApi = MaterioneArchRules.WEB_ONLY_CONSUMES_API;
}
