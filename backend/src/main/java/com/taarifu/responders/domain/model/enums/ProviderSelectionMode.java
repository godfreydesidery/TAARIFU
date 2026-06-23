package com.taarifu.responders.domain.model.enums;

/**
 * How a {@link com.taarifu.responders.domain.model.RoutingRule} chooses the responder for a category
 * (PRD §24.2).
 *
 * <p>Responsibility: encodes the two routing strategies §24.2 describes — the platform resolves the
 * provider automatically by sector + area ({@link #AUTO_BY_AREA}: electricity → the area's TANESCO
 * office), or the <b>citizen picks</b> the specific provider at report time ({@link #CITIZEN_SELECTED}:
 * an ATM/bank issue → the specific bank; network/airtime → the specific telecom).</p>
 *
 * <p>WHY this is on the rule (not inferred at routing time): the report-submission UX must know in
 * advance whether to present a provider picker, and the citizen must be told <b>who</b> their report
 * goes to before submitting (PDPA, §24.4). Making the strategy explicit on the rule lets the reporting
 * module render the right flow without a cross-module reach-around.</p>
 */
public enum ProviderSelectionMode {

    /**
     * The platform resolves the responder automatically by sector + the report's area (PRD §24.2).
     * Used for area-bound services (electricity, water, government-area categories).
     */
    AUTO_BY_AREA,

    /**
     * The citizen selects the specific provider at report time (PRD §24.2). Used where several
     * providers of the same sector serve the same area (banks, telecoms).
     */
    CITIZEN_SELECTED
}
