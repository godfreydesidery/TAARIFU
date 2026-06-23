package com.taarifu.ussd.domain.model.enums;

/**
 * The position of a USSD session in the menu state machine (PRD §14, UC-D02, EI-4).
 *
 * <p>Responsibility: names every screen the session can be parked on between two inbound keypresses. The
 * aggregator is stateless — it re-posts {@code MSISDN + sessionId + text} on each press — so <b>we</b> hold
 * the conversational position here, persisted on {@code UssdSession.step}. The
 * {@code UssdMenuMachine} reads the step + the latest input, mutates the session, and advances to the next
 * step (or terminates).</p>
 *
 * <p>WHY an explicit enum (not a free-form string): the flow is small, fixed, and must be exhaustively
 * handled — an enum makes every branch greppable and lets the machine {@code switch} be checked by the
 * compiler, so an unhandled screen is a build error, not a stuck citizen (KISS, CLAUDE.md §3).</p>
 */
public enum UssdStep {

    /** First screen of a fresh session: choose language (Swahili / English). */
    LANGUAGE,

    /** The main menu: file report / track report / my-area alerts / help. */
    MAIN_MENU,

    /** File-report flow: pick the issue category from the offered list. */
    FILE_CATEGORY,

    /** File-report flow: use the account's registered area, or pick a ward. */
    FILE_AREA_CHOICE,

    /** File-report flow: enter a ward code/number when not using the registered area. */
    FILE_AREA_PICK,

    /** File-report flow: type the short free-text description of the issue. */
    FILE_DESCRIPTION,

    /** File-report flow: confirm (1) or cancel (2) before the ticket is created. */
    FILE_CONFIRM,

    /** Track-report flow: enter the ticket code (TAR-YYYY-NNNNNN). */
    TRACK_CODE,

    /** My-area alerts flow: confirm subscribing the registered area to alerts. */
    ALERTS_CONFIRM,

    /** Terminal sentinel: the session has ended (END sent); the row is retained until it expires. */
    DONE
}
