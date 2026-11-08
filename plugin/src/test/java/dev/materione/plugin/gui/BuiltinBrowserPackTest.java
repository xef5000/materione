package dev.materione.plugin.gui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.gui.TextureSource;
import dev.materione.core.pipeline.BuiltinPacks;
import dev.materione.core.pipeline.PackLayout;
import dev.materione.core.runtime.RuntimeContentIndex;
import dev.materione.core.runtime.RuntimeContentIndex.Entry;
import dev.materione.core.runtime.RuntimeContentIndex.Type;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.content.gui.GuiCompiler;
import dev.materione.plugin.gui.input.ClickKind;
import dev.materione.plugin.gui.input.GuiClick;
import dev.materione.plugin.gui.open.OpenPlan;
import dev.materione.plugin.gui.open.OpenRequest;
import dev.materione.plugin.gui.open.OpenResult;
import dev.materione.plugin.gui.open.OpenSpecGate;
import dev.materione.plugin.gui.open.PlayerNames;
import dev.materione.plugin.gui.render.GuiRenderer;
import dev.materione.plugin.gui.render.RenderReporter;
import dev.materione.plugin.gui.rules.GuiRuleSet;
import dev.materione.plugin.gui.session.GuiSession;
import dev.materione.behavior.logic.RegistryLogicLookup;
import dev.materione.core.registry.RegistryService;
import dev.materione.plugin.logic.BuiltinLogic;
import dev.materione.plugin.logic.LogicTestSupport;
import dev.materione.plugin.logic.support.ItemCatalog;
import dev.materione.plugin.gui.prompt.PromptRuntime;
import dev.materione.api.scheduling.SchedulingGateway;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The plugin's own pack and the content browser it ships (runtime-systems §8.3, master §7.3).
 *
 * <p>The first half reads the pack the way the server does, from the classpath, and compiles its GUI with the real
 * compilers and zero diagnostics, as {@link ShowcasePackTest} does for the showcase. The second half runs the real
 * file: it is published to a fixture as {@code materione:browser} and clicked through the real router and rule engine,
 * over a content index. Only one step is replaced, by a recording action: {@code give_item}, which needs a server to
 * build a stack (its own tests cover it). The pack is read from the classpath and never copied, so the test and the
 * server see the same bytes.
 */
class BuiltinBrowserPackTest {

    private static final String GIVE = "give_item: { item: \"${args.item}\", amount: \"${args.amount}\" }";
    private static final NamespacedId BROWSER = NamespacedId.of("materione", "browser");

    private final List<AutoCloseable> open = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable closeable : open) {
            closeable.close();
        }
    }

    // --- the pack as shipped -----------------------------------------------------------------------------------------

    private BuiltinPacks shipped() {
        var problems = new ArrayList<Diagnostic>();
        BuiltinPacks packs = BuiltinPacks.fromClasspath(getClass().getClassLoader(), problems::add);
        open.add(packs);
        assertThat(problems).isEmpty();
        return packs;
    }

    @Test
    void theJarShipsThePluginsOwnPackAndItsDocumentSet() throws IOException {
        BuiltinPacks packs = shipped();

        assertThat(packs.ids()).containsExactly("materione");
        Path pack = packs.packs().get(0).directory();
        assertThat(Files.readString(pack.resolve(PackLayout.MANIFEST_FILE))).contains("id: materione");
        assertThat(PackLayout.sourceDocuments(pack, failure -> { }))
                .extracting(file -> pack.relativize(file).toString().replace('\\', '/'))
                .containsExactly("pack.yml", "guis/browser.yml");
    }

    @Test
    void everyShippedDocumentRoundTripsByteForByte() throws IOException {
        // master §5.6: load then an untouched save is byte-identical, for the plugin's own files like any other
        Path pack = shipped().packs().get(0).directory();
        for (Path file : PackLayout.sourceDocuments(pack, failure -> { })) {
            String raw = Files.readString(file);
            assertThat(dev.materione.core.document.LosslessYaml.parse(raw).save()).as(file.toString()).isEqualTo(raw);
        }
    }

    @Test
    void theBrowserGuiCompilesWithZeroDiagnostics() throws IOException {
        // the plugin's own built-ins (prompt, gui.* and the rest), as the live server registers them at boot
        var registries = new RegistryService();
        var service = LogicTestSupport.newService(registries);
        var host = LogicTestSupport.owner("materione");
        BuiltinLogic.register(service, host, ItemCatalog.none(), new GuiBridge());
        BuiltinLogic.registerPrompts(service, host, PromptRuntime.create(GuiFixture.plugin(), unusedScheduling(),
                new GuiBridge(), diagnostic -> { }));
        var lookup = new RegistryLogicLookup(registries, id -> Optional.empty());

        var definition = new GuiFixture("materione", ItemCatalog.none()).definition(browserYaml());
        assertThat(definition.id()).isEqualTo(BROWSER);
        assertThat(definition.skipped()).as("skipped layers").isEmpty();
        assertThat(definition.rules().problems()).as("rule problems").isEmpty();

        var overlays = new AtomicInteger(0xE201);
        var compiled = GuiCompiler.compile(definition, 0xE200, TextureSource.NONE,
                (layer, frame) -> overlays.getAndIncrement());
        assertThat(compiled.diagnostics()).as("layer diagnostics").isEmpty();
        // no art at all: the browser adds no texture to anyone's resource pack
        assertThat(compiled.model().lists()).extracting(list -> list.layerId()).containsExactly("entries");

        var rules = GuiRuleSet.compile(BROWSER, definition.rules(), lookup);
        assertThat(rules.problems()).as("rule diagnostics").isEmpty();

        OpenPlan plan = OpenPlan.compile(BROWSER, definition.rules(), lookup);
        assertThat(plan).isNotNull();
        assertThat(plan.problems()).isEmpty();
        assertThat(plan.permission()).isEqualTo("materione.browse");
        assertThat(plan.commands()).as("no top-level command: /materione browse opens it by id").isEmpty();
        assertThat(plan.args()).extracting(arg -> arg.name()).containsExactly("path", "search");
    }

    // --- the file, run -------------------------------------------------------------------------------------------------

    private static String browserYaml() throws IOException {
        try (InputStream in = BuiltinBrowserPackTest.class.getClassLoader()
                .getResourceAsStream("builtin-packs/materione/guis/browser.yml")) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Entry item(String id, String name, String... folders) {
        return new Entry(NamespacedId.parse(id), Type.ITEM, name, List.of(folders));
    }

    private GuiFixture fixture;
    private GuiSession session;

    @BeforeEach
    void fixture() {
        // a small server: two packs and some folders
        var entries = new ArrayList<>(List.of(
                item("shop:ruby", "Ruby", "gems"),
                item("shop:emerald", "Emerald", "gems"),
                item("shop:raw_ruby", "Raw Ruby", "gems", "raw"),
                item("shop:iron_sword", "Iron Sword", "tools"),
                item("shop:coin", "Coin"),
                item("zoo:lion", "Lion", "animals")));
        useIndex(entries);
    }

    private void useIndex(List<Entry> entries) {
        // the items are not live Materione items here: building a stack needs a server, so every row shows as the
        // barrier a missing item shows as, which is what the click, the page and the names are read from
        RuntimeRegistries registries = RuntimeRegistries.builder()
                .view(RuntimeContentIndex.KEY, RuntimeContentIndex.of(entries)).build();
        fixture = new GuiFixture("materione", () -> Optional.of(registries));
        fixture.windows.renderer = new GuiRenderer(RenderReporter.SILENT);
        fixture.permissions.addAll(List.of("materione.browse", "materione.browse.get"));
        fixture.opener.gate(new OpenSpecGate(
                (viewer, node) -> viewer.player().hasPermission(node), PlayerNames.NONE, (rule, context, owner) -> { }));
        try {
            String yaml = browserYaml();
            assertThat(yaml).contains(GIVE);
            // the one replaced step: a stack needs a server, so the take is recorded instead of built
            // a vanilla material needs a server to resolve, so the icons become ids that are not live: barriers
            fixture.gui(yaml.replace(GIVE, "mark: \"take ${args.item} x${args.amount}\"")
                    .replace("minecraft:", "missing:"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private OpenResult openBrowser(Map<String, String> args) {
        OpenResult result = fixture.opener.open(OpenRequest.of(fixture.viewer, BROWSER, args, OpenRequest.Cause.API));
        session = result.session();
        return result;
    }

    private GuiSession current() {
        return fixture.sessions.current(fixture.player.getUniqueId()).orElseThrow();
    }

    private void click(int slot, ClickKind kind) {
        fixture.router.route(new GuiClick(fixture.viewer, current(), slot, kind, 0));
    }

    private String shown(int slot) {
        return current().rendered().lists().shownAt(slot)
                .map(shown -> shown.entry().get("kind") + ":" + shown.entry().get("name"))
                .orElse(null);
    }

    @Test
    void theRootListsThePacksAndThereIsNoBackButtonThere() {
        assertThat(openBrowser(Map.of()).ok()).isTrue();

        assertThat(shown(0)).isEqualTo("pack:shop");
        assertThat(shown(1)).isEqualTo("pack:zoo");
        assertThat(shown(2)).isNull();
        assertThat(current().rendered().hidden()).contains("back", "clear", "no_results").doesNotContain("pages");
    }

    @Test
    void aPackOpensItsFoldersFirstThenItsItemsAndBackReturnsToThePacks() {
        openBrowser(Map.of());

        click(0, ClickKind.LEFT); // shop

        assertThat(current().args()).containsEntry("path", "shop");
        assertThat(shown(0)).isEqualTo("folder:gems");
        assertThat(shown(1)).isEqualTo("folder:tools");
        assertThat(shown(2)).isEqualTo("item:Coin");
        assertThat(current().rendered().hidden()).doesNotContain("back");

        click(45, ClickKind.LEFT); // back

        assertThat(current().args()).doesNotContainKey("path");
        assertThat(shown(0)).isEqualTo("pack:shop");
    }

    @Test
    void foldersNestAndEachStepKeepsTheLocationInTheWindowsArguments() {
        openBrowser(Map.of());
        click(0, ClickKind.LEFT); // shop
        click(0, ClickKind.LEFT); // gems

        assertThat(current().args()).containsEntry("path", "shop/gems");
        assertThat(shown(0)).isEqualTo("folder:raw");
        assertThat(shown(1)).isEqualTo("item:Emerald");
        assertThat(shown(2)).isEqualTo("item:Ruby");

        click(0, ClickKind.LEFT); // raw
        assertThat(current().args()).containsEntry("path", "shop/gems/raw");
        assertThat(shown(0)).isEqualTo("item:Raw Ruby");

        click(45, ClickKind.LEFT);
        click(45, ClickKind.LEFT);
        assertThat(current().args()).containsEntry("path", "shop");
    }

    @Test
    void aLeftClickTakesOneAndAShiftClickAFullStackOfThatItem() {
        openBrowser(Map.of("path", "shop/gems"));
        click(2, ClickKind.LEFT);
        click(2, ClickKind.SHIFT_LEFT);
        click(2, ClickKind.RIGHT); // not a take
        click(2, ClickKind.MIDDLE);
        click(2, ClickKind.DROP);

        // the full stack is the entry's own `stack` (the source reads it from the item's material)
        assertThat(fixture.ran).containsExactly("mark:take shop:ruby x1", "mark:take shop:ruby x64");
    }

    @Test
    void everyTakeChecksThePermissionAndADeniedOneGivesNothingAndSaysWhy() {
        openBrowser(Map.of("path", "shop/gems"));
        click(2, ClickKind.LEFT);
        assertThat(fixture.ran).hasSize(1);

        fixture.permissions.remove("materione.browse.get"); // revoked while the window is open
        click(2, ClickKind.LEFT);
        click(2, ClickKind.SHIFT_LEFT);

        assertThat(fixture.ran).as("nothing more was taken").hasSize(1);
        assertThat(fixture.messages).hasSize(2).allSatisfy(message ->
                assertThat(message).contains("materione.browse.get"));

        fixture.permissions.add("materione.browse.get");
        click(2, ClickKind.LEFT);
        assertThat(fixture.ran).hasSize(2);
    }

    @Test
    void aPlayerWithoutTheOpenPermissionIsRefusedByTheGuisOwnBlock() {
        fixture.permissions.remove("materione.browse");

        OpenResult result = openBrowser(Map.of());

        assertThat(result.outcome()).isEqualTo(OpenResult.Outcome.DENIED);
        assertThat(result.reason()).contains("materione.browse");
        assertThat(fixture.sessions.current(fixture.player.getUniqueId())).isEmpty();
    }

    @Test
    void searchingListsEveryMatchingItemFlatAndEveryWordMustMatch() {
        openBrowser(Map.of("search", "ruby"));

        assertThat(shown(0)).isEqualTo("item:Raw Ruby");
        assertThat(shown(1)).isEqualTo("item:Ruby");
        assertThat(shown(2)).isNull();
        assertThat(current().rendered().hidden()).contains("nothing_here", "no_results").doesNotContain("clear");

        // words in any order, against the id or the name
        openBrowser(Map.of("search", "ruby shop:raw"));
        assertThat(shown(0)).isEqualTo("item:Raw Ruby");
        assertThat(shown(1)).isNull();

        // a pack, a folder and the id all count
        openBrowser(Map.of("search", "SWORD"));
        assertThat(shown(0)).isEqualTo("item:Iron Sword");
    }

    @Test
    void aSearchWithNoMatchShowsTheNoResultsMessageAndClearBringsTheTreeBack() {
        openBrowser(Map.of("search", "nothingcalledthis"));

        assertThat(shown(0)).isNull();
        assertThat(current().pages().get("entries")).isEqualTo(Map.of("number", 1, "count", 1, "size", 0));
        assertThat(current().rendered().hidden()).contains("nothing_here").doesNotContain("no_results", "clear");

        click(50, ClickKind.LEFT); // clear the search

        assertThat(shown(0)).isEqualTo("pack:shop");
        assertThat(current().rendered().hidden()).contains("no_results", "clear");
    }

    @Test
    void anEmptyServerSaysThereIsNothingHereYet() {
        useIndex(List.of());

        openBrowser(Map.of());

        assertThat(shown(0)).isNull();
        assertThat(current().rendered().hidden()).contains("no_results").doesNotContain("nothing_here");
    }

    @Test
    void theListPagesFortyFiveToAWindowAndThePageSurvivesOpeningAFolderAndComingBack() {
        var many = new ArrayList<Entry>();
        for (int i = 0; i < 50; i++) {
            many.add(item("big:item_" + String.format("%02d", i), "Item " + String.format("%02d", i)));
        }
        useIndex(many);
        openBrowser(Map.of("path", "big"));

        assertThat(shown(0)).isEqualTo("item:Item 00");
        assertThat(shown(44)).isEqualTo("item:Item 44");
        assertThat(current().pages().get("entries")).isEqualTo(Map.of("number", 1, "count", 2, "size", 50));

        click(51, ClickKind.LEFT); // next page

        assertThat(shown(0)).isEqualTo("item:Item 45");
        assertThat(shown(4)).isEqualTo("item:Item 49");
        assertThat(shown(5)).isNull();
        assertThat(current().pages().get("entries")).isEqualTo(Map.of("number", 2, "count", 2, "size", 50));

        click(51, ClickKind.LEFT); // already on the last page: nothing moves
        assertThat(current().pages().get("entries")).isEqualTo(Map.of("number", 2, "count", 2, "size", 50));

        click(47, ClickKind.LEFT); // previous page
        assertThat(shown(0)).isEqualTo("item:Item 00");
    }

    /** The templates of the `entries` list and of the other layers, as authored in the file, read from its YAML. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> layer(String id) throws IOException {
        Map<String, Map<String, Object>> document = new org.yaml.snakeyaml.Yaml().load(browserYaml());
        for (Object layer : (List<Object>) document.get("browser").get("layers")) {
            if (id.equals(((Map<String, Object>) layer).get("id"))) {
                return (Map<String, Object>) layer;
            }
        }
        throw new AssertionError("no layer " + id);
    }

    private static dev.materione.behavior.logic.MapLogicContext rowContext(Map<String, Object> entry) {
        var root = new java.util.LinkedHashMap<String, Object>();
        root.put("entry", entry);
        root.put("gui", new java.util.LinkedHashMap<String, Object>());
        return new dev.materione.behavior.logic.MapLogicContext(root);
    }

    private static String render(Object template, Map<String, Object> entry) {
        return dev.materione.behavior.logic.EngineExpressions.INSTANCE.template(String.valueOf(template))
                .render(rowContext(entry));
    }

    @Test
    @SuppressWarnings("unchecked")
    void eachRowIsDressedByKindFromTheFileItself() throws IOException {
        var template = (Map<String, Object>) layer("entries").get("entry");
        var lore = (List<Object>) template.get("lore");
        var ruby = Map.<String, Object>of("kind", "item", "id", "shop:ruby", "name", "Ruby",
                "label", "<red>Ruby", "detail", "shop:ruby", "stack", 64);
        var folder = Map.<String, Object>of("kind", "folder", "id", "", "name", "gems", "label", "gems",
                "detail", "4 items", "path", "shop/gems");
        var pack = Map.<String, Object>of("kind", "pack", "id", "", "name", "shop", "label", "shop",
                "detail", "6 items", "path", "shop");

        // an item is itself: its real id, its own name with its own formatting, its id and the two ways to take it
        assertThat(render(template.get("item"), ruby)).isEqualTo("shop:ruby");
        assertThat(render(template.get("name"), ruby)).isEqualTo("<red>Ruby");
        assertThat(render(lore.get(0), ruby)).isEqualTo("<dark_gray>shop:ruby");
        assertThat(render(lore.get(1), ruby)).contains("take one").contains("take a stack");
        // a folder and a pack are a chest and a bookshelf, named in yellow, that open
        assertThat(render(template.get("item"), folder)).isEqualTo("minecraft:chest");
        assertThat(render(template.get("item"), pack)).isEqualTo("minecraft:bookshelf");
        assertThat(render(template.get("name"), folder)).isEqualTo("<yellow>gems");
        assertThat(render(lore.get(0), folder)).isEqualTo("<dark_gray>4 items");
        assertThat(render(lore.get(1), pack)).isEqualTo("<gray>Click to open.");
        assertThat(template.get("lore_mode")).isEqualTo("append");
    }

    @Test
    void theConditionsReadAnAbsentArgumentAndAnAbsentSearchAsEmpty() throws IOException {
        var absent = new dev.materione.behavior.logic.MapLogicContext(Map.of("gui", new java.util.LinkedHashMap<>(Map.of(
                "args", Map.of(), "session", Map.of(), "page", Map.of("entries", Map.of("size", 0))))));
        var located = new dev.materione.behavior.logic.MapLogicContext(Map.of("gui", new java.util.LinkedHashMap<>(Map.of(
                "args", Map.of("path", "shop"), "session", Map.of("search", "ruby"),
                "page", Map.of("entries", Map.of("size", 3))))));
        var cleared = new dev.materione.behavior.logic.MapLogicContext(Map.of("gui", new java.util.LinkedHashMap<>(Map.of(
                "args", Map.of("path", ""), "session", Map.of("search", ""),
                "page", Map.of("entries", Map.of("size", 0))))));

        record Case(String layer, boolean absent, boolean located, boolean cleared) {}
        for (Case c : List.of(
                new Case("back", false, true, false),
                new Case("clear", false, true, false),
                new Case("nothing_here", true, false, true),
                new Case("no_results", false, false, false))) {
            String when = String.valueOf(layer(c.layer()).get("visible_if"));
            var expression = dev.materione.behavior.logic.EngineExpressions.INSTANCE.expression(when);
            assertThat(expression.evaluate(absent)).as(c.layer() + " with nothing set").isEqualTo(c.absent());
            assertThat(expression.evaluate(located)).as(c.layer() + " located and searching").isEqualTo(c.located());
            assertThat(expression.evaluate(cleared)).as(c.layer() + " set to empty").isEqualTo(c.cleared());
        }
        // an empty page while searching is the other message
        var noMatch = new dev.materione.behavior.logic.MapLogicContext(Map.of("gui", new java.util.LinkedHashMap<>(Map.of(
                "args", Map.of(), "session", Map.of("search", "zzz"), "page", Map.of("entries", Map.of("size", 0))))));
        assertThat(dev.materione.behavior.logic.EngineExpressions.INSTANCE
                .expression(String.valueOf(layer("no_results").get("visible_if"))).evaluate(noMatch)).isEqualTo(true);
        assertThat(dev.materione.behavior.logic.EngineExpressions.INSTANCE
                .expression(String.valueOf(layer("nothing_here").get("visible_if"))).evaluate(noMatch)).isEqualTo(false);
    }

    @Test
    void theCloseButtonClosesTheWindow() {
        openBrowser(Map.of());

        click(53, ClickKind.LEFT);

        assertThat(fixture.windows.log).contains("close");
    }

    /** Only the prompt's constructor reads it; these tests compile rules and never run a prompt. */
    private static SchedulingGateway unusedScheduling() {
        return (SchedulingGateway) Proxy.newProxyInstance(SchedulingGateway.class.getClassLoader(),
                new Class<?>[] {SchedulingGateway.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException("scheduling." + method.getName());
                });
    }
}
