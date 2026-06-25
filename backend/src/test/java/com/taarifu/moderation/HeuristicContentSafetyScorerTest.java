package com.taarifu.moderation;

import com.taarifu.moderation.api.FlagSubjectType;
import com.taarifu.moderation.domain.model.enums.ContentSignal;
import com.taarifu.moderation.domain.port.ContentSafety;
import com.taarifu.moderation.domain.port.ContentSafety.ContentSafetyRequest;
import com.taarifu.moderation.domain.port.ContentSafety.ContentSafetyResult;
import com.taarifu.moderation.infrastructure.adapter.HeuristicContentSafetyScorer;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link HeuristicContentSafetyScorer} — the Swahili+English auto-assist scorer (US-12.3,
 * EI-18, R21; ADR-0018). No Spring/Docker.
 *
 * <p>Proves the load-bearing safety behaviours: it raises PROFANITY on Swahili insults (incl. an evasion
 * spelling), PII on a Tanzanian phone number, SPAM only on clustered cues, and — critically — returns an
 * <b>empty</b> result for clean/blank/null content so nothing is held without cause (the human pipeline
 * floor). Every signal is a label + confidence; the scorer has no path to a takedown.</p>
 */
class HeuristicContentSafetyScorerTest {

    private final HeuristicContentSafetyScorer scorer = new HeuristicContentSafetyScorer();

    private ContentSafetyResult score(String text) {
        return scorer.score(new ContentSafetyRequest(FlagSubjectType.COMMENT, UUID.randomUUID(), text, "sw"));
    }

    @Test
    void raisesProfanity_onSwahiliInsult() {
        ContentSafetyResult r = score("Wewe ni mjinga kabisa");
        assertThat(r.signals()).extracting(ContentSafety.SafetySignal::signal)
                .contains(ContentSignal.PROFANITY);
        assertThat(r.topSignal().confidence()).isBetween(0.80, 0.97);
    }

    @Test
    void raisesProfanity_onElongationEvasionSpelling() {
        // "mjingaaaa" must normalise to "mjinga" (R21 evasion-resistance) and still be caught.
        ContentSafetyResult r = score("wewe ni mjingaaaa");
        assertThat(r.signals()).extracting(ContentSafety.SafetySignal::signal)
                .contains(ContentSignal.PROFANITY);
    }

    @Test
    void raisesPii_onTanzanianPhoneNumber() {
        ContentSafetyResult r = score("Mpigie huyu jamaa 0712345678 amfuate nyumbani");
        assertThat(r.signals()).extracting(ContentSafety.SafetySignal::signal).contains(ContentSignal.PII);
        // PII is a high-confidence, low-false-positive structured match.
        assertThat(r.signals().stream().filter(s -> s.signal() == ContentSignal.PII).findFirst()
                .orElseThrow().confidence()).isGreaterThanOrEqualTo(0.85);
    }

    @Test
    void raisesSpam_onlyWhenCuesCluster() {
        // A single innocuous cue ("pesa") must NOT raise spam (conservative — avoid over-holding).
        assertThat(score("Nahitaji msaada wa pesa kwa shule").signals())
                .extracting(ContentSafety.SafetySignal::signal).doesNotContain(ContentSignal.SPAM);
        // Two+ clustered promo cues raise SPAM.
        assertThat(score("Bonyeza hapa upate mkopo wa haraka bure").signals())
                .extracting(ContentSafety.SafetySignal::signal).contains(ContentSignal.SPAM);
    }

    @Test
    void returnsEmpty_forCleanContent_soNothingIsHeldWithoutCause() {
        ContentSafetyResult r = score("Asante kwa huduma nzuri, barabara imetengenezwa vizuri");
        assertThat(r.signals()).isEmpty();
        assertThat(r.recommendHold()).isFalse();
        assertThat(r.topSignal()).isNull();
    }

    @Test
    void returnsEmpty_forNullOrBlank() {
        assertThat(score(null).signals()).isEmpty();
        assertThat(score("   ").signals()).isEmpty();
    }
}
