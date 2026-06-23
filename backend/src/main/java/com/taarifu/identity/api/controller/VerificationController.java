package com.taarifu.identity.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.common.security.RequiresTier;
import com.taarifu.identity.api.dto.SubmitVerificationDto;
import com.taarifu.identity.api.dto.VerificationStatusDto;
import com.taarifu.identity.application.service.VerificationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Citizen-facing ID/voter verification submit (Flow 2, VERIFICATION-DESIGN §4, §9.3).
 *
 * <p>Responsibility: a thin REST surface over {@link VerificationService}. Submitting requires <b>live</b>
 * T2 ({@code @RequiresTier("T2")} — MF-2). The {@code idNo} is field-encrypted and never echoed; the
 * response carries only the request reference + status. No business logic/transaction here.</p>
 */
@RestController
@RequestMapping("/profiles/me/verification")
public class VerificationController {

    private final VerificationService verificationService;
    private final ResponseFactory responses;

    /**
     * @param verificationService the citizen-submit service.
     * @param responses           envelope builder.
     */
    public VerificationController(VerificationService verificationService, ResponseFactory responses) {
        this.verificationService = verificationService;
        this.responses = responses;
    }

    /**
     * Submits a government ID for verification (dedup-guarded, D15) → creates a {@code PENDING} request.
     *
     * @param request the ID type, number (PII), claimed name, and optional evidence ref.
     * @return {@code 202} + the request reference + {@code PENDING} status.
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @RequiresTier("T2")
    public ResponseEntity<ApiResponse<VerificationStatusDto>> submit(
            @Valid @RequestBody SubmitVerificationDto request) {
        VerificationService.SubmitResult result = verificationService.submitIdVerification(
                CurrentUser.requirePublicId(),
                request.idType(), request.idNo(), request.fullName(), request.evidenceRef());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(responses.ok(
                new VerificationStatusDto(result.verificationPublicId(), result.status().name())));
    }
}
