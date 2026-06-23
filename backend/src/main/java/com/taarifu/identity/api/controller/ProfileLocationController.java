package com.taarifu.identity.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.common.security.RequiresTier;
import com.taarifu.identity.api.dto.LocationCreatedDto;
import com.taarifu.identity.api.dto.PinLocationDto;
import com.taarifu.identity.application.service.LocationService;
import com.taarifu.identity.application.service.ProfileService;
import com.taarifu.identity.domain.model.enums.TrustTier;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * {@code ProfileLocation} management — add/remove, set the single primary, set the single electoral
 * (VERIFICATION-DESIGN §6, §9.2; PRD §9.0, D12/D13). Replaces the single location POST that lived on
 * {@code ProfileController}.
 *
 * <p>Responsibility: a thin REST surface over {@link LocationService} (and {@link ProfileService} for the
 * tier-recompute on add). Every {@code ProfileLocation} is <b>private PII</b> — no endpoint here ever
 * returns the pin to anyone but its owner, and never another citizen's location (§18). All endpoints
 * require authentication; tier gates are enforced <b>live</b> by {@code @RequiresTier} (MF-2). Set-electoral
 * additionally enforces the change cooldown in the service (D13). No business logic/transaction here.</p>
 */
@RestController
@RequestMapping("/profiles/me/locations")
public class ProfileLocationController {

    private final LocationService locationService;
    private final ProfileService profileService;
    private final ResponseFactory responses;

    /**
     * @param locationService the {@code ProfileLocation} lifecycle service.
     * @param profileService  used to recompute the live tier after an add (the ≥1-pin half of T2).
     * @param responses       envelope builder.
     */
    public ProfileLocationController(LocationService locationService,
                             ProfileService profileService,
                             ResponseFactory responses) {
        this.locationService = locationService;
        this.profileService = profileService;
        this.responses = responses;
    }

    /**
     * Adds a ward pin to the caller's profile (the ≥1-pin half of T2).
     *
     * @param request the ward, association type, and primary flag.
     * @return {@code 201} + the new location id and the caller's recomputed live tier.
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @RequiresTier("T1")
    public ResponseEntity<ApiResponse<LocationCreatedDto>> add(@Valid @RequestBody PinLocationDto request) {
        UUID me = CurrentUser.requirePublicId();
        UUID locationId = locationService.addLocation(
                me, request.wardPublicId(), request.associationType(), request.primary());
        // Recompute the live tier so the client sees a T2 promotion immediately (MF-2 — server-computed).
        TrustTier tier = profileService.getMeTier(me);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responses.ok(new LocationCreatedDto(locationId, tier.name())));
    }

    /**
     * Removes one of the caller's locations (soft-delete). Refuses to remove the last location of a T2+
     * user or the voter-ID-authoritative electoral (re-verify to move) — both yield {@code CONFLICT}.
     *
     * @param publicId the location to remove.
     * @return {@code 200} success envelope.
     */
    @DeleteMapping("/{publicId}")
    @PreAuthorize("isAuthenticated()")
    @RequiresTier("T1")
    public ResponseEntity<ApiResponse<Void>> remove(@PathVariable UUID publicId) {
        locationService.removeLocation(CurrentUser.requirePublicId(), publicId);
        return ResponseEntity.ok(responses.ok(null));
    }

    /**
     * Sets a location as the single primary (default context), demoting any prior primary (D12).
     *
     * @param publicId the location to promote.
     * @return {@code 200} success envelope.
     */
    @PatchMapping("/{publicId}/primary")
    @PreAuthorize("isAuthenticated()")
    @RequiresTier("T1")
    public ResponseEntity<ApiResponse<Void>> setPrimary(@PathVariable UUID publicId) {
        locationService.setPrimary(CurrentUser.requirePublicId(), publicId);
        return ResponseEntity.ok(responses.ok(null));
    }

    /**
     * <b>Manual</b> set of the single electoral location (binding civic weight). Cooldown-guarded; refused
     * for a voter-ID-authoritative electoral (D13). Requires T2.
     *
     * @param publicId the location to make electoral.
     * @return {@code 200} success envelope.
     */
    @PatchMapping("/{publicId}/electoral")
    @PreAuthorize("isAuthenticated()")
    @RequiresTier("T2")
    public ResponseEntity<ApiResponse<Void>> setElectoral(@PathVariable UUID publicId) {
        locationService.setElectoralManual(CurrentUser.requirePublicId(), publicId);
        return ResponseEntity.ok(responses.ok(null));
    }
}
