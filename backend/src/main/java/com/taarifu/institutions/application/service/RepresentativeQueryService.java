package com.taarifu.institutions.application.service;

import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.geography.domain.model.Constituency;
import com.taarifu.geography.domain.model.Location;
import com.taarifu.institutions.api.RepresentativeQueryApi;
import com.taarifu.institutions.domain.model.Representative;
import com.taarifu.institutions.domain.repository.RepresentativeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Live implementation of {@link RepresentativeQueryApi} — the institutions-owned resolution of a
 * representative's existence and constituency for cross-module binding-action scoping (ADR-0013 §1; D13).
 *
 * <p>Responsibility: validate the representative exists (else {@code NOT_FOUND}) and return the public id of
 * the constituency they hold (constituency-mandate MP) or the ward they hold (councillor/ward-exec) — the
 * two-tier electoral mapping the binding-action fence gates on (D13/F1). It is
 * {@code @Transactional(readOnly = true)} and exposes only DTO-grade {@code UUID}s — never the
 * {@link Representative}/{@link Constituency}/{@link Location} entities — to the caller (boundary
 * discipline, CLAUDE.md §8).</p>
 */
@Service
@Transactional(readOnly = true)
public class RepresentativeQueryService implements RepresentativeQueryApi {

    private final RepresentativeRepository representativeRepository;

    /**
     * @param representativeRepository representative persistence port (existence + constituency read).
     */
    public RepresentativeQueryService(RepresentativeRepository representativeRepository) {
        this.representativeRepository = representativeRepository;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<UUID> constituencyOf(UUID representativePublicId) {
        // CONSTITUENCY-mandate MPs hold a constituency; councillor/special-seats/nominated reps do not.
        Constituency constituency = require(representativePublicId).getConstituency();
        return Optional.ofNullable(constituency).map(Constituency::getPublicId);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<UUID> wardOf(UUID representativePublicId) {
        // COUNCILLOR_WARD councillors / ward-execs hold a ward; constituency-MPs/special-seats/nominated
        // do not (F1, D13). A null ward ⇒ empty ⇒ the caller applies no ward electoral gate for this subject.
        Location ward = require(representativePublicId).getWard();
        return Optional.ofNullable(ward).map(Location::getPublicId);
    }

    /** Loads a representative by public id or throws a localised {@code NOT_FOUND} (cannot scope a phantom). */
    private Representative require(UUID representativePublicId) {
        return representativeRepository.findByPublicId(representativePublicId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "institutions.representative.notFound", representativePublicId));
    }
}
