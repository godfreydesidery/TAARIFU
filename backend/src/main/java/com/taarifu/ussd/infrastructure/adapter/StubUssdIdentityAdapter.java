package com.taarifu.ussd.infrastructure.adapter;

import com.taarifu.ussd.application.port.UssdIdentityPort;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link UssdIdentityPort} adapter — deterministically derives a stable account id per MSISDN so the
 * system boots and the USSD flows run end-to-end with <b>zero external calls</b> (ARCHITECTURE §7 stub
 * principle).
 *
 * <p>Responsibility: stand in for identity's (not-yet-published) signup-by-MSISDN command port. It maps each
 * MSISDN to a stable {@link UUID} (so the same caller is the same "account" across sessions) and remembers a
 * registered ward only if one was set in-process — it does <b>not</b> create real identity rows. This is a
 * scaffold: the production adapter delegates to identity's published port ({@code // TODO(wiring)}).</p>
 *
 * <p>WHY a deterministic id (UUID v3 over the MSISDN) and not random: tracking/area shortcuts in a single
 * dialogue, and re-entry across dialogues, must see a consistent account without a backing store. The MSISDN
 * is never logged here (S-4).</p>
 */
@Component
public class StubUssdIdentityAdapter implements UssdIdentityPort {

    /** Namespace for deterministic per-MSISDN account ids (stub only). */
    private static final UUID MSISDN_NS = UUID.fromString("0a3d8f1e-2b6c-4d7a-9e1f-7c2b5a4d6e80");

    /** In-process registered-ward memory for the stub (so "use my area" can be exercised in tests). */
    private final ConcurrentHashMap<UUID, UUID> registeredWardByUser = new ConcurrentHashMap<>();

    /** {@inheritDoc} */
    @Override
    public UUID linkOrCreateByMsisdn(String msisdn) {
        // TODO(wiring): delegate to identity's published signup-by-MSISDN command port (T1, one-per-phone,
        // PII-encrypted) once com.taarifu.identity.api exposes it (ADR-0013 §1; CENTRAL INTEGRATION NEEDS).
        return UUID.nameUUIDFromBytes((MSISDN_NS + "|" + msisdn).getBytes(StandardCharsets.UTF_8));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<UUID> registeredWardId(UUID userPublicId) {
        // TODO(wiring): read the account's registered ProfileLocation ward via identity's published query port.
        return Optional.ofNullable(registeredWardByUser.get(userPublicId));
    }

    /**
     * Test/dev hook: set a stub registered ward for an account so the "use my area" branch can be exercised.
     *
     * @param userPublicId the account id.
     * @param wardId       the ward to register.
     */
    public void setRegisteredWard(UUID userPublicId, UUID wardId) {
        registeredWardByUser.put(userPublicId, wardId);
    }
}
