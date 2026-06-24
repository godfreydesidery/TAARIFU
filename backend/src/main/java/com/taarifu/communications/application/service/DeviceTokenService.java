package com.taarifu.communications.application.service;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.communications.domain.model.DeviceToken;
import com.taarifu.communications.domain.model.enums.DevicePlatform;
import com.taarifu.communications.domain.port.DeviceTokenRegistry;
import com.taarifu.communications.domain.repository.DeviceTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages the push device-token registry and serves the push path's token resolution
 * (PRD §13, EI-5, US-5.1; ADR-0014 §5a fan-out target).
 *
 * <p>Responsibility: the use-case orchestration for a citizen registering/unregistering the device token
 * their app obtains from FCM, plus the {@link DeviceTokenRegistry} the push adapter reads through. It owns
 * the transaction boundary and the registry invariants:</p>
 * <ul>
 *   <li><b>Idempotent register</b> (DI4): registering an already-known token re-binds it to the caller and
 *       refreshes {@code lastSeenAt} — never a duplicate row — so the unique-per-live-token guarantee holds
 *       and a push never double-delivers to one device.</li>
 *   <li><b>Owner-only unregister</b>: a caller may only unregister a token registered to <i>their own</i>
 *       profile (logout), so one citizen can never silence another's device. Unregister is a soft-delete
 *       (history stays auditable, PRD §9).</li>
 *   <li><b>Opportunistic prune</b>: the push adapter calls {@link #pruneInvalid(String)} for a token FCM
 *       reports permanently invalid; it is an idempotent soft-delete, safe on an unknown/already-pruned
 *       token.</li>
 * </ul>
 *
 * <p><b>Secret handling (PRD §18, CLAUDE.md §12)</b>: the FCM token is a sensitive routing credential. This
 * service <b>never logs the token string</b> — log lines carry only the owning profile {@code UUID} and a
 * token <i>count/presence</i>. The token never leaves this module in an event or a cross-user DTO.</p>
 *
 * <p>WHY this service implements {@link DeviceTokenRegistry}: it is the one place that knows both the
 * schema and the registry rules, so the push adapter depends on the small port (DIP) while the controller
 * depends on the concrete service for register/unregister (which return a managed entity).</p>
 */
@Service
public class DeviceTokenService implements DeviceTokenRegistry {

    private static final Logger log = LoggerFactory.getLogger(DeviceTokenService.class);

    private final DeviceTokenRepository repository;
    private final ClockPort clock;

    /**
     * @param repository device-token persistence (registry-scoped to live rows).
     * @param clock      injectable "now" for the last-seen stamp (testability).
     */
    public DeviceTokenService(DeviceTokenRepository repository, ClockPort clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Registers (or idempotently refreshes) the caller's device token.
     *
     * <p>If the token already exists (any owner — a token uniquely identifies a device install), it is
     * re-bound to the caller and its {@code lastSeenAt} refreshed; otherwise a new live row is inserted.
     * This is the {@code POST /notification-tokens} backing call.</p>
     *
     * @param callerProfileId the authenticated caller's profile public id.
     * @param token           the opaque FCM registration token (secret; never logged).
     * @param platformName    the device platform enum name (validated).
     * @return the persisted (new or refreshed) device token.
     * @throws ApiException {@link ErrorCode#VALIDATION_FAILED} if the platform is unknown or the token is
     *                      blank/over-length.
     */
    @Transactional
    public DeviceToken register(UUID callerProfileId, String token, String platformName) {
        String normalised = normaliseToken(token);
        DevicePlatform platform = parsePlatform(platformName);

        DeviceToken row = repository.findByToken(normalised)
                .map(existing -> {
                    // Idempotent re-bind/refresh — no duplicate row (DI4).
                    existing.refresh(callerProfileId, platform, clock.now());
                    return existing;
                })
                .orElseGet(() ->
                        DeviceToken.register(callerProfileId, normalised, platform, clock.now()));
        DeviceToken saved = repository.save(row);
        // Redacted: log the owner + platform only, never the token string (PRD §18).
        log.info("Device token registered: profile={}, platform={}", callerProfileId, platform);
        return saved;
    }

    /**
     * Unregisters one of the caller's device tokens on logout (idempotent soft-delete).
     *
     * <p>A missing/already-unregistered token is a no-op success (idempotent logout). A token registered to
     * <b>another</b> profile is rejected as {@link ErrorCode#FORBIDDEN} — a caller never unregisters another
     * citizen's device.</p>
     *
     * @param callerProfileId the authenticated caller's profile public id.
     * @param token           the token to unregister (secret; never logged).
     * @throws ApiException {@link ErrorCode#FORBIDDEN} if the token belongs to a different profile.
     */
    @Transactional
    public void unregister(UUID callerProfileId, String token) {
        Optional<DeviceToken> found = repository.findByToken(normaliseToken(token));
        if (found.isEmpty()) {
            // Already gone (or never registered) — logout is idempotent.
            return;
        }
        DeviceToken row = found.get();
        if (!row.getProfileId().equals(callerProfileId)) {
            // Never let one citizen silence another's device.
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        row.markDeleted(callerProfileId);
        repository.save(row);
        log.info("Device token unregistered: profile={}", callerProfileId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the caller's live token strings, newest-registered first (so the freshest device is tried
     * first on fan-out). Read-only; never logs a token value.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public List<String> tokensFor(UUID recipientProfileId) {
        return repository.findByProfileId(recipientProfileId).stream()
                .sorted(Comparator.comparing(DeviceToken::getLastSeenAt).reversed())
                .map(DeviceToken::getToken)
                .toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Idempotent soft-delete of a token FCM reported permanently invalid; a no-op when the token is
     * unknown/already pruned so the push path can call it opportunistically without ever failing.</p>
     */
    @Override
    @Transactional
    public void pruneInvalid(String token) {
        repository.findByToken(normaliseToken(token)).ifPresent(row -> {
            // No authenticated actor on the fan-out path; attribute the prune to the token's owner.
            row.markDeleted(row.getProfileId());
            repository.save(row);
            log.info("Device token pruned (FCM-invalid): profile={}", row.getProfileId());
        });
    }

    /** Validates + trims the token; blank or over-length is a localised validation failure. */
    private String normaliseToken(String token) {
        if (token == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
        String trimmed = token.trim();
        if (trimmed.isEmpty() || trimmed.length() > DeviceToken.MAX_TOKEN_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
        return trimmed;
    }

    /** Parses the platform name; an unknown value is a localised validation failure. */
    private DevicePlatform parsePlatform(String name) {
        try {
            return DevicePlatform.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
