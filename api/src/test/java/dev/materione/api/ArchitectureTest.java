package dev.materione.api;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import dev.materione.testing.arch.MaterioneArchRules;

@AnalyzeClasses(packages = "dev.materione.api")
class ArchitectureTest {

    @ArchTest
    static final ArchRule noSchedulerUsageOutsideGateway = MaterioneArchRules.NO_SCHEDULER_USAGE_OUTSIDE_GATEWAY;

    @ArchTest
    static final ArchRule noVersionQueriesOutsidePlatform = MaterioneArchRules.NO_VERSION_QUERIES_OUTSIDE_PLATFORM;

    @ArchTest
    static final ArchRule noRawThreads = MaterioneArchRules.NO_RAW_THREADS;

    @ArchTest
    static final ArchRule apiIsInternalDependencyFree = MaterioneArchRules.API_IS_INTERNAL_DEPENDENCY_FREE;
}
