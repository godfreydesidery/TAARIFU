package com.taarifu.moderation.domain.model.enums;

/**
 * A content-safety risk <b>signal</b> the auto-assist scorer can raise on a piece of content
 * (PRD §12 US-12.3, UC-H05, EI-18; Appendix E {@code auto_moderation_triaged.signal}).
 *
 * <p>Responsibility: the closed, published vocabulary of automated risk labels the {@code ContentSafety}
 * port returns and the auto-assist pipeline records on a queue item. The values mirror the Appendix E
 * {@code auto_moderation_triaged.signal} set <b>exactly</b> ({@code PROFANITY/PII/SPAM/IMAGE}) so the
 * analytics auto-vs-manual split branches on a stable contract.</p>
 *
 * <p>WHY this is a label, never an action: auto-assist is <b>assist only, human-in-the-loop</b> (D-Q8,
 * R21). A signal can cause a queue item to be <i>held for review</i> and prioritised — it can <b>never</b>
 * approve/hide/remove content. Borderline Swahili content is never auto-removed; only a human moderator
 * (through the D16-guarded action path) takes a takedown decision.</p>
 *
 * <p>WHY GBV-sensitivity is not its own value (§25.3): the published analytics vocabulary is kept stable;
 * GBV/doxxing risk surfaces as high-confidence {@link #PROFANITY}/{@link #PII} signals on the stricter
 * sensitive-report pre-routing hold. A dedicated {@code GBV}/vision signal is an additive follow-up
 * (ADR-0018 revisit trigger), never a repurposing of an existing value.</p>
 */
public enum ContentSignal {

    /** Abusive/offensive language (hate, slurs, threats) — SW+EN lexicon, evasion-normalised. */
    PROFANITY,

    /** Exposed personal data (phone/MSISDN, national ID, email) — the doxxing/privacy screen (R20, PDPA). */
    PII,

    /** Unsolicited / repetitive spam (links, repeated tokens) at scale. */
    SPAM,

    /** Unsafe imagery — raised only by a vision-capable provider; the heuristic text scorer never sets it. */
    IMAGE
}
