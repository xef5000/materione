package dev.materione.plugin.logic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.logic.provider.EconomyProvider;
import dev.materione.api.logic.provider.EconomyResult;
import dev.materione.api.logic.provider.PermissionProvider;
import dev.materione.api.logic.provider.ProviderResult;
import dev.materione.api.logic.provider.ServerTransfer;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The provider slots (logic.md §4.5): a registration replaces, closing restores what it replaced,
 * a stale handle never clears a newer registration, and an owner that disables takes its
 * providers with it. The permission slot always answers, falling back to the session default.
 */
class LogicProvidersTest {

    private static final PermissionProvider DEFAULT = new PermissionProvider() {
        @Override
        public String name() {
            return "default";
        }

        @Override
        public ProviderResult grant(UUID player, String node) {
            return ProviderResult.OK;
        }

        @Override
        public ProviderResult revoke(UUID player, String node) {
            return ProviderResult.OK;
        }
    };

    private static EconomyProvider economy(String name) {
        return new EconomyProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public EconomyResult balance(UUID player) {
                return new EconomyResult.Succeeded(0);
            }

            @Override
            public EconomyResult has(UUID player, double amount) {
                return new EconomyResult.Succeeded(0);
            }

            @Override
            public EconomyResult withdraw(UUID player, double amount) {
                return new EconomyResult.Succeeded(0);
            }

            @Override
            public EconomyResult deposit(UUID player, double amount) {
                return new EconomyResult.Succeeded(0);
            }
        };
    }

    @Test
    void theSlotsStartEmptyExceptForThePermissionDefault() {
        var providers = new LogicProvidersImpl(DEFAULT);

        assertThat(providers.economy()).isEmpty();
        assertThat(providers.transfer()).isEmpty();
        assertThat(providers.permissions()).isSameAs(DEFAULT);
    }

    @Test
    void aRegistrationReplacesAndClosingRestoresTheSlot() {
        var providers = new LogicProvidersImpl(DEFAULT);
        var owner = LogicTestSupport.owner("vault");

        var handle = providers.setEconomy(owner, economy("first"));
        assertThat(providers.economy()).map(EconomyProvider::name).contains("first");

        handle.close();
        handle.close(); // idempotent
        assertThat(providers.economy()).isEmpty();
    }

    @Test
    void aStaleHandleNeverClearsANewerRegistration() {
        var providers = new LogicProvidersImpl(DEFAULT);
        var owner = LogicTestSupport.owner("vault");

        var first = providers.setEconomy(owner, economy("first"));
        providers.setEconomy(owner, economy("second"));
        first.close();

        assertThat(providers.economy()).map(EconomyProvider::name).contains("second");
    }

    @Test
    void aPermissionRegistrationFallsBackToTheDefaultWhenClosed() {
        var providers = new LogicProvidersImpl(DEFAULT);
        PermissionProvider luckPerms = new PermissionProvider() {
            @Override
            public String name() {
                return "luckperms";
            }

            @Override
            public ProviderResult grant(UUID player, String node) {
                return ProviderResult.OK;
            }

            @Override
            public ProviderResult revoke(UUID player, String node) {
                return ProviderResult.OK;
            }
        };

        var handle = providers.setPermissions(LogicTestSupport.owner("luckperms"), luckPerms);
        assertThat(providers.permissions()).isSameAs(luckPerms);

        handle.close();
        assertThat(providers.permissions()).isSameAs(DEFAULT);
    }

    @Test
    void disablingTheOwnerRemovesOnlyItsProviders() {
        var providers = new LogicProvidersImpl(DEFAULT);
        var vault = LogicTestSupport.owner("vault");
        var other = LogicTestSupport.owner("other");
        providers.setEconomy(vault, economy("vault-economy"));
        ServerTransfer transfer = new ServerTransfer() {
            @Override
            public String name() {
                return "proxy";
            }

            @Override
            public ProviderResult send(UUID player, String server) {
                return ProviderResult.OK;
            }
        };
        providers.setTransfer(other, transfer);

        providers.ownerDisabled(vault);

        assertThat(providers.economy()).isEmpty();
        assertThat(providers.transfer()).contains(transfer);
    }

    @Test
    void aDisabledOwnerCannotRegisterAProvider() {
        var providers = new LogicProvidersImpl(DEFAULT);

        assertThatThrownBy(() -> providers.setEconomy(LogicTestSupport.disabledOwner("gone"), economy("x")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("disabled");
    }
}
