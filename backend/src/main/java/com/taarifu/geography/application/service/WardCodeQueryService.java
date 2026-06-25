package com.taarifu.geography.application.service;

import com.taarifu.geography.api.WardCodeQueryApi;
import com.taarifu.geography.domain.model.enums.LocationType;
import com.taarifu.geography.domain.repository.LocationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Geography's implementation of the published {@link WardCodeQueryApi} — the synchronous
 * {@code ussd → geography} "resolve this ward code to a ward id" seam (ADR-0013 §1; ADR-0019; A7;
 * PRD §9.0/§14, UC-D02).
 *
 * <p>Responsibility: map a citizen-typed ward (Kata) {@code code} to its ward public id, so the feature-phone
 * channel can let a user pin a report to a ward by typing a short friendly code instead of a UUID. It owns only
 * this read mapping; the hierarchy/electoral reads live in {@link GeographyQueryService}. It is
 * {@code @Transactional(readOnly = true)} and returns only a {@code UUID} — never the
 * {@link com.taarifu.geography.domain.model.Location} entity (boundary discipline, CLAUDE.md §8).</p>
 *
 * <p>WHY empty-not-throw and the normalisation here (not in the repository): a mistyped ward code on a feature
 * phone is routine, so a miss is a graceful empty the USSD machine turns into a "try again" {@code CON} — never
 * an exception that aborts the dialogue (EI-3, deny-by-default). The service trims the input and short-circuits
 * a blank before touching the DB; the {@code lower(code)} case-fold and the {@code type = WARD} pin live in the
 * repository query so only a true ward at the minimum pin granularity (PRD §9.0) can resolve.</p>
 */
@Service
@Transactional(readOnly = true)
public class WardCodeQueryService implements WardCodeQueryApi {

    private final LocationRepository locationRepository;

    /**
     * @param locationRepository administrative-node persistence (ward code → public id projection).
     */
    public WardCodeQueryService(LocationRepository locationRepository) {
        this.locationRepository = locationRepository;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<UUID> wardIdByCode(String wardCode) {
        if (wardCode == null) {
            // Deny-by-default: no code resolves to no ward (the USSD machine re-prompts).
            return Optional.empty();
        }
        String trimmed = wardCode.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        // Case-insensitive, WARD-pinned single-row lookup on the unique location.code index — never loads or
        // exposes the Location entity (only its public id crosses the boundary).
        return locationRepository.findPublicIdByCodeAndType(trimmed, LocationType.WARD);
    }
}
