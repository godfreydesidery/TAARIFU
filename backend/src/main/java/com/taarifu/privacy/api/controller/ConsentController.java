package com.taarifu.privacy.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.privacy.api.dto.ConsentDto;
import com.taarifu.privacy.api.dto.RecordConsentRequest;
import com.taarifu.privacy.application.service.ConsentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The citizen privacy-center consent endpoints (PRD §18 PDPA, UC-A16, US-0.7; ADR-0016 §2/§7).
 *
 * <p>Responsibility: a thin, <b>self-service</b> surface — a citizen records (grants/withdraws) and reads
 * <b>their own</b> consent decisions. The subject is always bound from the authenticated principal
 * ({@code CurrentUser.requirePublicId()}), never from the request body, so one citizen can never act on
 * another's consent. Deny-by-default method security ({@code isAuthenticated()}); no business logic or
 * transaction here — {@link ConsentService} owns the append-on-change ledger and the audit.</p>
 */
@RestController
@RequestMapping("/privacy/consents")
public class ConsentController {

    private final ConsentService consentService;
    private final ResponseFactory responses;

    /**
     * @param consentService the consent ledger service.
     * @param responses      the single envelope builder.
     */
    public ConsentController(ConsentService consentService, ResponseFactory responses) {
        this.consentService = consentService;
        this.responses = responses;
    }

    /**
     * Records a consent decision (grant/withdraw) for the authenticated caller.
     *
     * @param request the purpose + state + policy version (+ optional source).
     * @return {@code 200} + the new current decision.
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<ConsentDto>> record(@Valid @RequestBody RecordConsentRequest request) {
        ConsentDto decision = consentService.record(CurrentUser.requirePublicId(), request);
        return ResponseEntity.ok(responses.ok(decision));
    }

    /**
     * Lists the authenticated caller's current consent decisions across all purposes (the privacy center).
     *
     * @return {@code 200} + one decision per decided purpose.
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<ConsentDto>>> list() {
        List<ConsentDto> current = consentService.listCurrent(CurrentUser.requirePublicId());
        return ResponseEntity.ok(responses.ok(current));
    }
}
