package com.taarifu.ussd.api.controller;

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
 * <p>Responsibility: a thin HTTP layer that receives one inbound keypress, delegates to the
 * {@link UssdMenuMachine}, and returns the <b>raw {@code CON}/{@code END} string</b> the aggregator renders
 * on the handset. WHY this endpoint does <b>not</b> use the JSON {@link com.taarifu.common.api.dto.ApiResponse}
 * envelope: the USSD aggregator protocol requires a plain-text body beginning with {@code CON }/{@code END }
 * — wrapping it in JSON would break the channel (this is the one deliberate, documented exception to the
 * single-envelope rule, justified by the external protocol; ARCHITECTURE §5.1). No business logic here
 * (CLAUDE.md §8).</p>
 *
 * <p><b>Security:</b> the caller is the aggregator (a trusted server-to-server integration), not a
 * JWT-bearing citizen — a feature-phone user has no token. The handler is therefore {@code permitAll()} at
 * the method layer, but the kernel {@code SecurityConfig}'s {@code anyRequest().authenticated()} will still
 * reject it until {@code POST /ussd/gateway} is added to the central public allow-list (this module must not
 * edit {@code SecurityConfig} — see CENTRAL INTEGRATION NEEDS). The production hardening is a shared-secret
 * /IP-allow-list/HMAC check on the aggregator request, owned centrally with the public-path registration.</p>
 */
@RestController
@RequestMapping("/ussd")
@Tag(name = "USSD", description = "Feature-phone USSD session webhook (CON/END), Swahili-first.")
public class UssdGatewayController {

    private final UssdMenuMachine machine;

    /**
     * @param machine the USSD menu state machine.
     */
    public UssdGatewayController(UssdMenuMachine machine) {
        this.machine = machine;
    }

    /**
     * Handles one USSD keypress and returns the next screen as the aggregator wire string.
     *
     * @param request the validated aggregator payload.
     * @return {@code "CON …"} to continue the dialogue or {@code "END …"} to terminate it (plain text).
     */
    @PostMapping(value = "/gateway", produces = MediaType.TEXT_PLAIN_VALUE)
    @PreAuthorize("permitAll()")
    @Operation(summary = "USSD session webhook — returns a raw CON/END string (not the JSON envelope)")
    public String gateway(@Valid @RequestBody UssdGatewayRequest request) {
        UssdGatewayResponse response = machine.handle(request);
        return response.render();
    }
}
