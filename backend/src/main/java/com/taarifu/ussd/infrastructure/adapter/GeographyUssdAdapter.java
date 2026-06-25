package com.taarifu.ussd.infrastructure.adapter;

import com.taarifu.geography.api.WardCodeQueryApi;
import com.taarifu.ussd.application.port.UssdGeographyPort;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Production {@link UssdGeographyPort} adapter — delegates to geography's published {@link WardCodeQueryApi}
 * (the sanctioned synchronous {@code ussd → geography} read contract, ADR-0013 §1; ADR-0019; A7).
 *
 * <p>Responsibility: bind the USSD module's consumer-owned geography seam to geography's real ward-by-code
 * lookup, so the "enter a ward code" step resolves a friendly ward code against the real administrative
 * hierarchy — closing the prior {@code // TODO(wiring)} scaffold that accepted only a typed UUID.</p>
 *
 * <p>This adapter holds <b>no logic</b>: it is a one-line delegation to the published query port, the ADR-0013
 * pattern (the consumer's port, the producer's {@code api} implementation, an adapter wiring them by
 * {@code UUID}/code only — never a geography entity). No token is read on this path (the civic-integrity fence,
 * D18).</p>
 */
@Component
public class GeographyUssdAdapter implements UssdGeographyPort {

    private final WardCodeQueryApi wardCodeQueryApi;

    /**
     * @param wardCodeQueryApi geography's published ward-code lookup query port.
     */
    public GeographyUssdAdapter(WardCodeQueryApi wardCodeQueryApi) {
        this.wardCodeQueryApi = wardCodeQueryApi;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<UUID> wardIdByCode(String wardCode) {
        return wardCodeQueryApi.wardIdByCode(wardCode);
    }
}
