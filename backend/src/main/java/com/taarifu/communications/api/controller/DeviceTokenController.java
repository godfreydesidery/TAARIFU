package com.taarifu.communications.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.communications.api.dto.DeviceTokenDto;
import com.taarifu.communications.api.dto.RegisterDeviceTokenRequest;
import com.taarifu.communications.application.mapper.CommunicationsMapper;
import com.taarifu.communications.application.service.DeviceTokenService;
import com.taarifu.communications.domain.model.DeviceToken;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Citizen push device-token registration (PRD §13, EI-5, US-5.1).
 *
 * <p>Responsibility: the thin REST surface over {@link DeviceTokenService}. It registers the device token a
 * citizen's app obtains from FCM (so push can actually reach a device) and unregisters it on logout. It
 * validates input, delegates, and wraps the result in the single {@link ApiResponse} envelope — no
 * business logic, no {@code @Transactional} (CLAUDE.md §8).</p>
 *
 * <p><b>Authorization</b>: any authenticated citizen registers/unregisters only their <b>own</b> device
 * tokens ({@code @PreAuthorize("isAuthenticated()")}); the service binds the registration to the caller's
 * profile id and rejects unregistering another profile's token. This is a non-binding, non-civic action
 * (it never reads a token <i>balance</i> and is not part of the integrity fence — PRD §23.5); it must stay
 * reachable by the lowest authenticated tier so a feature-phone-adjacent smartphone user is never gated out
 * of receiving notifications.</p>
 *
 * <p><b>Secret handling (PRD §18)</b>: the FCM token is a sensitive routing credential. It is accepted in
 * the request body and used as a path variable on unregister, but it is <b>never logged</b> and never
 * returned in a response body (the {@link DeviceTokenDto} omits it).</p>
 */
@RestController
@RequestMapping("/notification-tokens")
@Tag(name = "Notification tokens", description = "Register/unregister push device tokens for FCM delivery.")
public class DeviceTokenController {

    private final DeviceTokenService deviceTokenService;
    private final CommunicationsMapper mapper;
    private final ResponseFactory responses;

    /**
     * @param deviceTokenService register/unregister orchestration + the device-token registry.
     * @param mapper             entity→DTO mapper.
     * @param responses          envelope builder.
     */
    public DeviceTokenController(DeviceTokenService deviceTokenService,
                                 CommunicationsMapper mapper,
                                 ResponseFactory responses) {
        this.deviceTokenService = deviceTokenService;
        this.mapper = mapper;
        this.responses = responses;
    }

    /**
     * Registers (or idempotently refreshes) the caller's device token.
     *
     * <p>Idempotent: re-posting a known token re-binds it to the caller and refreshes its last-seen rather
     * than creating a duplicate, so a device is registered at most once (DI4).</p>
     *
     * @param request the validated {token, platform} registration.
     * @return {@code 201} + the {@link DeviceTokenDto} (no token value echoed).
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Register a push device token",
            description = "Idempotent upsert by token value; binds the token to the caller's profile.")
    public ResponseEntity<ApiResponse<DeviceTokenDto>> register(
            @Valid @RequestBody RegisterDeviceTokenRequest request) {
        DeviceToken token = deviceTokenService.register(
                CurrentUser.requirePublicId(), request.token(), request.platform());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responses.ok(mapper.toDeviceTokenDto(token)));
    }

    /**
     * Unregisters one of the caller's device tokens on logout (idempotent soft-delete).
     *
     * <p>Unregistering an already-gone token is a no-op success; unregistering a token owned by a different
     * profile is forbidden (the service enforces ownership).</p>
     *
     * @param token the raw device token to unregister (secret; never logged).
     * @return {@code 200} with no body.
     */
    @DeleteMapping("/{token}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Unregister a push device token (logout)")
    public ResponseEntity<ApiResponse<Void>> unregister(@PathVariable String token) {
        deviceTokenService.unregister(CurrentUser.requirePublicId(), token);
        return ResponseEntity.ok(responses.ok(null));
    }
}
