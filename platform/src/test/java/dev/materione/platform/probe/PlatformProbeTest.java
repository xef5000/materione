package dev.materione.platform.probe;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.platform.ProbeResult;
import org.junit.jupiter.api.Test;

class PlatformProbeTest {

    @Test
    void floorVersionIsSupported() {
        var result = PlatformProbe.probe("1.21.4-232-main", false, false, false);
        assertThat(result).isInstanceOf(ProbeResult.Supported.class);
        var supported = (ProbeResult.Supported) result;
        assertThat(supported.descriptor().minecraftVersion()).isEqualTo("1.21.4");
        assertThat(supported.descriptor().supports(dev.materione.api.platform.PlatformCapability.ITEM_MODELS)).isTrue();
        assertThat(supported.descriptor().supports(dev.materione.api.platform.PlatformCapability.FOLIA)).isFalse();
    }

    @Test
    void newerVersionsAreSupportedIncludingCalendarVersioning() {
        assertThat(PlatformProbe.probe("1.21.11-5-main", false, false, false))
                .isInstanceOf(ProbeResult.Supported.class);
        // Calendar versioning (26.2): numeric compare, not lexicographic.
        assertThat(PlatformProbe.probe("26.2-100-main", false, false, false))
                .isInstanceOf(ProbeResult.Supported.class);
    }

    @Test
    void belowFloorIsUnsupportedWithClearReason() {
        var result = PlatformProbe.probe("1.21.3-88-main", false, false, false);
        assertThat(result).isInstanceOf(ProbeResult.Unsupported.class);
        var unsupported = (ProbeResult.Unsupported) result;
        assertThat(unsupported.reason()).contains("1.21.3").contains("1.21.4");
        assertThat(unsupported.instructions()).containsIgnoringCase("update");
    }

    @Test
    void muchOlderServersAreUnsupported() {
        assertThat(PlatformProbe.probe("1.20.1-63-main", false, false, false))
                .isInstanceOf(ProbeResult.Unsupported.class);
    }

    @Test
    void garbageVersionIsUnsupportedNotAnException() {
        var result = PlatformProbe.probe("not-a-version", false, false, false);
        assertThat(result).isInstanceOf(ProbeResult.Unsupported.class);
        assertThat(((ProbeResult.Unsupported) result).reason()).contains("unrecognizable");
        assertThat(PlatformProbe.probe(null, false, false, false))
                .isInstanceOf(ProbeResult.Unsupported.class);
    }

    @Test
    void foliaFlagFlowsIntoDescriptor() {
        var result = (ProbeResult.Supported) PlatformProbe.probe("1.21.4-232-main", true, true, true);
        var descriptor = result.descriptor();
        assertThat(descriptor.folia()).isTrue();
        assertThat(descriptor.geyserPresent()).isTrue();
        assertThat(descriptor.viaVersionPresent()).isTrue();
        assertThat(descriptor.supports(dev.materione.api.platform.PlatformCapability.FOLIA)).isTrue();
    }

    @Test
    void versionParsing() {
        assertThat(PlatformProbe.parse("1.21.4-232-main")).containsExactly(1, 21, 4);
        assertThat(PlatformProbe.parse("26.2")).containsExactly(26, 2, 0);
        assertThat(PlatformProbe.parse("1.21")).containsExactly(1, 21, 0);
        assertThat(PlatformProbe.parse("junk")).isNull();
        assertThat(PlatformProbe.parse("")).isNull();
    }
}
