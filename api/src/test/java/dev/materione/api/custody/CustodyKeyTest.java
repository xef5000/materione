package dev.materione.api.custody;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.logic.variable.StackRef;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustodyKeyTest {

    @Test
    void canonicalKeysAreNeverNormalizedOrAliased() {
        assertThat(CustodyKey.require("vault:player/2")).isEqualTo("vault:player/2");
        for (String bad : new String[] {"", " Vault:1", "Vault:1", "vault:../x", "a b", "é"}) {
            assertThat(CustodyKey.valid(bad)).as(bad).isFalse();
            assertThatThrownBy(() -> CustodyKey.require(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void holderEqualityIncludesTheOwnerAndTheExactKey() {
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertThat(new CustodyHolder.Keyed(owner, "vault:1"))
                .isEqualTo(new CustodyHolder.Keyed(owner, "vault:1"))
                .isNotEqualTo(new CustodyHolder.Keyed(owner, "vault:2"))
                .isNotEqualTo(new CustodyHolder.Keyed(UUID.randomUUID(), "vault:1"));
        StackRef stack = new StackRef() {
            @Override
            public UUID owner() {
                return owner;
            }
        };
        assertThat(new CustodyHolder.Stack(owner, stack))
                .isEqualTo(new CustodyHolder.Stack(owner, stack));
    }
}
