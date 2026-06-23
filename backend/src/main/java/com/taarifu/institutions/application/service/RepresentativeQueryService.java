package com.taarifu.institutions.application.service;

import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.geography.domain.model.Constituency;
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
 * the constituency they hold, or empty for a ward/special-seats/nominated seat that has no constituency. It
 * is {@code @Transactional(readOnly = true)} and exposes only DTO-grade {@code UUID}s — never the
 * {@link Representative}/{@link Constituency} entities — to the caller (boundary discipline, CLAUDE.md §8).</p>
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
        Representative rep = representativeRepository.findByPublicId(representativePublicId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "institutions.representative.notFound", representativePublicId));
        // CONSTITUENCY-mandate MPs hold a constituency; councillor/special-seats/nominated reps do not.
        Constituency constituency = rep.getConstituency();
        return Optional.ofNullable(constituency).map(Constituency::getPublicId);
    }
}
