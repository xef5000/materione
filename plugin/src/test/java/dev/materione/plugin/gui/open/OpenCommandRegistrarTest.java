package dev.materione.plugin.gui.open;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
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
 * The open-command registrar (logic.md §6.1): what the server's COMMANDS event registers, which names are
 * conflicts, and how a content swap adds, hides and re-shapes aliases. The server's command registrar is a
 * proxy over a real Brigadier dispatcher, so the tree and its {@code requires} checks are the real ones.
 */
class OpenCommandRegistrarTest {

    private static final CommandSourceStack SOURCE = (CommandSourceStack) Proxy.newProxyInstance(
            CommandSourceStack.class.getClassLoader(), new Class<?>[] {CommandSourceStack.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "hashCode" -> 0;
                case "equals" -> proxy == args[0];
                case "toString" -> "test-source";
                default -> null;
            });

    private GuiFixture f;
    private CommandDispatcher<CommandSourceStack> dispatcher;
    private List<Diagnostic> diagnostics;
    private OpenCommandHandler handler;
    private OpenCommandRegistrar registrar;
    private Commands commands;
    /** What the fake server's {@code reloadData} does: Paper fires the COMMANDS event again, so this re-registers. */
    private Runnable onReload;
    private int reloads;

    @BeforeEach
    void setUp() {
        f = new GuiFixture();
        dispatcher = new CommandDispatcher<>();
        diagnostics = new ArrayList<>();
        handler = new OpenCommandHandler(plugin(), null, f::view, null, PlayerNames.NONE);
        registrar = new OpenCommandRegistrar(plugin(), handler, f::view, diagnostics::add);
        commands = commandsOver(dispatcher);
        onReload = () -> registrar.onCommands(commands);
    }

    private static final String SHOP = """
            shop:
              title: Shop
              rows: 1
              layers: []
              open:
                commands: [shop, store]
                args:
                  - { name: category, type: choice, default: all, suggest: [all, tools] }
            """;

    @Test
    void theCommandsEventRegistersEveryDeclaredAlias() {
        f.gui(SHOP);

        registrar.onCommands(commands);

        assertThat(dispatcher.getRoot().getChild("shop")).isNotNull();
        assertThat(dispatcher.getRoot().getChild("store")).isNotNull();
        assertThat(handler.bindings()).containsOnlyKeys("shop", "store");
        assertThat(diagnostics).isEmpty();
    }

    @Test
    void aNameThatIsAlreadyACommandIsSkippedWithAWarningAndTheRestStillRegister() {
        dispatcher.getRoot().addChild(LiteralArgumentBuilder.<CommandSourceStack>literal("give").build());
        f.gui("""
                shop:
                  title: Shop
                  rows: 1
                  layers: []
                  open:
                    commands: [give, store]
                """);

        registrar.onCommands(commands);

        assertThat(handler.bindings()).containsOnlyKeys("store");
        assertThat(diagnostics).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(DiagnosticCodes.OPEN_COMMAND_CONFLICT);
            assertThat(diagnostic.message()).contains("'give'").contains("is already a command");
        });
    }

    @Test
    void aNewAliasIsAddedToTheKeptTreeAfterAContentSwap() {
        f.gui(SHOP);
        registrar.onCommands(commands);

        f.gui("""
                menu:
                  title: Menu
                  rows: 1
                  layers: []
                  open:
                    commands: [menu]
                """);
        registrar.resync(f.view());

        // the new alias reached the tree through the reload the swap asked for, not through the kept registrar
        assertThat(reloads).isEqualTo(1);
        assertThat(dispatcher.getRoot().getChild("menu")).isNotNull();
        assertThat(handler.declared("menu")).isTrue();
        assertThat(diagnostics).isEmpty();
    }

    @Test
    void aSwapNeverTouchesTheTreeItself_andAnAliasTheReloadDoesNotAddIsReportedNotThrown() {
        f.gui(SHOP);
        registrar.onCommands(commands);
        onReload = () -> { };

        f.gui("""
                menu:
                  title: Menu
                  rows: 1
                  layers: []
                  open:
                    commands: [menu]
                """);
        registrar.resync(f.view());

        assertThat(handler.declared("menu")).isTrue();
        assertThat(dispatcher.getRoot().getChild("menu")).isNull();
        assertThat(diagnostics).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(DiagnosticCodes.OPEN_TREE_RESYNC);
            assertThat(diagnostic.message()).contains("'menu'").contains("next restart");
        });
    }

    @Test
    void aRejectedAliasDoesNotAskForAReloadOnEverySwapUntilTheNextCommandsEvent() {
        dispatcher.getRoot().addChild(LiteralArgumentBuilder.<CommandSourceStack>literal("give").build());
        f.gui(SHOP);
        f.gui("""
                other:
                  title: Other
                  rows: 1
                  layers: []
                  open:
                    commands: [give]
                """);
        registrar.onCommands(commands);
        int before = reloads;

        registrar.resync(f.view());
        registrar.resync(f.view());

        assertThat(reloads).isEqualTo(before);
    }

    @Test
    void aRemovedAliasIsHiddenButStaysInTheTreeAfterAContentSwap() {
        f.gui(SHOP);
        registrar.onCommands(commands);

        f.remove("shop");
        registrar.resync(f.view());

        assertThat(handler.declared("shop")).isFalse();
        assertThat(handler.declared("store")).isFalse();
        assertThat(dispatcher.getRoot().getChild("shop").canUse(SOURCE)).isFalse();
        assertThat(diagnostics).isEmpty();
    }

    @Test
    void anAliasThatComesBackIsDeclaredAgainWithoutANewNode() {
        f.gui(SHOP);
        registrar.onCommands(commands);
        LiteralCommandNode<CommandSourceStack> before = (LiteralCommandNode<CommandSourceStack>)
                dispatcher.getRoot().getChild("shop");
        f.remove("shop");
        registrar.resync(f.view());

        f.gui(SHOP);
        registrar.resync(f.view());

        assertThat(handler.declared("shop")).isTrue();
        assertThat(dispatcher.getRoot().getChild("shop")).isSameAs(before);
        assertThat(diagnostics).isEmpty();
    }

    @Test
    void aChangedArgumentShapeIsReportedAsNeedingARestart() {
        f.gui(SHOP);
        registrar.onCommands(commands);

        f.gui("""
                shop:
                  title: Shop
                  rows: 1
                  layers: []
                  open:
                    commands: [shop, store]
                    args:
                      - { name: amount, type: int, optional: true }
                """);
        registrar.resync(f.view());

        // both aliases of the GUI were built with the old shape, and the tree keeps it until a restart
        assertThat(diagnostics).hasSize(2).allSatisfy(diagnostic ->
                assertThat(diagnostic.code()).isEqualTo(DiagnosticCodes.OPEN_TREE_RESYNC));
    }

    @Test
    void aResyncBeforeTheCommandsEventDeclaresButRegistersNothingUntilIt() {
        f.gui(SHOP);

        registrar.resync(f.view());
        assertThat(handler.declared("shop")).isTrue();
        assertThat(dispatcher.getRoot().getChild("shop")).isNull();

        registrar.onCommands(commands);
        assertThat(dispatcher.getRoot().getChild("shop")).isNotNull();
    }

    @Test
    void aGuiClaimingAnAliasAnotherGuiAlreadyClaimsIsAConflict() {
        f.gui(SHOP);
        f.gui("""
                other:
                  title: Other
                  rows: 1
                  layers: []
                  open:
                    commands: [shop]
                """);

        registrar.onCommands(commands);

        assertThat(diagnostics).singleElement().satisfies(diagnostic ->
                assertThat(diagnostic.code()).isEqualTo(DiagnosticCodes.OPEN_COMMAND_CONFLICT));
        assertThat(handler.bindings().keySet()).contains("shop", "store");
    }

    /** A {@code Commands} registrar over {@code dispatcher}: a {@code register} adds the literal to the tree. */
    private static Commands commandsOver(CommandDispatcher<CommandSourceStack> dispatcher) {
        return (Commands) Proxy.newProxyInstance(Commands.class.getClassLoader(), new Class<?>[] {Commands.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getDispatcher" -> dispatcher;
                    case "register" -> {
                        @SuppressWarnings("unchecked")
                        LiteralCommandNode<CommandSourceStack> node = (LiteralCommandNode<CommandSourceStack>) args[0];
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
