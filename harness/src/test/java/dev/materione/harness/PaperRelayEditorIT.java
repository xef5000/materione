package dev.materione.harness;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.testing.relay.FakeMailbox;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The M5 relay gate, live on Paper (roadmap M5; web.md §3/§4.1): the plugin's
 * DEFAULT editor surface — the cloud relay — end to end, with the
 * contract-accurate {@link FakeMailbox} standing in for the Cloudflare
 * worker. The full cloud path runs on a real server: channel create, sealed
 * blob in the payload store, capability URL, mailbox hello, in-game trust
 * approval, key confirmation, sealed RPC, and the save→apply push.reload —
 * without dialing the real internet (the injected {@code relay_base_url}
 * points at the fake) and without the plugin opening any inbound port.
 */
@Tag("integration")
class PaperRelayEditorIT {

    @Test
    void editorRelayHandshakeWorksLiveOnPaper() throws Exception {
        FakeMailbox mailbox = new FakeMailbox();
        var server = mailbox.start();
        String relayBase = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            HarnessOptions base = HarnessOptions.fromProperties();
            HarnessOptions options = base.withScenario(
                    base.workRoot(), "version: 1\nrelay_base_url: " + relayBase + "\n");
            HarnessResult result = new RelayEditorRunner().run(options, relayBase);
            // The summary carries every recorded problem + the log tail.
            assertThat(result.ok()).as(result.summary()).isTrue();
        } finally {
            mailbox.stop();
        }
    }
}
