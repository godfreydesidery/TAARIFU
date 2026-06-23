package com.taarifu.geography.infrastructure.adapter;

import com.taarifu.geography.domain.model.Location;
import com.taarifu.geography.domain.model.enums.LocationType;
import com.taarifu.geography.domain.repository.LocationRepository;
import com.taarifu.geography.domain.port.Geocoder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Stub/sandbox {@link Geocoder} adapter for dev/test/demo (ADR-0004, ARCHITECTURE.md §7).
 *
 * <p>Responsibility: resolves any coordinate to a deterministic fallback ward (the first {@code WARD}
 * row available) so the whole system can run E2E with <b>no boundary geometry seeded</b> and <b>zero
 * external calls</b>. This is what makes CI hermetic and staged region-by-region onboarding possible
 * (ADR-0004, ADR-0009).</p>
 *
 * <p>WHY it returns empty when no ward exists at all: that faithfully exercises the EI-7 degradation
 * path (no resolution → manual drill-down) rather than fabricating a fake location. It never invents a
 * ward that is not in the database.</p>
 *
 * <p>Activated by {@code taarifu.geography.geocoder=stub}; the PostGIS adapter is the default.</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.geography.geocoder", havingValue = "stub")
public class StubGeocoder implements Geocoder {

    private final LocationRepository locationRepository;

    /**
     * @param locationRepository used to pick a deterministic fallback ward from seeded data.
     */
    public StubGeocoder(LocationRepository locationRepository) {
        this.locationRepository = locationRepository;
    }

    /**
     * @param latitude  ignored by the stub.
     * @param longitude ignored by the stub.
     * @return the first available {@code WARD}, or empty if none are seeded (exercises degradation).
     */
    @Override
    public Optional<Location> resolveWard(double latitude, double longitude) {
        return locationRepository.findByType(LocationType.WARD, PageRequest.of(0, 1))
                .stream().findFirst();
    }
}
