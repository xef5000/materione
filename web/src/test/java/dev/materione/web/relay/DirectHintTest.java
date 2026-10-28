package dev.materione.web.relay;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.web.relay.DirectHint.Mode;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;

/**
 * The hint policy only decides whether to INVITE a browser probe; it must
 * never produce a hint that could mislead on its own, and the default must
 * stay silent for every sender that does not look co-located.
 */
class DirectHintTest {

    private static final String CLOUD = "https://editor.materione.com";

    private static InetAddress ip(String literal) throws Exception {
        return InetAddress.getByName(literal);
    }

    @Test
    void autoHintsOnlyForLoopbackSenders() throws Exception {
        assertThat(DirectHint.resolve(Mode.AUTO, true, "127.0.0.1", 8089, "", "", CLOUD, ip("127.0.0.1")))
                .isEqualTo("http://127.0.0.1:8089");
        assertThat(DirectHint.resolve(Mode.AUTO, true, "0.0.0.0", 8089, "", "", CLOUD, ip("::1")))
                .isEqualTo("http://127.0.0.1:8089");
    }

    @Test
    void autoStaysSilentForRemoteConsoleAndLanSenders() throws Exception {
        // a remote player (hosting panel, proxy-less public IP)
        assertThat(DirectHint.resolve(Mode.AUTO, true, "127.0.0.1", 8089, "", "", CLOUD, ip("203.0.113.9"))).isNull();
        // a LAN player: the server cannot know its own LAN address, and http://LAN is mixed content anyway
        assertThat(DirectHint.resolve(Mode.AUTO, true, "0.0.0.0", 8089, "", "", CLOUD, ip("192.168.1.20"))).isNull();
        // console: nothing is known about the browser
        assertThat(DirectHint.resolve(Mode.AUTO, true, "127.0.0.1", 8089, "", "", CLOUD, null)).isNull();
    }

    @Test
    void neverHintsWithoutARunningListenerOrWhenOff() throws Exception {
        assertThat(DirectHint.resolve(Mode.AUTO, false, "", -1, "", "", CLOUD, ip("127.0.0.1"))).isNull();
        assertThat(DirectHint.resolve(Mode.ALWAYS, false, "127.0.0.1", 8089, "", "", CLOUD, null)).isNull();
        assertThat(DirectHint.resolve(Mode.OFF, true, "127.0.0.1", 8089, "https://ed.example.com", "", CLOUD, ip("127.0.0.1")))
                .isNull();
    }

    @Test
    void loopbackHintRequiresALoopbackReachableBind() throws Exception {
        // bound to a specific LAN address: 127.0.0.1 would not reach it
        assertThat(DirectHint.resolve(Mode.AUTO, true, "192.168.1.5", 8089, "", "", CLOUD, ip("127.0.0.1"))).isNull();
        assertThat(DirectHint.resolve(Mode.ALWAYS, true, "192.168.1.5", 8089, "", "", CLOUD, null)).isNull();
    }

    @Test
    void alwaysHintsWithoutSenderEvidence() {
        assertThat(DirectHint.resolve(Mode.ALWAYS, true, "127.0.0.1", 8089, "", "", CLOUD, null))
                .isEqualTo("http://127.0.0.1:8089");
    }

    @Test
    void pinnedHttpsFrontWinsForAnySenderAndIsNormalized() throws Exception {
        assertThat(DirectHint.resolve(Mode.AUTO, true, "127.0.0.1", 8089,
                "https://editor.team.example/some/path", "", CLOUD, ip("203.0.113.9")))
                .isEqualTo("https://editor.team.example");
        assertThat(DirectHint.resolve(Mode.AUTO, true, "127.0.0.1", 8089,
                "https://editor.team.example:8443", "", CLOUD, null))
                .isEqualTo("https://editor.team.example:8443");
    }

    @Test
    void plainHttpPublicUrlIsIgnoredBecauseAnHttpsPageCannotUseIt() throws Exception {
        assertThat(DirectHint.resolve(Mode.AUTO, true, "0.0.0.0", 8089,
                "http://mc.example.com:8089", "", CLOUD, ip("203.0.113.9"))).isNull();
        assertThat(DirectHint.resolve(Mode.AUTO, true, "0.0.0.0", 8089, "garbage ::", "", CLOUD, null)).isNull();
    }

    @Test
    void publicHostIsHonouredOnlyForAPlainHttpEditorPage() {
        // self-hosted dev relay served over http: a plain-http hint is usable there
        assertThat(DirectHint.resolve(Mode.AUTO, true, "0.0.0.0", 8089, "", "10.0.0.7",
                "http://localhost:8787", null)).isEqualTo("http://10.0.0.7:8089");
        // the https cloud page can never use it: no hint rather than a doomed one
        assertThat(DirectHint.resolve(Mode.AUTO, true, "0.0.0.0", 8089, "", "10.0.0.7", CLOUD, null)).isNull();
    }

    @Test
    void modeParsingFailsSafe() {
        assertThat(Mode.parse(null)).isEqualTo(Mode.OFF);
        assertThat(Mode.parse("auto")).isEqualTo(Mode.AUTO);
        assertThat(Mode.parse("garbage")).isEqualTo(Mode.OFF);
        assertThat(Mode.parse(" Auto ")).isEqualTo(Mode.AUTO);
        assertThat(Mode.parse(" OFF ")).isEqualTo(Mode.OFF);
        assertThat(Mode.parse("false")).isEqualTo(Mode.OFF);
        assertThat(Mode.parse("Always")).isEqualTo(Mode.ALWAYS);
    }
}
