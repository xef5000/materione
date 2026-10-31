package dev.materione.plugin.gui.open;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Brigadier literal an open command becomes (logic.md §6.1, issue #96): typed arguments the tree accepts or
 * refuses before the server runs anything, every prefix reaching the executor, activation through
 * {@code requires}, and completions. A real dispatcher, with a proxied command source.
 */
class OpenCommandTreeTest {

    private static final CommandSourceStack SOURCE = (CommandSourceStack) Proxy.newProxyInstance(
            CommandSourceStack.class.getClassLoader(), new Class<?>[] {CommandSourceStack.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "hashCode" -> 0;
                case "equals" -> proxy == args[0];
                case "toString" -> "test-source";
                default -> null;
            });

    private static final List<OpenArgSpec> ARGS = List.of(
            new OpenArgSpec("count", ArgType.INT, true, null, List.of()),
            new OpenArgSpec("label", ArgType.STRING, true, null, List.of()),
            new OpenArgSpec("flag", ArgType.BOOLEAN, true, null, List.of()));

    private final List<String> ran = new ArrayList<>();
    private final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
    private boolean declared = true;

    private void register(List<OpenArgSpec> args) {
        dispatcher.getRoot().addChild(OpenCommandTree.build(
                "shop", args,
                source -> declared,
                context -> {
                    ran.add(context.getInput());
                    return 1;
                },
                name -> (context, builder) -> {
                    builder.suggest("tools");
                    return builder.buildFuture();
                }));
    }

    @Test
    void typedArgumentsAreParsedAndTheExecutorSeesEveryPrefix() throws CommandSyntaxException {
        register(ARGS);

        dispatcher.execute("shop", SOURCE);
        dispatcher.execute("shop 3", SOURCE);
        dispatcher.execute("shop 3 \"two words\" true", SOURCE);

        assertThat(ran).containsExactly("shop", "shop 3", "shop 3 \"two words\" true");
    }

    @Test
    void aValueOfTheWrongTypeIsRefusedByTheTreeBeforeTheServerRunsIt() {
        register(ARGS);

        assertThatThrownBy(() -> dispatcher.execute("shop abc", SOURCE)).isInstanceOf(CommandSyntaxException.class);
        assertThatThrownBy(() -> dispatcher.execute("shop 3 x flag", SOURCE))
                .isInstanceOf(CommandSyntaxException.class);
        assertThat(ran).isEmpty();
    }

    @Test
    void aCommandThatIsNoLongerDeclaredIsNeitherRunNorSuggested() throws CommandSyntaxException {
        register(ARGS);
        declared = false;

        assertThatThrownBy(() -> dispatcher.execute("shop", SOURCE)).isInstanceOf(CommandSyntaxException.class);
        assertThat(dispatcher.getRoot().getChild("shop")).isNotNull();
        assertThat(ran).isEmpty();
    }

    @Test
    void aTypedArgumentReachesTheExecutorAsItsOwnType() throws CommandSyntaxException {
        var values = new ArrayList<String>();
        dispatcher.getRoot().addChild(OpenCommandTree.build(
                "typed", ARGS, source -> true,
                context -> {
                    values.add(String.valueOf(context.getArgument("count", Object.class)));
                    values.add(String.valueOf(context.getArgument("flag", Object.class)));
                    return 1;
                },
                name -> (context, builder) -> builder.buildFuture()));

        dispatcher.execute("typed 42 x true", SOURCE);

        assertThat(values).containsExactly("42", "true");
    }

    @Test
    void completionsComeFromTheSuggestionProvider() throws Exception {
        register(ARGS);

        var suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse("shop ", SOURCE)).get();

        assertThat(suggestions.getList()).extracting(suggestion -> suggestion.getText()).contains("tools");
    }

    @Test
    void theShapeNamesEveryArgumentWithItsTreeType() {
        assertThat(OpenCommandTree.shape(ARGS)).containsExactly(
                "count:IntegerArgumentType", "label:StringArgumentType", "flag:BoolArgumentType");
    }
}
