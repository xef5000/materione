package dev.materione.plugin.view;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import dev.materione.plugin.plantedview.PlantedViewerMutation;
import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ParamReader;
import dev.materione.behavior.logic.EngineExpressions;
import dev.materione.plugin.gui.GuiFixture;
import dev.materione.plugin.gui.event.GuiContexts;
import dev.materione.plugin.gui.input.ClickKind;
import dev.materione.plugin.gui.input.GuiClick;
import dev.materione.plugin.gui.session.GuiViewerState;
import dev.materione.plugin.logic.action.view.BackViewAction;
import dev.materione.plugin.logic.action.view.OpenViewAction;
import dev.materione.plugin.view.open.OpenRequest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The ban test of views.md section 11 and 5.6 (D8): <b>no viewer-record mutation off the owner thread</b>. A
 * recording guard is installed on the registry, so every mutation of viewer-local state reports the thread it ran on;
 * the runtime is driven through a recording gateway, a single "owner" thread that stands for the viewer's region
 * thread, and then read from other threads the way the live refresh, a placeholder and the write observer of row V1
 * read it. Any mutation that did not run on the owner thread fails the test. A second test pins where the mutators may be
 * called from at all.
 */
class ViewerOwnerThreadTest {

    private static final String MENU = """
            menu:
              title: Menu
              container: chest_3
              layers:
                - { id: go, type: button, x: 8, y: 18, width: 16, height: 16 }
                - { id: close, type: button, x: 26, y: 18, width: 16, height: 16 }
              behavior:
                - on: gui.click
                  layer: go
                  do:
                    - set_var: { var: session.tab, value: "weapons" }
                    - view.open: shop
                - on: gui.click
                  layer: close
                  do:
                    - view.close
            """;
    private static final String SHOP = "shop:\n  title: Shop\n  container: chest_3\n  layers: []\n";

    /** One mutation as the guard saw it. */
    private record Mutation(String thread, String what) {}

    /** The recording gateway: runs a task on the owner thread, as the entity scheduler does for the player's region. */
    private final ExecutorService owner = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "owner-region");
        thread.setDaemon(true);
        return thread;
    });
    private final List<Mutation> mutations = new CopyOnWriteArrayList<>();
    private GuiFixture f;

    @BeforeEach
    void setUp() throws Exception {
        f = new GuiFixture();
        f.sessions.guard((record, what) -> mutations.add(new Mutation(Thread.currentThread().getName(), what)));
        onOwner(() -> {
            f.gui(MENU);
            f.gui(SHOP);
        });
    }

    @AfterEach
    void tearDown() {
        owner.shutdownNow();
    }

    private void onOwner(Runnable task) throws Exception {
        owner.submit(task).get(10, TimeUnit.SECONDS);
    }

    private List<Mutation> offOwner() {
        return mutations.stream().filter(mutation -> !mutation.thread().equals("owner-region")).toList();
    }

    @Test
    void theWholeLifeOfAViewerMutatesItsRecordOnlyOnTheOwnerThread() throws Exception {
        onOwner(() -> {
            ViewerRecord menu = f.opener.open(OpenRequest.of(
                    f.viewer, GuiFixture.id("menu"), Map.of("page", "2"), OpenRequest.Cause.API)).record();
            f.router.route(new GuiClick(f.viewer, menu, 0, ClickKind.LEFT, 0)); // writes a session value, opens the shop
            ViewerRecord shop = f.sessions.current(f.viewer.id(), "gui").orElseThrow();
            f.refresh.refresh(f.viewer, shop);
            f.ticks.advance(5);
            var back = new BackViewAction(f.bridge.view());
            assertThat(back.execute(back.parse(ParamReader.of(Map.of(), EngineExpressions.INSTANCE)),
                    GuiContexts.build(f.viewer, shop, Map.of()))).isEqualTo(ActionOutcome.OK);
            var open = new OpenViewAction(f.bridge.view());
            assertThat(open.execute(open.parse(ParamReader.of(Map.of("view", "menu"), EngineExpressions.INSTANCE)),
                    GuiContexts.build(f.viewer, f.sessions.current(f.viewer.id()).orElseThrow(), Map.of())))
                    .isEqualTo(ActionOutcome.OK);
            f.lifecycle.playerQuit(f.viewer);
        });

        assertThat(mutations).as("the guard saw the mutations at all").isNotEmpty();
        assertThat(offOwner()).as("mutations off the owner thread").isEmpty();
    }

    @Test
    void readingFromOtherThreadsMutatesNothing() throws Exception {
        NamespacedId menuId = GuiFixture.id("menu");
        onOwner(() -> f.opener.open(OpenRequest.of(f.viewer, menuId, Map.of(), OpenRequest.Cause.API)));
        mutations.clear();

        // the live refresh walks every record, a placeholder reads the current and last view, row V1's write observer
        // looks viewers up by definition: all of it from a thread that is not the owner's
        Thread reader = new Thread(() -> {
            f.sessions.showing(GuiViewerState.KIND, menuId).forEach(record -> record.ref().toString());
            f.sessions.all().forEach(ViewerRecord::isOpen);
            new ViewRegistryPort(f.sessions).openView(f.viewer.id());
            new ViewRegistryPort(f.sessions).lastView(f.viewer.id());
            f.sessions.current(f.viewer.id()).ifPresent(record -> GuiViewerState.of(record).rendered());
            f.sessions.showingKey("any");
        }, "foreign-reader");
        reader.start();
        reader.join(10_000);

        assertThat(mutations).as("a read mutated viewer-local state").isEmpty();
    }

    @Test
    void theGuardCatchesAMutationOffTheOwnerThread() throws Exception {
        ViewerRecord[] record = new ViewerRecord[1];
        onOwner(() -> record[0] = f.opener.open(OpenRequest.of(
                f.viewer, GuiFixture.id("menu"), Map.of(), OpenRequest.Cause.API)).record());
        mutations.clear();

        Thread intruder = new Thread(() -> GuiViewerState.of(record[0]).advanceTicks(1), "foreign-writer");
        intruder.start();
        intruder.join(10_000);

        assertThat(offOwner()).extracting(Mutation::thread).containsExactly("foreign-writer");
    }

    /** The one place that may call a mutator: the view and GUI runtime packages, which run on the owner thread. */
    private static final String[] MAY_MUTATE = {"dev.materione.plugin.view..", "dev.materione.plugin.gui.."};

    private static ArchRule onlyTheViewAndGuiRuntimesCallAMutator(String... allowed) {
        return noClasses().that().resideOutsideOfPackages(allowed)
                .should().callMethodWhere(new DescribedPredicate<JavaMethodCall>("a @ViewerMutator method") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        return call.getTarget().resolveMember().map(m -> m.isAnnotatedWith(ViewerMutator.class))
                                .orElse(false);
                    }
                })
                .because("a viewer record is owner-thread state (D8): its mutators run only in the view and GUI runtimes");
    }

    private static JavaClasses mainClasses() {
        return new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("dev.materione.plugin");
    }

    @Test
    void onlyTheViewAndGuiRuntimesCallAMutatorOfAViewerRecord() {
        JavaClasses plugin = mainClasses();
        assertThat(plugin.size()).as("the plugin classes were found").isGreaterThan(100);
        onlyTheViewAndGuiRuntimesCallAMutator(MAY_MUTATE).check(plugin);
    }

    @Test
    void everyMethodThatReportsToTheGuardIsMarkedAMutator() {
        JavaClasses plugin = mainClasses();
        var reporting = plugin.stream().flatMap(type -> type.getMethods().stream())
                .filter(method -> !method.getName().equals("mutating"))
                .filter(method -> method.getMethodCallsFromSelf().stream().anyMatch(call ->
                        call.getName().equals("mutating")
                                && (call.getTargetOwner().isEquivalentTo(ViewerRecord.class)
                                        || call.getTargetOwner().isEquivalentTo(GuiViewerState.class))))
                .toList();

        assertThat(reporting).as("methods that call the guard were found").hasSizeGreaterThan(5);
        assertThat(reporting.stream().filter(method -> !method.isAnnotatedWith(ViewerMutator.class))
                .map(method -> method.getFullName()).toList())
                .as("methods that report a mutation to the guard without the @ViewerMutator marker").isEmpty();
    }

    @Test
    void theRuleFailsOnAPlantedNoArgumentMutatorCall() {
        JavaClasses planted = new ClassFileImporter().importClasses(PlantedViewerMutation.class);
        assertThatThrownBy(() -> onlyTheViewAndGuiRuntimesCallAMutator(MAY_MUTATE).check(planted))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("forgetWindow");
    }
}
