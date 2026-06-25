package com.taarifu.moderation.infrastructure.adapter;

import com.taarifu.moderation.domain.model.enums.ContentSignal;
import com.taarifu.moderation.domain.port.ContentSafety;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Default {@link ContentSafety} — a conservative, <b>Swahili+English heuristic</b> content-risk scorer that
 * produces auto-assist labels (US-12.3, UC-H05, EI-18, D-Q8) and <b>never removes content</b>.
 *
 * <p>Responsibility: lets every environment run the auto-assist screen with <b>zero external calls</b> until a
 * real ML/hosted Swahili classifier is configured. It scans the content text with curated SW/EN lexicons
 * (PROFANITY, SPAM) and regex rules (PII — Tanzanian MSISDN, NIDA-shaped IDs, email) and returns
 * {@link SafetySignal}s with confidences. It decides nothing about takedown — a signal only lets the
 * auto-assist policy <i>hold for human review</i> (assist only, R21).</p>
 *
 * <p><b>WHY Swahili-aware normalisation (R21):</b> naive classifiers miss Swahili because of diacritics,
 * elongation, and code-switching/Sheng. {@link #normalise(String)} lower-cases, strips combining diacritics,
 * and collapses 3+ repeated letters (so {@code "mjingaaaa"} matches {@code "mjinga"}) before lexicon lookup —
 * catching common evasion spellings. The lexicons hold both Swahili and English terms so a code-switched
 * message is still screened. This is a <b>heuristic, explicitly assist</b>: limited recall is accepted (the
 * human pipeline + community flagging is the floor); the conservative threshold lives in the policy, not here.</p>
 *
 * <p><b>WHY {@code @ConditionalOnProperty(..., matchIfMissing = true)}</b> (the {@code LoggingSmsGatewayStub}
 * pattern, ARCHITECTURE §7): this is the match-if-missing default for
 * {@code taarifu.moderation.content-safety.provider}, so exactly one {@link ContentSafety} bean exists in
 * every environment and a no-provider context boots and degrades to this heuristic. A real ML adapter is
 * selected only by {@code provider=ml} (mutually exclusive on the same property). When neither this nor a real
 * provider scores anything, the auto-assist policy holds nothing and <b>everything routes to human moderators</b>
 * (EI-18 degradation).</p>
 *
 * <p><b>🔒 PII / content discipline (PRD §18, PDPA):</b> the content body and any detected PII are <b>never
 * logged</b> — only a non-PII signal count at debug. The text is consumed transiently and never persisted.</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.moderation.content-safety.provider", havingValue = "heuristic",
        matchIfMissing = true)
public class HeuristicContentSafetyScorer implements ContentSafety {

    private static final Logger log = LoggerFactory.getLogger(HeuristicContentSafetyScorer.class);

    /**
     * Curated abuse/hate/threat lexicon — Swahili + English, normalised form. Conservative and small by
     * design (R21: a curated lexicon beats an over-eager classifier on Swahili). Append-only and reviewable;
     * a real classifier replaces this entirely. NOTE: these are normalised (diacritic-folded, lower-case)
     * tokens, matched as whole words against the normalised text.
     */
    private static final Set<String> PROFANITY_SW_EN = Set.of(
            // Swahili insults / hate / threat markers (illustrative, curated — extend via review).
            "mjinga", "pumbavu", "fala", "shenzi", "malaya", "takataka", "kumbavu",
            "nitakuua", "tutawaua", "wauawe", "wachinjwe", "mende",
            // English insults / threats commonly code-switched in.
            "idiot", "stupid", "kill", "rape", "scum");

    /**
     * Spam markers — money/promo/repetition cues common in Swahili promo spam, normalised form.
     */
    private static final Set<String> SPAM_SW_EN = Set.of(
            "bure", "bonyeza", "shinda", "tuma", "pesa", "mkopo", "haraka",
            "free", "winner", "click", "loan", "promo", "bit.ly");

    /** Tanzanian MSISDN: {@code +2557XXXXXXXX} / {@code 06/07XXXXXXXX} — doxxing/PII screen (R20). */
    private static final Pattern TZ_MSISDN = Pattern.compile("(\\+?255|0)[67]\\d{8}");

    /** NIDA-shaped national ID: a run of 18–20 digits (optionally hyphenated) — PII screen. */
    private static final Pattern NIDA_ID = Pattern.compile("\\b\\d{8}[- ]?\\d{5}[- ]?\\d{5}[- ]?\\d?\\b");

    /** Email address — PII screen. */
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");

    /** Collapses 3+ repeats of a letter to one (elongation evasion: {@code mjingaaaa} → {@code mjinga}). */
    private static final Pattern ELONGATION = Pattern.compile("(\\p{L})\\1{2,}");

    /** Splits normalised text into word tokens for whole-word lexicon matching. */
    private static final Pattern WORD_SPLIT = Pattern.compile("[^\\p{L}\\p{Nd}]+");

    /** {@inheritDoc} */
    @Override
    public ContentSafetyResult score(ContentSafetyRequest request) {
        String text = request == null ? null : request.text();
        if (text == null || text.isBlank()) {
            return ContentSafetyResult.empty();
        }

        List<SafetySignal> signals = new ArrayList<>();

        // PII / doxxing: regex on the RAW text (patterns are case-insensitive enough; do NOT log the match).
        if (TZ_MSISDN.matcher(text).find() || NIDA_ID.matcher(text).find() || EMAIL.matcher(text).find()) {
            // High confidence: a structured identifier match is a strong, low-false-positive PII signal.
            signals.add(new SafetySignal(ContentSignal.PII, 0.90));
        }

        // Lexicon screens on the NORMALISED, tokenised text (Swahili evasion-resistant).
        Set<String> tokens = tokens(normalise(text));
        long profanityHits = PROFANITY_SW_EN.stream().filter(tokens::contains).count();
        if (profanityHits > 0) {
            // Conservative confidence that climbs with hit count but caps below certainty (it is a heuristic).
            signals.add(new SafetySignal(ContentSignal.PROFANITY, confidence(profanityHits, 0.82)));
        }
        long spamHits = SPAM_SW_EN.stream().filter(tokens::contains).count();
        if (spamHits >= 2) {
            // Require 2+ spam cues to avoid flagging an innocent "pesa"/"free"; promo spam clusters cues.
            signals.add(new SafetySignal(ContentSignal.SPAM, confidence(spamHits, 0.80)));
        }

        if (log.isDebugEnabled()) {
            // NON-PII only: counts of signals, never the content or the matched terms (S-4, §18).
            log.debug("auto-assist heuristic: subjectType={}, signals={}",
                    request.subjectType(), signals.size());
        }
        // recommendHold = any signal at/above a baseline; the policy still applies its configurable threshold.
        boolean recommend = signals.stream().anyMatch(s -> s.confidence() >= 0.80);
        return new ContentSafetyResult(List.copyOf(signals), recommend);
    }

    /**
     * Lower-cases, strips combining diacritics, and collapses letter elongation so Swahili evasion spellings
     * normalise to their base form before lexicon lookup (R21).
     *
     * @param text the raw content text.
     * @return the normalised form for matching (never the value persisted/logged).
     */
    private static String normalise(String text) {
        String lower = text.toLowerCase();
        String deAccented = Normalizer.normalize(lower, Normalizer.Form.NFKD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return ELONGATION.matcher(deAccented).replaceAll("$1");
    }

    /** Splits normalised text into a set of word tokens for whole-word matching. */
    private static Set<String> tokens(String normalised) {
        return java.util.Arrays.stream(WORD_SPLIT.split(normalised))
                .filter(t -> !t.isBlank())
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Maps a hit count to a capped confidence: {@code base} for one hit, climbing 0.05 per extra hit, capped
     * at 0.97 (never 1.0 — a heuristic is never certain).
     */
    private static double confidence(long hits, double base) {
        return Math.min(0.97, base + 0.05 * (hits - 1));
    }
}
