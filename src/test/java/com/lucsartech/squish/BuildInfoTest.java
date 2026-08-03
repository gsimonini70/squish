package com.lucsartech.squish;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BuildInfo}. These stay environment-agnostic: whether {@code build.properties}
 * was filtered (a Maven build → real version/commit) or not (an IDE run → fallbacks), the invariants
 * below must hold. No Spring context or database is needed.
 */
@DisplayName("BuildInfo (build identity)")
class BuildInfoTest {

    @Test
    @DisplayName("version is never null, blank, or an unfiltered @...@ placeholder")
    void versionIsResolved() {
        assertThat(BuildInfo.version())
                .isNotBlank()
                .doesNotStartWith("@");
    }

    @Test
    @DisplayName("build number and time are never null (default to \"unknown\")")
    void buildNumberAndTimeNeverNull() {
        assertThat(BuildInfo.buildNumber()).isNotBlank().doesNotStartWith("@");
        assertThat(BuildInfo.buildTime()).isNotBlank().doesNotStartWith("@");
    }

    @Test
    @DisplayName("fullVersion always starts with the release version")
    void fullVersionStartsWithVersion() {
        assertThat(BuildInfo.fullVersion()).startsWith(BuildInfo.version());
    }

    @Test
    @DisplayName("fullVersion appends the build number only when it is known")
    void fullVersionAppendsKnownBuildNumber() {
        if ("unknown".equals(BuildInfo.buildNumber())) {
            assertThat(BuildInfo.fullVersion()).isEqualTo(BuildInfo.version());
        } else {
            assertThat(BuildInfo.fullVersion())
                    .isEqualTo(BuildInfo.version() + "+" + BuildInfo.buildNumber());
        }
    }
}
