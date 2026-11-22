package dev.materione.plugin.commands;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * How one typed command becomes runs (logic.md §40, #467): one run for plain values, one per matched player for a
 * selector, the combinations of several, and the cap that stops a selector from becoming a flood. The expansion of a
 * live selector is proven on a server (PaperCommandsIT); this is the arithmetic.
 */
class ArgInvocationsTest {

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> values(Object... pairs) {
        var out = new LinkedHashMap<String, List<String>>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], (List<String>) pairs[i + 1]);
        }
        return out;
    }

    private static List<String> names(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count).mapToObj(i -> "P" + i).toList();
    }

    @Test
    void plainValuesAreOneRun() {
        var expansion = ArgInvocations.product(values("to", List.of("Bob"), "amount", List.of("5")));

        assertThat(expansion.runs()).containsExactly(Map.of("to", "Bob", "amount", "5"));
        assertThat(expansion.capped()).isFalse();
        assertThat(expansion.error()).isNull();
    }

    @Test
    void noArgumentsIsOneEmptyRun() {
        assertThat(ArgInvocations.product(values()).runs()).containsExactly(Map.of());
    }

    @Test
    void aSelectorThatMatchedSeveralPlayersIsOneRunEachInTheMatchOrder() {
        var expansion = ArgInvocations.product(values("to", List.of("A", "B", "C"), "amount", List.of("5")));

        assertThat(expansion.runs()).extracting(run -> run.get("to")).containsExactly("A", "B", "C");
        assertThat(expansion.runs()).allSatisfy(run -> assertThat(run).containsEntry("amount", "5"));
    }

    @Test
    void twoSelectorsMakeTheirCombinations() {
        var expansion = ArgInvocations.product(values("from", List.of("A", "B"), "to", List.of("X", "Y")));

        assertThat(expansion.runs()).hasSize(4);
        assertThat(expansion.runs().get(0)).containsEntry("from", "A").containsEntry("to", "X");
        assertThat(expansion.runs().get(3)).containsEntry("from", "B").containsEntry("to", "Y");
    }

    @Test
    void pastTheCapTheRestAreDroppedAndTheExpansionSaysSo() {
        var expansion = ArgInvocations.product(values("to", names(40)));

        assertThat(expansion.runs()).hasSize(ArgInvocations.MAX_RUNS).hasSize(16);
        assertThat(expansion.runs().get(15)).containsEntry("to", "P16");
        assertThat(expansion.capped()).isTrue();
        assertThat(expansion.wanted()).isEqualTo(40);
    }

    @Test
    void exactlyTheCapIsNotCapped() {
        var expansion = ArgInvocations.product(values("to", names(16)));

        assertThat(expansion.runs()).hasSize(16);
        assertThat(expansion.capped()).isFalse();
    }

    @Test
    void combinationsAreCappedInAllNotPerSelector() {
        var expansion = ArgInvocations.product(values("from", names(10), "to", names(10)));

        assertThat(expansion.runs()).hasSize(16);
        assertThat(expansion.capped()).isTrue();
        assertThat(expansion.wanted()).isEqualTo(100);
    }
}
