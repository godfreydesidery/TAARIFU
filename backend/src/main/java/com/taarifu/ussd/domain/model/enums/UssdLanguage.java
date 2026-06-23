package com.taarifu.ussd.domain.model.enums;

/**
 * The language a USSD session is conducted in (PRD §14, §15 Swahili-first).
 *
 * <p>Responsibility: records the citizen's first-screen language choice so every subsequent menu and the
 * outbound SMS confirmation are localised. Swahili is the default and first option (the platform is
 * Swahili-first, PRD §14); English is offered for the minority who prefer it.</p>
 *
 * <p>WHY only two values (not a full {@code Locale}): the USSD menu strings are authored here as short,
 * GSM-7-safe constants for exactly these two audiences; a broad locale set would be speculative generality
 * (KISS). The enum maps cleanly to the i18n {@code Accept-Language} tag when forwarding to other modules.</p>
 */
public enum UssdLanguage {

    /** Swahili (Kiswahili) — the default and first-offered language. */
    SW,

    /** English — secondary. */
    EN;

    /** @return the BCP-47 language tag ({@code "sw"}/{@code "en"}) for downstream i18n. */
    public String tag() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
