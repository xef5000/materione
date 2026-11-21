package dev.materione.plugin.gui.open;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.gui.GuiAuthoringApi.OpenArg;
import dev.materione.api.gui.GuiAuthoringApi.OpenSpec;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.plugin.gui.GuiFixture;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.command.CommandMap;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Command documents in the one command tree (logic.md §40): a name a document declares reaches the server's tree
 * through the same registrar a GUI's {@code open:} aliases do, claims after every GUI, is refused when another
 * declaration or a server command holds it (the second one, never the first), shows its typed arguments in the tree, and
 * follows a content swap by being declared, hidden and brought back. The server's command registrar is a proxy over a
 * real Brigadier dispatcher, so the tree and its {@code requires} checks are the real ones.
 */
class CommandDocumentRegistrarTest {

    private static final CommandSourceStack SOURCE = (CommandSourceStack) Proxy.newProxyInstance(
            CommandSourceStack.class.getClassLoader(), new Class<?>[] {CommandSourceStack.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "hashCode" -> 0;
                case "equals" -> proxy == args[0];
                case "toString" -> "test-source";
                default -> null;
            });

    private static final String SHOP = """
            shop:
              title: Shop
              container: chest_1
              layers: []
              open:
                commands: [shop, store]
            """;

    private GuiFixture f;
    private CommandDispatcher<CommandSourceStack> dispatcher;
    private List<Diagnostic> diagnostics;
    private OpenCommandHandler handler;
    private OpenCommandRegistrar registrar;
    private Commands commands;
    private List<OpenDeclaration> documents;
    private int reloads;
    private Runnable onReload;

    @BeforeEach
    void setUp() {
        f = new GuiFixture();
        dispatcher = new CommandDispatcher<>();
        diagnostics = new ArrayList<>();
        documents = new ArrayList<>();
        handler = new OpenCommandHandler(plugin(), null, f::view, null, PlayerNames.NONE);
        registrar = new OpenCommandRegistrar(plugin(), handler, f::view, f::view, diagnostics::add,
                () -> 0L, scheduling());
        registrar.commandDocuments(() -> List.copyOf(documents), () -> List.copyOf(documents));
        commands = commandsOver(dispatcher);
        onReload = () -> registrar.onCommands(commands);
    }

    private static OpenDeclaration command(String id, List<String> names, OpenArg... args) {
        var spec = new OpenSpec(names, List.of(args), null, null, List.of());
        NamespacedId owner = NamespacedId.of("pack", id);
        return new OpenDeclaration(OpenDeclaration.Kind.COMMAND, owner,
                OpenPlan.compile(owner, spec, null, "command.run", "", null));
    }

    private static OpenArg arg(String name, String type) {
        return new OpenArg(name, type, false, null, List.of());
    }

    @Test
    void aDocumentsNamesReachTheTreeWithTheirTypedArguments() {
        documents.add(command("pay", List.of("pay", "give-coins"), arg("to", "player"), arg("amount", "number")));

        registrar.onCommands(commands);

        CommandNode<CommandSourceStack> pay = dispatcher.getRoot().getChild("pay");
        assertThat(pay).isNotNull();
        var to = (ArgumentCommandNode<?, ?>) pay.getChild("to");
        var amount = (ArgumentCommandNode<?, ?>) to.getChild("amount");
        assertThat(to.getType().getClass().getSimpleName()).isEqualTo("StringArgumentType");
        assertThat(amount.getType().getClass().getSimpleName()).isEqualTo("DoubleArgumentType");
        assertThat(dispatcher.getRoot().getChild("give-coins")).isNotNull();
        assertThat(handler.bindings()).containsOnlyKeys("pay", "give-coins");
        assertThat(handler.bindings().get("pay"))
                .isEqualTo(new OpenCommandHandler.Binding(OpenDeclaration.Kind.COMMAND, NamespacedId.of("pack", "pay")));
        assertThat(diagnostics).isEmpty();
    }

    @Test
    void aGuiAliasAndADocumentNameLiveInOneTreeAndEachRunsItsOwn() {
        f.gui(SHOP);
        documents.add(command("pay", List.of("pay")));

        registrar.onCommands(commands);

        assertThat(handler.bindings()).containsOnlyKeys("shop", "store", "pay");
        assertThat(handler.bindings().get("shop").kind()).isEqualTo(OpenDeclaration.Kind.GUI);
        assertThat(handler.bindings().get("pay").kind()).isEqualTo(OpenDeclaration.Kind.COMMAND);
        assertThat(handler.gui("shop")).isPresent();
        assertThat(handler.gui("pay")).isEmpty();
        assertThat(diagnostics).isEmpty();
    }

    @Test
    void aDocumentThatWantsAGuisAliasIsTheOneRefusedAndTheGuiKeepsItsName() {
        f.gui(SHOP);
        documents.add(command("shopper", List.of("shop", "buy")));

        registrar.onCommands(commands);

        assertThat(handler.bindings().get("shop").kind()).isEqualTo(OpenDeclaration.Kind.GUI);
        assertThat(handler.bindings().get("buy").kind()).isEqualTo(OpenDeclaration.Kind.COMMAND);
        assertThat(diagnostics).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(DiagnosticCodes.OPEN_COMMAND_CONFLICT);
            assertThat(diagnostic.message()).startsWith("command 'shop' of pack:shopper is already the open command of ")
                    .endsWith("the alias is skipped");
            assertThat(diagnostic.resource()).isEqualTo(NamespacedId.of("pack", "shopper"));
        });
    }

    @Test
    void theSecondDocumentToClaimANameIsTheOneRefusedInOrder() {
        documents.add(command("first", List.of("pay")));
        documents.add(command("second", List.of("pay", "other")));

        registrar.onCommands(commands);

        assertThat(handler.bindings().get("pay").id()).isEqualTo(NamespacedId.of("pack", "first"));
        assertThat(handler.bindings().get("other").id()).isEqualTo(NamespacedId.of("pack", "second"));
        assertThat(diagnostics).singleElement().satisfies(diagnostic ->
                assertThat(diagnostic.message()).startsWith("command 'pay' of pack:second is already the command of pack:first"));
    }

    @Test
    void aDocumentWhoseEveryNameIsRefusedClaimsNothingAndNothingElseIsHarmed() {
        dispatcher.getRoot().addChild(LiteralArgumentBuilder.<CommandSourceStack>literal("give").build());
        documents.add(command("gift", List.of("give")));

        registrar.onCommands(commands);

        assertThat(handler.bindings()).isEmpty();
        assertThat(diagnostics).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(DiagnosticCodes.OPEN_COMMAND_CONFLICT);
            assertThat(diagnostic.message()).contains("command 'give' of pack:gift is already a command");
        });
    }

    @Test
    void aSwapThatAddsADocumentAsksForOneReloadAndUnchangedContentAsksForNone() {
        registrar.onCommands(commands);
        documents.add(command("pay", List.of("pay")));

        registrar.resync(f.view());
        assertThat(reloads).as("a new name is not in the tree: one data reload registers it").isEqualTo(1);
        assertThat(dispatcher.getRoot().getChild("pay")).isNotNull();

        registrar.resync(f.view());
        registrar.resync(f.view());
        assertThat(reloads).isEqualTo(1);
    }

    @Test
    void theBootRegistersDocumentsFromTheBuiltViewWhileTheLiveOneIsStillEmpty() {
        var live = new ArrayList<OpenDeclaration>();
        var built = new ArrayList<OpenDeclaration>(List.of(command("pay", List.of("pay"))));
        registrar.commandDocuments(() -> List.copyOf(live), () -> List.copyOf(built));

        registrar.onCommands(commands);
        live.addAll(built); // the first apply publishes what the tree already holds
        registrar.resync(f.view());

        assertThat(dispatcher.getRoot().getChild("pay")).isNotNull();
        assertThat(reloads).as("unchanged content at boot causes no reload").isZero();
    }

    @Test
    void aRemovedDocumentIsHiddenButStaysInTheTreeAndComesBackWithoutANewNode() {
        documents.add(command("pay", List.of("pay")));
        registrar.onCommands(commands);
        CommandNode<CommandSourceStack> node = dispatcher.getRoot().getChild("pay");

        documents.clear();
        registrar.resync(f.view());
        assertThat(handler.declared("pay")).isFalse();
        assertThat(node.canUse(SOURCE)).isFalse();
        assertThat(reloads).isZero();

        documents.add(command("pay", List.of("pay")));
        registrar.resync(f.view());
        assertThat(handler.declared("pay")).isTrue();
        assertThat(node.canUse(SOURCE)).isTrue();
        assertThat(dispatcher.getRoot().getChild("pay")).isSameAs(node);
        assertThat(reloads).isZero();
    }

    @Test
    void aChangedArgumentShapeOfADocumentRebuildsItsNodeThroughOneReload() {
        documents.add(command("pay", List.of("pay"), arg("amount", "int")));
        registrar.onCommands(commands);

        documents.clear();
        documents.add(command("pay", List.of("pay"), arg("amount", "number")));
        registrar.resync(f.view());

        // a real server's reload rebuilds the dispatcher; the fake's merges a same-name child, so only the ask is checked
        assertThat(reloads).isEqualTo(1);
        assertThat(diagnostics).isEmpty();
    }

    @Test
    void theSuggestionsOfADocumentsArgumentsComeFromItsPlan() throws Exception {
        var choice = new OpenArg("mode", "choice", false, null, List.of("soft", "loud"));
        var declaration = command("tune", List.of("tune"), choice, arg("who", "player"), arg("flag", "boolean"));
        documents.add(declaration);
        handler.documents(new DocumentCommands() {
            @Override
            public java.util.Optional<OpenPlan> plan(NamespacedId command) {
                return java.util.Optional.of(declaration.plan());
            }

            @Override
            public void run(NamespacedId command, String alias, org.bukkit.command.CommandSender sender,
                    java.util.Map<String, String> raw) {
                throw new AssertionError("a suggestion runs nothing");
            }
        });
        var players = new PlayerNames() {
            @Override
            public java.util.Optional<String> online(String name) {
                return names().stream().filter(name::equalsIgnoreCase).findFirst();
            }

            @Override
            public java.util.Collection<String> names() {
                return List.of("Alice", "Bob");
            }
        };
        var withPlayers = new OpenCommandHandler(plugin(), null, f::view, null, players);
        withPlayers.documents(new DocumentCommands() {
            @Override
            public java.util.Optional<OpenPlan> plan(NamespacedId command) {
                return java.util.Optional.of(declaration.plan());
            }

            @Override
            public void run(NamespacedId command, String alias, org.bukkit.command.CommandSender sender,
                    java.util.Map<String, String> raw) {
                throw new AssertionError("a suggestion runs nothing");
            }
        });
        withPlayers.rebind(java.util.Map.of("tune",
                new OpenCommandHandler.Binding(OpenDeclaration.Kind.COMMAND, declaration.id())));

        assertThat(complete(withPlayers, "mode", "")).containsExactlyInAnyOrder("soft", "loud");
        assertThat(complete(withPlayers, "mode", "lo")).containsExactly("loud");
        assertThat(complete(withPlayers, "who", "a")).containsExactly("Alice");
        assertThat(complete(withPlayers, "flag", "")).containsExactlyInAnyOrder("true", "false");
    }

    private static List<String> complete(OpenCommandHandler handler, String argument, String typed) throws Exception {
        var builder = new com.mojang.brigadier.suggestion.SuggestionsBuilder("tune " + typed, "tune ".length());
        var suggestions = handler.suggestions("tune", argument).getSuggestions(null, builder).get();
        return suggestions.getList().stream().map(com.mojang.brigadier.suggestion.Suggestion::getText).toList();
    }

    private static SchedulingGateway scheduling() {
        return (SchedulingGateway) Proxy.newProxyInstance(SchedulingGateway.class.getClassLoader(),
                new Class<?>[] {SchedulingGateway.class}, (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Commands commandsOver(CommandDispatcher<CommandSourceStack> dispatcher) {
        return (Commands) Proxy.newProxyInstance(Commands.class.getClassLoader(), new Class<?>[] {Commands.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getDispatcher" -> dispatcher;
                    case "register" -> {
                        @SuppressWarnings("unchecked")
                        var node = (com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack>) args[0];
                        dispatcher.getRoot().addChild(node);
                        yield Set.of(node.getLiteral());
                    }
                    case "hashCode" -> 0;
                    case "equals" -> proxy == args[0];
                    case "toString" -> "test-commands";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A plugin whose server has an empty command map and no players: what the registrar reads. */
    private Plugin plugin() {
        CommandMap commandMap = (CommandMap) Proxy.newProxyInstance(CommandMap.class.getClassLoader(),
                new Class<?>[] {CommandMap.class}, (proxy, method, args) -> null);
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getCommandMap" -> commandMap;
                    case "getOnlinePlayers" -> List.of();
                    case "reloadData" -> {
                        reloads++;
                        onReload.run();
                        yield null;
                    }
                    default -> null;
                });
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getServer" -> server;
                    case "getLogger" -> Logger.getAnonymousLogger();
                    default -> null;
                });
    }
}
