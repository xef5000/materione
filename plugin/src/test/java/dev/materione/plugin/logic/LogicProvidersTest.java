package dev.materione.plugin.logic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.logic.provider.PermissionProvider;
import dev.materione.api.logic.provider.ProviderResult;
import dev.materione.api.logic.provider.ServerTransfer;
import dev.materione.plugin.logic.currency.MaterioneCurrencyCatalog;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The provider slots (logic.md §4.5): a registration replaces, closing restores what it replaced, a
 * stale handle never clears a newer registration, and an owner that disables takes its providers with
 * it. The permission slot always answers, falling back to the session default. Currencies are a catalog
 * rather than a slot, so they are not covered here.
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

    private static PermissionProvider permissions(String name) {
        return new PermissionProvider() {
            @Override
            public String name() {
                return name;
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
    }

    private static LogicProvidersImpl providers() {
        return new LogicProvidersImpl(DEFAULT, new MaterioneCurrencyCatalog(authored -> Optional.empty(), null));
    }

    @Test
    void theSlotsStartEmptyExceptForThePermissionDefaultAndTheCatalogHasTheBuiltInMoney() {
        var providers = providers();

        assertThat(providers.transfer()).isEmpty();
        assertThat(providers.permissions()).isSameAs(DEFAULT);
        assertThat(providers.currencies().all()).extracting(c -> c.id().toString()).containsExactly("materione:money");
    }

    @Test
    void aPermissionRegistrationFallsBackToTheDefaultWhenClosed() {
        var providers = providers();
        var luckPerms = permissions("luckperms");

        var handle = providers.setPermissions(LogicTestSupport.owner("luckperms"), luckPerms);
        assertThat(providers.permissions()).isSameAs(luckPerms);

        handle.close();
        handle.close(); // idempotent
        assertThat(providers.permissions()).isSameAs(DEFAULT);
    }

    @Test
    void aStaleHandleNeverClearsANewerRegistration() {
        var providers = providers();
        var owner = LogicTestSupport.owner("luckperms");
        var first = permissions("first");
        var second = permissions("second");

        var handle = providers.setPermissions(owner, first);
        providers.setPermissions(owner, second);
        handle.close();

        assertThat(providers.permissions()).isSameAs(second);
    }

    @Test
    void theTransferSlotIsEmptyWhenItsRegistrationCloses() {
        var providers = providers();
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

        var handle = providers.setTransfer(LogicTestSupport.owner("proxy"), transfer);
        assertThat(providers.transfer()).contains(transfer);
        handle.close();
        assertThat(providers.transfer()).isEmpty();
    }

    @Test
    void disablingTheOwnerRemovesOnlyItsProviders() {
        var providers = providers();
        var owner = LogicTestSupport.owner("luckperms");
        var other = LogicTestSupport.owner("other");
        providers.setPermissions(owner, permissions("luckperms"));
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

        providers.ownerDisabled(owner);

        assertThat(providers.permissions()).isSameAs(DEFAULT);
        assertThat(providers.transfer()).contains(transfer);
    }

    @Test
    void aDisabledOwnerCannotRegisterAProvider() {
        var providers = providers();

        assertThatThrownBy(() -> providers.setPermissions(LogicTestSupport.disabledOwner("gone"), permissions("x")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("disabled");
        assertThat(List.of(providers.permissions())).containsExactly(DEFAULT);
    }
}
