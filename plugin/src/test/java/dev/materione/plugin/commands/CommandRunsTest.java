package dev.materione.plugin.commands;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.logic.Facets;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.content.commands.CommandDefinition;
import dev.materione.content.commands.CommandType;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.plugin.gui.GuiFixture;
import dev.materione.plugin.gui.open.PlayerNames;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * What a command document does when its name is typed (logic.md §40): the gate in the order the design fixes
 * (permission, console, arguments, requirement), the typed {@code args.*} and {@code command.*} the flow reads, the deny
 * steps and the bypass, a flow that {@code call}s a flow, and the console. The flow is the real engine over the real
 * compiler; the sender is a stand-in whose messages are recorded, so each refusal is read from what the sender was told
 * and from the stable outcome line.
 */
class CommandRunsTest {

    private static final NamespacedId PAY = NamespacedId.of("shop", "pay");

    private GuiFixture f;
    private List<Diagnostic> diagnostics;
    private List<String> outcomes;
    private List<String> consoleMessages;
    private CommandSender console;
    private CommandRuns runs;
    private final AtomicReference<RuntimeCommands> view = new AtomicReference<>(RuntimeCommands.empty());

    @BeforeEach
    void setUp() {
        f = new GuiFixture("shop", dev.materione.plugin.logic.support.ItemCatalog.none());
        diagnostics = new ArrayList<>();
        outcomes = new ArrayList<>();
        consoleMessages = new ArrayList<>();
        console = (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "CONSOLE";
                    case "hasPermission" -> true;
                    case "sendMessage" -> {
                        consoleMessages.add(String.valueOf(args[0]));
                        yield null;
                    }
                    case "hashCode" -> 7;
                    case "equals" -> proxy == args[0];
                    case "toString" -> "console";
                    default -> throw new UnsupportedOperationException("console." + method.getName());
                });
        Logger log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                outcomes.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        var names = new PlayerNames() {
            @Override
            public Optional<String> online(String name) {
                return names().stream().filter(name::equalsIgnoreCase).findFirst();
            }

            @Override
            public Collection<String> names() {
                return List.of("Alice", "Bob");
            }
        };
        var contexts = new CommandRuns.Contexts() {
            @Override
            public Map<String, Object> baseRoot(Player player) {
                var root = new LinkedHashMap<String, Object>();
                root.put("player", Map.of("name", player.getName(), "uuid", player.getUniqueId().toString()));
                root.put("vars", new LinkedHashMap<String, Object>());
                return root;
            }

            @Override
            public MapLogicContext context(Map<String, Object> root, Player player) {
                return new MapLogicContext(root).with(Facets.PLAYER, player);
            }
        };
        runs = new CommandRuns(log,
                () -> RuntimeRegistries.builder().view(RuntimeCommands.KEY, view.get()).build(),
                contexts, f.engine, names);
    }

    @SuppressWarnings("unchecked")
    private void declare(String yaml) {
        Map<String, Map<String, Object>> document = (Map<String, Map<String, Object>>)
                new Yaml().load(yaml);
        var live = new ArrayList<RuntimeCommands.Live>();
        for (var entry : document.entrySet()) {
            var parsed = new CommandType().parse(NamespacedId.of("shop", entry.getKey()), entry.getValue(), Map.of());
            CommandDocuments.compile((CommandDefinition) parsed.orElseThrow(), f.logic.lookup(), diagnostics::add)
                    .ifPresent(live::add);
        }
        view.set(new RuntimeCommands(live));
    }

    private static final String PAY_YAML = """
            pay:
              names: [pay, give-coins]
              permission: shop.pay
              args:
                - {name: to, type: player}
                - {name: amount, type: number}
              requirement: "args.amount > 0"
              deny:
                - mark: "denied ${args.amount}"
              do:
                - mark: "${player.name} pays ${args.to} ${args.amount} doubled ${args.amount * 2} as ${command.label}/${command.name}"
                - call: thanks
              flows:
                thanks:
                  - mark: "thanks ${args.to}"
            """;

    private void run(String alias, CommandSender sender, Map<String, String> raw) {
        runs.run(PAY, alias, sender, raw);
    }

    @Test
    void typedArgumentsAndTheCommandReachTheFlowAndACalledFlowReadsThemToo() {
        declare(PAY_YAML);
        f.permissions.add("shop.pay");

        run("give-coins", f.player, Map.of("to", "bob", "amount", "5"));

        assertThat(diagnostics).isEmpty();
        // a number is a number (doubled is 10, not "55"), the player is the online player's own spelling, the alias
        // is the label and the first name is the name; the called flow, passed nothing, sees the same args
        assertThat(f.ran).containsExactly("mark:Probe pays Bob 5 doubled 10 as give-coins/pay", "mark:thanks Bob");
        assertThat(outcomes).containsExactly("MATERIONE-COMMAND alias=give-coins command=shop:pay outcome=ran");
    }

    @Test
    void thePermissionIsCheckedFirstEvenBeforeTheArgumentsAreRead() {
        declare(PAY_YAML);

        run("pay", f.player, Map.of("amount", "not a number"));

        assertThat(f.messages).singleElement().asString().contains("no permission (shop.pay)");
        assertThat(f.ran).isEmpty();
        assertThat(outcomes).containsExactly("MATERIONE-COMMAND alias=pay command=shop:pay outcome=denied");
    }

    @Test
    void aBadArgumentIsRefusedWithTheUsageLineGeneratedFromTheDeclaration() {
        declare(PAY_YAML);
        f.permissions.add("shop.pay");

        run("pay", f.player, Map.of("to", "Bob", "amount", "lots"));
        run("pay", f.player, Map.of("to", "Nobody", "amount", "5"));
        run("pay", f.player, Map.of("to", "Bob"));

        assertThat(f.messages).hasSize(3);
        assertThat(f.messages.get(0)).contains("amount: 'lots' is not a number")
                .contains("usage: /pay <to:player> <amount:number>");
        assertThat(f.messages.get(1)).contains("to: no online player named 'Nobody'");
        assertThat(f.messages.get(2)).contains("missing amount");
        assertThat(f.ran).isEmpty();
        assertThat(outcomes).containsOnly("MATERIONE-COMMAND alias=pay command=shop:pay outcome=usage");
    }

    @Test
    void aFailedRequirementRunsTheDenyStepsAndNotTheFlow() {
        declare(PAY_YAML);
        f.permissions.add("shop.pay");

        run("pay", f.player, Map.of("to", "Bob", "amount", "-3"));

        assertThat(f.ran).containsExactly("mark:denied -3");
        assertThat(f.messages).isEmpty(); // the author's own steps are the answer when there are some
        assertThat(outcomes).containsExactly("MATERIONE-COMMAND alias=pay command=shop:pay outcome=refused");
    }

    @Test
    void withNoDenyStepsTheSenderIsToldTheRequirementIsNotMet() {
        declare("""
                pay:
                  names: [pay]
                  args: [{name: amount, type: int}]
                  requirement: "args.amount > 0"
                  do: [ {mark: ran} ]
                """);

        run("pay", f.player, Map.of("amount", "0"));

        assertThat(f.messages).singleElement().asString().contains("the requirement for /pay is not met");
        assertThat(f.ran).isEmpty();
    }

    @Test
    void theBypassNodeSkipsTheRequirementAndNothingElse() {
        declare(PAY_YAML);
        f.permissions.add("shop.pay");
        f.permissions.add("materione.command.bypass.shop.pay");

        run("pay", f.player, Map.of("to", "Bob", "amount", "-3"));
        run("pay", f.player, Map.of("to", "Bob", "amount", "lots"));

        assertThat(f.ran).first().asString().startsWith("mark:Probe pays Bob -3");
        assertThat(f.messages).singleElement().asString().contains("amount: 'lots' is not a number");
    }

    @Test
    void aRequirementThatDoesNotLoadFailsClosedForEveryoneWithoutTheBypass() {
        declare("""
                pay:
                  names: [pay]
                  requirement: "this is ((( not an expression"
                  do: [ {mark: ran} ]
                """);

        run("pay", f.player, Map.of());

        assertThat(diagnostics).extracting(d -> d.code().code()).contains("MTR-0068");
        assertThat(f.ran).isEmpty();
        f.permissions.add("materione.command.bypass.shop.pay");
        run("pay", f.player, Map.of());
        assertThat(f.ran).containsExactly("mark:ran");
    }

    @Test
    void theConsoleIsRefusedUnlessTheDocumentSaysConsoleTrue() {
        declare(PAY_YAML);

        run("pay", console, Map.of("to", "Bob", "amount", "5"));

        assertThat(consoleMessages).singleElement().asString().contains("only a player can run /pay");
        assertThat(f.ran).isEmpty();
        assertThat(outcomes).containsExactly("MATERIONE-COMMAND alias=pay command=shop:pay outcome=console");
    }

    @Test
    void aConsoleFormRunsWithNoPlayerAndTheConsolesName() {
        declare("""
                pay:
                  names: [pay]
                  console: true
                  args: [{name: n, type: int}]
                  do: [ {mark: "${player.name} ran ${args.n + 1} ${command.label}"} ]
                """);

        run("pay", console, Map.of("n", "4"));

        assertThat(f.ran).containsExactly("mark:CONSOLE ran 5 pay");
        assertThat(outcomes).containsExactly("MATERIONE-COMMAND alias=pay command=shop:pay outcome=ran");
    }

    @Test
    void anOptionalArgumentLeftOutIsAbsentAndOneWithADefaultCarriesIt() {
        declare("""
                pay:
                  names: [pay]
                  args:
                    - {name: loud, type: boolean, optional: true}
                    - {name: times, type: int, default: "2"}
                    - {name: mode, type: choice, default: soft, suggest: [soft, loud]}
                  do: [ {mark: "[${args.loud}] ${args.times * 3} ${args.mode}"} ]
                """);

        run("pay", f.player, Map.of());
        run("pay", f.player, Map.of("loud", "TRUE", "times", "5", "mode", "loud"));

        assertThat(f.ran).containsExactly("mark:[] 6 soft", "mark:[true] 15 loud");
    }

    @Test
    void aCommandAReloadRemovedBetweenTheTreeAndTheRunIsAQuietNoOp() {
        declare(PAY_YAML);
        view.set(RuntimeCommands.empty());

        run("pay", f.player, Map.of("to", "Bob", "amount", "5"));

        assertThat(f.ran).isEmpty();
        assertThat(f.messages).isEmpty();
        assertThat(outcomes).containsExactly("MATERIONE-COMMAND alias=pay command=shop:pay outcome=gone");
        assertThat(runs.plan(PAY)).isEmpty();
    }

    @Test
    void aStepThatFailsWhenItRunsNeverReachesTheTreesExecutor() {
        declare("""
                pay:
                  names: [pay]
                  do:
                    - fail: x
                    - mark: after
                """);

        run("pay", f.player, Map.of());

        assertThat(outcomes).containsExactly("MATERIONE-COMMAND alias=pay command=shop:pay outcome=ran");
    }
}
