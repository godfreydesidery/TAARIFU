package com.taarifu.responders.application.service;

import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.responders.api.dto.OrganisationDto;
import com.taarifu.responders.api.dto.ResponderDto;
import com.taarifu.responders.application.mapper.ResponderMapper;
import com.taarifu.responders.domain.model.Organisation;
import com.taarifu.responders.domain.model.Responder;
import com.taarifu.responders.domain.model.enums.OrganisationStatus;
import com.taarifu.responders.domain.repository.OrganisationRepository;
import com.taarifu.responders.domain.repository.ResponderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Read-only application service for the <b>public provider directory</b> — "who handles what"
 * (PRD §24.1, §24.3).
 *
 * <p>Responsibility: serves the citizen-facing directory of responders and organisations. Every read
 * here is constrained to <b>publicly listable</b> rows — an organisation that is
 * {@link OrganisationStatus#ACTIVE} and verified, and a responder that is ACTIVE under such an
 * organisation (PRD §24.4). A PENDING, suspended, or unverified provider must never appear in a citizen
 * response; the repository queries enforce that, so this service cannot accidentally leak one.</p>
 *
 * <p>WHY a separate service from {@link ResponderAdminService}: the directory is unauthenticated/public
 * (browse who-handles-what, §24.1) while management is Moderator/Admin-only; splitting them keeps the
 * public read-path free of any admin-only field exposure and makes the visibility rule reviewable in
 * one place (SOLID single-responsibility).</p>
 */
@Service
@Transactional(readOnly = true)
public class ResponderDirectoryService {

    private final OrganisationRepository organisationRepository;
    private final ResponderRepository responderRepository;
    private final ResponderMapper mapper;

    /**
     * @param organisationRepository organisation persistence port.
     * @param responderRepository    responder persistence port.
     * @param mapper                 entity→DTO mapper.
     */
    public ResponderDirectoryService(OrganisationRepository organisationRepository,
                                     ResponderRepository responderRepository,
                                     ResponderMapper mapper) {
        this.organisationRepository = organisationRepository;
        this.responderRepository = responderRepository;
        this.mapper = mapper;
    }

    /**
     * Lists the publicly listable organisations (active + verified), paged.
     *
     * @param pageable bounded paging/sorting.
     * @return a page of {@link OrganisationDto}.
     */
    public Page<OrganisationDto> listPublicOrganisations(Pageable pageable) {
        return organisationRepository
                .findByStatusAndVerifiedTrue(OrganisationStatus.ACTIVE, pageable)
                .map(mapper::toOrganisationDto);
    }

    /**
     * Fetches a single publicly listable organisation by public id.
     *
     * @param publicId the organisation's public id.
     * @return the {@link OrganisationDto}.
     * @throws ResourceNotFoundException if missing or not publicly listable (hidden as not-found so the
     *         existence of an unverified/pending provider is not disclosed to the public — anti-enumeration).
     */
    public OrganisationDto getPublicOrganisation(UUID publicId) {
        Organisation org = organisationRepository
                .findByPublicIdAndStatusAndVerifiedTrue(publicId, OrganisationStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("responders.organisation.notFound", publicId));
        return mapper.toOrganisationDto(org);
    }

    /**
     * Lists the publicly listable responders (ACTIVE under active+verified orgs), optionally filtered
     * to those handling a given category, paged.
     *
     * @param categoryPublicId optional reporting-category id to filter by ("who handles X?"); {@code null}
     *                         lists all publicly listable responders.
     * @param pageable         bounded paging/sorting.
     * @return a page of {@link ResponderDto}.
     */
    public Page<ResponderDto> listPublicResponders(UUID categoryPublicId, Pageable pageable) {
        Page<Responder> page = categoryPublicId == null
                ? responderRepository.findPubliclyListable(pageable)
                : responderRepository.findPubliclyListableByCategory(categoryPublicId, pageable);
        return page.map(mapper::toResponderDto);
    }

    /**
     * Fetches a single publicly listable responder by public id.
     *
     * @param publicId the responder's public id.
     * @return the {@link ResponderDto}.
     * @throws ResourceNotFoundException if missing or not publicly listable.
     */
    public ResponderDto getPublicResponder(UUID publicId) {
        Responder responder = responderRepository.findByPublicId(publicId)
                .filter(r -> r.getStatus() == com.taarifu.responders.domain.model.enums.ResponderStatus.ACTIVE
                        && r.getOrganisation() != null
                        && r.getOrganisation().isPubliclyListable())
                .orElseThrow(() -> new ResourceNotFoundException("responders.responder.notFound", publicId));
        return mapper.toResponderDto(responder);
    }
}
