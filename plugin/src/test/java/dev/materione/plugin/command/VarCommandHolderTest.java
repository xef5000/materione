package dev.materione.plugin.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableType;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

class VarCommandHolderTest {

    private final List<String> sent = new ArrayList<>();

    private final CommandSender sender = (CommandSender) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {CommandSender.class}, (proxy, method, args) -> {
                if (method.getName().equals("sendMessage") && args != null) {
                    for (Object arg : args) {
                        if (arg instanceof Component component) {
                            sent.add(PlainTextComponentSerializer.plainText().serialize(component));
                        }
                    }
                }
                return null;
            });

    private static VariableDeclaration declaration(VariableScope scope) {
        return new VariableDeclaration(NamespacedId.of("overlay", "tab"), scope, VariableType.STRING, "", "");
    }

    @Test
    void aSessionVariableIsRefusedAsSessionWhateverHolderIsGiven() {
        VarCommand command = new VarCommand(null);
        VariableDeclaration session = declaration(VariableScope.SESSION);

        assertThat(command.target(sender, "-", session)).isNotNull();
        assertThat(sent).as("the holder is not judged").isEmpty();

        assertThat(VarCommand.sessionRefused(sender, session)).isTrue();
        assertThat(sent).singleElement().asString().contains("is a session variable");
    }

    @Test
    void aPlayerVariableStillNeedsAPlayer() {
        assertThat(new VarCommand(null).target(sender, "-", declaration(VariableScope.PLAYER))).isNull();
        assertThat(sent).singleElement().asString().contains("is a player variable: name a player");
    }
}
