package com.taarifu.ussd.api.dto;

/**
 * The USSD reply the gateway returns to the aggregator: a {@code CON}/{@code END} verb plus a body
 * (PRD §14, EI-4).
 *
 * <p>Responsibility: model the two-outcome USSD reply. {@code CON} ("continue") keeps the session open and
 * shows the next menu/prompt; {@code END} ("end") terminates the dialogue with a final line. The aggregator
 * renders {@code "<verb> <body>"} on the handset. The body must be <b>GSM-7-safe and short</b> (≤182 chars
 * per USSD page — enforced by keeping the in-module menu constants terse, PRD §14).</p>
 *
 * <p>WHY a dedicated record (not a raw String): it makes the continue-vs-terminate decision explicit and
 * testable, and lets the controller serialise the exact {@code "CON …"}/{@code "END …"} wire string in one
 * place ({@link #render()}) so no flow code hand-assembles the verb.</p>
 *
 * @param terminal whether the dialogue ends here ({@code true} → END) or continues ({@code false} → CON).
 * @param body     the menu/prompt/final text to show (GSM-7-safe, ≤182 chars).
 */
public record UssdGatewayResponse(boolean terminal, String body) {

    /**
     * @param body the next menu/prompt text.
     * @return a continue ({@code CON}) reply that keeps the session open.
     */
    public static UssdGatewayResponse con(String body) {
        return new UssdGatewayResponse(false, body);
    }

    /**
     * @param body the final line.
     * @return a terminate ({@code END}) reply that closes the session.
     */
    public static UssdGatewayResponse end(String body) {
        return new UssdGatewayResponse(true, body);
    }

    /**
     * Renders the aggregator wire string {@code "CON <body>"} / {@code "END <body>"}.
     *
     * @return the exact response line the aggregator expects.
     */
    public String render() {
        return (terminal ? "END " : "CON ") + body;
    }
}
