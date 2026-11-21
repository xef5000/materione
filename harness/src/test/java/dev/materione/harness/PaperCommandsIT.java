package dev.materione.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Commands as content on a real Paper server (logic.md §40, #330): typed arguments into {@code args.*}, an alias, a usage
 * refusal, a permission refusal, a requirement with its deny steps, a console form and the console refusal, the tree's
 * completions, and {@code open:} beside the document that opens the same GUI. See {@link CommandsRunner}.
 */
@Tag("integration")
class PaperCommandsIT {

    @Test
    void aCommandDocumentRunsWithTypedArgumentsAndTheGateRefusesInOrder() {
        HarnessResult result = new CommandsRunner().run(HarnessOptions.fromProperties());
        assertThat(result.ok()).as(result.summary()).isTrue();
    }
}
