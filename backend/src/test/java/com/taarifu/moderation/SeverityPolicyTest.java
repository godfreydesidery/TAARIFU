package com.taarifu.moderation;

import com.taarifu.moderation.application.service.SeverityPolicy;
import com.taarifu.moderation.domain.model.enums.FlagReason;
import com.taarifu.moderation.domain.model.enums.ModerationSeverity;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SeverityPolicy} — the §25.8 reason→severity classification.
 *
 * <p>Proves safety-critical reasons map to fast-track severities (and thus tight SLAs), and that every
 * reason maps to a non-null severity (no flag can fall through unclassified). No Spring/Docker.</p>
 */
class SeverityPolicyTest {

    private final SeverityPolicy policy = new SeverityPolicy();

    @Test
    void harassmentIsCriticalWithHoursSla() {
        ModerationSeverity sev = policy.initialSeverity(FlagReason.HARASSMENT);
        assertThat(sev).isEqualTo(ModerationSeverity.CRITICAL);
        // §25.8: GBV/safety review target ≤ a few hours.
        assertThat(sev.reviewTarget()).isLessThanOrEqualTo(Duration.ofHours(4));
    }

    @Test
    void piiIsHigh() {
        assertThat(policy.initialSeverity(FlagReason.PII)).isEqualTo(ModerationSeverity.HIGH);
    }

    @Test
    void abuseAndMisinformationAreMedium() {
        assertThat(policy.initialSeverity(FlagReason.ABUSE)).isEqualTo(ModerationSeverity.MEDIUM);
        assertThat(policy.initialSeverity(FlagReason.MISINFORMATION)).isEqualTo(ModerationSeverity.MEDIUM);
    }

    @Test
    void spamAndOtherAreLowWith72hSla() {
        assertThat(policy.initialSeverity(FlagReason.SPAM)).isEqualTo(ModerationSeverity.LOW);
        assertThat(policy.initialSeverity(FlagReason.OTHER)).isEqualTo(ModerationSeverity.LOW);
        // §25.8: general review target ≤ 72h.
        assertThat(ModerationSeverity.LOW.reviewTarget()).isEqualTo(Duration.ofHours(72));
    }

    @Test
    void everyReasonClassifies() {
        for (FlagReason reason : FlagReason.values()) {
            assertThat(policy.initialSeverity(reason)).isNotNull();
        }
    }
}
