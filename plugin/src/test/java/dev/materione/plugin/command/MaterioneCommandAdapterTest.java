package dev.materione.plugin.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

class MaterioneCommandAdapterTest {

    private final CommandSender sender = (CommandSender) Proxy.newProxyInstance(
            CommandSender.class.getClassLoader(), new Class<?>[] {CommandSender.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> "test";
                default -> throw new UnsupportedOperationException(method.getName());
            });

    private CommandSourceStack source() {
        return (CommandSourceStack) Proxy.newProxyInstance(CommandSourceStack.class.getClassLoader(),
                new Class<?>[] {CommandSourceStack.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getSender" -> sender;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void lifecycleExecutionPreservesSenderAndAllNamespacedAndFreeTextArguments() {
        List<List<String>> calls = new ArrayList<>();
        var adapter = new MaterioneCommandAdapter((actual, command, label, args) -> {
            assertThat(actual).isSameAs(sender);
            assertThat(command.getName()).isEqualTo("materione");
            assertThat(label).isEqualTo("materione");
            calls.add(Arrays.asList(args));
            return true;
        }, (actual, command, alias, args) -> List.of());
        adapter.execute(source(), new String[] {"dev", "effect", "sample:wave", "two", "words"});
        adapter.execute(source(), new String[] {"give", "corpus:gem_red", "Probe", "5"});
        adapter.execute(source(), new String[0]);

        assertThat(calls).containsExactly(
                List.of("dev", "effect", "sample:wave", "two", "words"),
                List.of("give", "corpus:gem_red", "Probe", "5"),
                List.of());
    }

    @Test
    void lifecycleSuggestionsUseTheExistingCompleterIncludingTheTrailingEmptyArgument() {
        var adapter = new MaterioneCommandAdapter((actual, command, label, args) -> true,
                (actual, command, alias, args) -> {
                    assertThat(actual).isSameAs(sender);
                    assertThat(alias).isEqualTo("materione");
                    assertThat(args).containsExactly("gui", "open", "");
                    return List.of("sample:shop");
                });

        assertThat(adapter.suggest(source(), new String[] {"gui", "open", ""}))
                .containsExactly("sample:shop");
        assertThat(adapter.canUse(sender)).isTrue();
    }
}
