package com.taarifu.identity.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.identity.api.dto.TotpCodeDto;
import com.taarifu.identity.api.dto.TotpSetupDto;
import com.taarifu.identity.application.service.TotpService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff TOTP (MFA) enrolment endpoints (N-4, VERIFICATION-DESIGN §7.3, §9.5).
 *
 * <p>Responsibility: thin REST surface over {@link TotpService} for the authenticated account to enrol a
 * TOTP second factor. {@code setup} returns the provisioning URI + secret <b>once</b>; {@code activate}
 * verifies a code and enables MFA. The login second-factor step itself ({@code /auth/login/totp}) lives
 * on {@code AuthController} (it is public — it carries the {@code MFA_CHALLENGE} token, not an access
 * token). Both endpoints here require authentication; no business logic or transaction lives here.</p>
 */
@RestController
@RequestMapping("/auth/mfa/totp")
public class MfaController {

    private final TotpService totpService;
    private final ResponseFactory responses;

    /**
     * @param totpService the TOTP provisioning/verification service.
     * @param responses   envelope builder.
     */
    public MfaController(TotpService totpService, ResponseFactory responses) {
        this.totpService = totpService;
        this.responses = responses;
    }

    /**
     * Provisions a TOTP secret for the caller and returns the enrolment material once.
     *
     * @return {@code 200} + the {@code otpauth} URI + raw secret (shown once; never logged).
     */
    @PostMapping("/setup")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<TotpSetupDto>> setup() {
        TotpService.TotpEnrolment enrolment = totpService.setup(CurrentUser.requirePublicId());
        return ResponseEntity.ok(responses.ok(new TotpSetupDto(enrolment.otpauthUri(), enrolment.secret())));
    }

    /**
     * Activates MFA for the caller by verifying a TOTP code against the pending secret.
     *
     * @param request the 6-digit TOTP code.
     * @return {@code 200} success envelope.
     */
    @PostMapping("/activate")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> activate(@Valid @RequestBody TotpCodeDto request) {
        totpService.activate(CurrentUser.requirePublicId(), request.totp());
        return ResponseEntity.ok(responses.ok(null));
    }
}
