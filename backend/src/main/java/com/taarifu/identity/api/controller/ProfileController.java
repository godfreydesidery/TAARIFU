package com.taarifu.identity.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.common.security.RequiresTier;
import com.taarifu.identity.api.dto.MeDto;
import com.taarifu.identity.api.dto.PinLocationDto;
import com.taarifu.identity.api.dto.UpdateProfileDto;
import com.taarifu.identity.application.service.ProfileService;
import com.taarifu.identity.domain.model.enums.TrustTier;
import jakarta.validation.Valid;
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
     * Pins a ward location to the caller's profile (PRIVATE PII) — the ≥1-location half of T2.
     *
     * @param request the ward, association type, and primary flag.
     * @return {@code 200} + the recomputed tier.
     */
    @PostMapping("/me/locations")
    @PreAuthorize("isAuthenticated()")
    @RequiresTier("T1")
    public ResponseEntity<ApiResponse<Map<String, String>>> pinLocation(
            @Valid @RequestBody PinLocationDto request) {
        TrustTier tier = profileService.pinLocation(
                CurrentUser.requirePublicId(),
                request.wardPublicId(), request.associationType(), request.primary());
        return ResponseEntity.ok(responses.ok(Map.of("tier", tier.name())));
    }

    private static MeDto toDto(ProfileService.MeView v) {
        return new MeDto(v.userPublicId(), v.phone(), v.firstName(), v.lastName(), v.email(),
                v.tier(), v.phoneVerified(), v.emailVerified(), v.idVerified());
    }
}
