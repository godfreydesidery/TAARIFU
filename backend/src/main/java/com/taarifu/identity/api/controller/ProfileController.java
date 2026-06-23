package com.taarifu.identity.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.common.security.RequiresTier;
import com.taarifu.identity.api.dto.EmailOtpRequestDto;
import com.taarifu.identity.api.dto.OtpChallengeDto;
import com.taarifu.identity.api.dto.MeDto;
import com.taarifu.identity.api.dto.UpdateProfileDto;
import com.taarifu.identity.api.dto.VerifyOtpDto;
import com.taarifu.identity.application.service.ProfileService;
import com.taarifu.identity.domain.model.enums.TrustTier;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Profile read + completion endpoints on the path to T2 (AUTH-DESIGN §14.2).
 *
 * <p>Responsibility: thin REST surface over {@link ProfileService}. Every endpoint requires
 * authentication ({@code @PreAuthorize("isAuthenticated()")}); the tier-affecting ones are additionally
 * gated by {@link RequiresTier} (enforced <b>live</b> by the {@code RequiresTierAspect} — MF-2). It owns
 * no business logic or transaction. Responses use the single {@link ApiResponse} envelope; {@code /me}
 * never returns another user's PII (PRD §18).</p>
 */
@RestController
@RequestMapping("/profiles")
public class ProfileController {

    private final ProfileService profileService;
    private final ResponseFactory responses;

    /**
     * @param profileService profile read/completion service.
     * @param responses      envelope builder.
     */
    public ProfileController(ProfileService profileService, ResponseFactory responses) {
        this.profileService = profileService;
        this.responses = responses;
    }

    /**
     * Returns the caller's own profile snapshot.
     *
     * @return {@code 200} + the caller's {@link MeDto}.
     */
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<MeDto>> me() {
        ProfileService.MeView v = profileService.getMe(CurrentUser.requirePublicId());
        return ResponseEntity.ok(responses.ok(toDto(v)));
    }

    /**
     * Returns the caller's <b>live</b> trust tier (the same resolver used for gating — UI hint source).
     *
     * @return {@code 200} + {@code {tier: "Tn"}}.
     */
    @GetMapping("/me/tier")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, String>>> myTier() {
        ProfileService.MeView v = profileService.getMe(CurrentUser.requirePublicId());
        return ResponseEntity.ok(responses.ok(Map.of("tier", v.tier())));
    }

    /**
     * Completes the caller's profile (PATCH) — may promote T1→T2.
     *
     * @param request the profile fields to set.
     * @return {@code 200} + the recomputed tier.
     */
    @PatchMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @RequiresTier("T1")
    public ResponseEntity<ApiResponse<Map<String, String>>> updateMe(
            @Valid @RequestBody UpdateProfileDto request) {
        TrustTier tier = profileService.updateProfile(
                CurrentUser.requirePublicId(),
                request.firstName(), request.lastName(),
                request.dateOfBirth(), request.gender(), request.nationality());
        return ResponseEntity.ok(responses.ok(Map.of("tier", tier.name())));
    }

    /**
     * Requests an EMAIL VERIFY OTP for the caller's contact-channel verification (T2 path,
     * VERIFICATION-DESIGN §3, §9.1). The email is recorded on the account; the code is delivered
     * out-of-band and never appears in any response (S-4).
     *
     * @param request the destination email.
     * @return {@code 202} + the OTP challenge id to verify against.
     */
    @PostMapping("/me/email/otp")
    @PreAuthorize("isAuthenticated()")
    @RequiresTier("T1")
    public ResponseEntity<ApiResponse<OtpChallengeDto>> requestEmailOtp(
            @Valid @RequestBody EmailOtpRequestDto request) {
        UUID challengeId = profileService.requestEmailVerification(
                CurrentUser.requirePublicId(), request.email());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(responses.ok(new OtpChallengeDto(challengeId)));
    }

    /**
     * Verifies the EMAIL VERIFY OTP → marks email verified → recomputes the live tier (may promote T2).
     *
     * @param request the challenge id + code.
     * @return {@code 200} + the recomputed tier.
     */
    @PostMapping("/me/email/verify")
    @PreAuthorize("isAuthenticated()")
    @RequiresTier("T1")
    public ResponseEntity<ApiResponse<Map<String, String>>> verifyEmailOtp(
            @Valid @RequestBody VerifyOtpDto request) {
        TrustTier tier = profileService.verifyEmail(
                CurrentUser.requirePublicId(), request.challengeId(), request.code());
        return ResponseEntity.ok(responses.ok(Map.of("tier", tier.name())));
    }

    private static MeDto toDto(ProfileService.MeView v) {
        return new MeDto(v.userPublicId(), v.phone(), v.firstName(), v.lastName(), v.email(),
                v.tier(), v.phoneVerified(), v.emailVerified(), v.idVerified());
    }
}
