package com.taarifu.ussd.api.controller;

import com.taarifu.common.domain.port.CryptoPort;
import com.taarifu.common.security.UssdGatewayRateLimiter;
import com.taarifu.ussd.api.dto.UssdGatewayRequest;
import com.taarifu.ussd.api.dto.UssdGatewayResponse;
import com.taarifu.ussd.application.service.UssdMenuMachine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The aggregator-facing USSD webhook (PRD §14, EI-4, UC-D02).
 *
 * <p>Responsibility: a thin HTTP layer that receives one inbound keypress, applies per-MSISDN
 * anti-automation, delegates to the {@link UssdMenuMachine}, and returns the <b>raw {@code CON}/{@code END}
 * string</b> the aggregator renders on the handset. WHY this endpoint does <b>not</b> use the JSON
 * {@link com.taarifu.common.api.dto.ApiResponse} envelope: the USSD aggregator protocol requires a
 * plain-text body beginning with {@code CON }/{@code END } — wrapping it in JSON would break the channel
 * (the one deliberate, documented exception to the single-envelope rule, justified by the external
 * protocol; ARCHITECTURE §5.1). No business logic here (CLAUDE.md §8).</p>
 *
 * <p><b>Security (wave2-review P2-1, THREAT-MODEL TB-3):</b> the caller is the aggregator (a trusted
 * server-to-server integration), not a JWT-bearing citizen — a feature-phone user has no token. The handler
 * is therefore {@code permitAll()} at the method layer. Two controls protect the open webhook:
 * <ol>
 *   <li><b>Aggregator authentication</b> — a shared-secret header verified by
 *       {@code UssdGatewaySecretFilter} <i>before</i> this controller runs (fail-closed when unset).</li>
 *   <li><b>Per-MSISDN rate-limiting</b> — {@link UssdGatewayRateLimiter} caps the keypress rate and, more
 *       tightly, the new-dialogue (no-OTP T1 account-creation) trigger, so a flood relayed through the
 *       authenticated link still cannot mass-provision accounts or spam reports.</li>
 * </ol>
 * The kernel {@code SecurityConfig}'s {@code anyRequest().authenticated()} will reject this path until
 * {@code POST /ussd/gateway} is added to the central public allow-list — this module must not edit
 * {@code SecurityConfig} (see CENTRAL INTEGRATION NEEDS).</p>
 */
@RestController
@RequestMapping("/ussd")
@Tag(name = "USSD", description = "Feature-phone USSD session webhook (CON/END), Swahili-first.")
public class UssdGatewayController {

    private final UssdMenuMachine machine;
    private final UssdGatewayRateLimiter rateLimiter;
    private final CryptoPort crypto;

    /**
     * @param machine     the USSD menu state machine.
     * @param rateLimiter per-MSISDN anti-automation for the open webhook (P2-1).
     * @param crypto      hashes (blind-indexes) the MSISDN for the rate-limit key so no raw PII is used as
     *                    a limiter key (S-4, PDPA).
     */
    public UssdGatewayController(UssdMenuMachine machine,
                                 UssdGatewayRateLimiter rateLimiter,
                                 CryptoPort crypto) {
        this.machine = machine;
        this.rateLimiter = rateLimiter;
        this.crypto = crypto;
    }

    /**
     * Handles one USSD keypress and returns the next screen as the aggregator wire string.
     *
     * <p>Rate-limits per MSISDN before any work: every turn counts against the per-MSISDN keypress cap, and
     * the <b>first hit of a fresh dialogue</b> (empty/blank {@code text} — the moment the flow auto-creates
     * the T1 account) additionally counts against the tighter new-session cap. A breach returns a plain
     * {@code END} line (USSD wire form) rather than the JSON 429 envelope, so the handset shows a clean
     * "try again later" message and no further work runs.</p>
     *
     * @param request the validated aggregator payload.
     * @return {@code "CON …"} to continue the dialogue or {@code "END …"} to terminate it (plain text).
     */
    @PostMapping(value = "/gateway", produces = MediaType.TEXT_PLAIN_VALUE)
    @PreAuthorize("permitAll()")
    @Operation(summary = "USSD session webhook — returns a raw CON/END string (not the JSON envelope)")
    public String gateway(@Valid @RequestBody UssdGatewayRequest request) {
        // Hashed MSISDN key — the limiter (and any log/metric it touches) never sees the raw number (S-4).
        String msisdnHash = crypto.blindIndex(normalise(request.msisdn()));
        boolean firstHit = request.text() == null || request.text().isBlank();
        if (!rateLimiter.allowSessionTurn(msisdnHash)
                || (firstHit && !rateLimiter.allowNewSession(msisdnHash))) {
            return UssdGatewayResponse.end("Umejaribu mara nyingi. Tafadhali jaribu tena baadaye.").render();
        }
        UssdGatewayResponse response = machine.handle(request);
        return response.render();
    }

    /**
     * Normalises an MSISDN to a stable key form (trim + strip spaces) so the rate-limit key matches the
     * machine's session key for the same number. Full E.164 normalisation belongs in the aggregator
     * adapter (DI7); here we only need a consistent key.
     *
     * @param msisdn the raw MSISDN from the payload.
     * @return the normalised key form (never {@code null}).
     */
    private static String normalise(String msisdn) {
        return msisdn == null ? "" : msisdn.trim().replace(" ", "");
    }
}
