package com.taarifu.institutions.application.service;

import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.geography.application.service.GeographyQueryService;
import com.taarifu.geography.domain.model.Constituency;
import com.taarifu.institutions.api.dto.MyRepresentativesDto;
import com.taarifu.institutions.api.dto.ParliamentDto;
import com.taarifu.institutions.api.dto.ParliamentRoleDto;
import com.taarifu.institutions.api.dto.PoliticalPartyDto;
import com.taarifu.institutions.api.dto.RepresentativeDto;
import com.taarifu.institutions.api.dto.RepresentativeSummaryDto;
import com.taarifu.institutions.application.mapper.InstitutionsMapper;
import com.taarifu.institutions.domain.model.Representative;
import com.taarifu.institutions.domain.model.enums.Legislature;
import com.taarifu.institutions.domain.model.enums.RepresentativeStatus;
import com.taarifu.institutions.domain.model.enums.RepresentativeType;
import com.taarifu.institutions.domain.repository.ParliamentRepository;
import com.taarifu.institutions.domain.repository.ParliamentRoleRepository;
import com.taarifu.institutions.domain.repository.PoliticalPartyRepository;
import com.taarifu.institutions.domain.repository.RepresentativeRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

/**
 * Read-only application service for the institutions slice — the party/parliament directory, the
 * representative profile/directory/search, and the first-class <b>"find my representatives"</b> flow
 * (PRD §9.1, §22.6; UC-C01, UC-C02, UC-C06, UC-C07; ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: orchestrates lookups and the find-my-rep fan-out, returning <b>DTOs</b> (never
 * entities). It owns the read transaction boundary. All public lookups are by {@code publicId}; a miss
 * throws {@link ResourceNotFoundException} with a resource-specific i18n key (Swahili-first, ADR-0010).</p>
 *
 * <p>WHY it depends on {@link GeographyQueryService} (geography's <b>public</b> application service) and
 * not on geography repositories: cross-module access goes through a module's public API, never its
 * internals (ARCHITECTURE.md §3.2). Geography owns the effective-dated Ward→Constituency resolution; this
 * service composes that result with representative lookups.</p>
 */
@Service
@Transactional(readOnly = true)
public class InstitutionsQueryService {

    private final RepresentativeRepository representativeRepository;
    private final PoliticalPartyRepository partyRepository;
    private final ParliamentRepository parliamentRepository;
    private final ParliamentRoleRepository parliamentRoleRepository;
    private final GeographyQueryService geographyQueryService;
    private final InstitutionsMapper mapper;

    /**
     * @param representativeRepository  representative persistence port.
     * @param partyRepository           party persistence port.
     * @param parliamentRepository      parliament persistence port.
     * @param parliamentRoleRepository  parliament-role persistence port.
     * @param geographyQueryService     geography's public service for Ward→Constituency resolution.
     * @param mapper                    entity→DTO mapper.
     */
    public InstitutionsQueryService(RepresentativeRepository representativeRepository,
                                    PoliticalPartyRepository partyRepository,
                                    ParliamentRepository parliamentRepository,
                                    ParliamentRoleRepository parliamentRoleRepository,
                                    GeographyQueryService geographyQueryService,
                                    InstitutionsMapper mapper) {
        this.representativeRepository = representativeRepository;
        this.partyRepository = partyRepository;
        this.parliamentRepository = parliamentRepository;
        this.parliamentRoleRepository = parliamentRoleRepository;
        this.geographyQueryService = geographyQueryService;
        this.mapper = mapper;
    }

    // ---------------------------------------------------------------------------------------------
    // Find my representatives (UC-C01) — the platform's front door, available to Guests.
    // ---------------------------------------------------------------------------------------------

    /**
     * Resolves the representatives for a ward: the sitting MP (via the ward's current constituency), the
     * sitting Councillor(s) (Diwani), and any sitting ward/village executive officer(s).
     *
     * <p>WHY the MP is resolved through geography's effective-dated bridge (not a stored ward→MP link):
     * constituency membership is re-delimited over time; the MP must be derived from the constituency the
     * ward currently maps to (PRD §9.0). A ward with no current mapping (or a vacant/being-onboarded seat)
     * yields a {@code null} MP — the client renders a "rep being onboarded" state rather than failing
     * (PRD R2). The whole flow is read-only and never hard-fails on a missing rep (graceful degradation).</p>
     *
     * @param wardPublicId the ward's public id (minimum pin granularity is Ward, PRD §9.0).
     * @return the find-my-rep bundle for the ward.
     * @throws ResourceNotFoundException if the ward does not exist or is not a WARD (delegated to geography).
     */
    public MyRepresentativesDto findRepresentativesByWard(UUID wardPublicId) {
        // Geography is authoritative for ward + effective constituency (its public API, not its internals).
        GeographyQueryService.WardPin pin = geographyQueryService.resolveWardPin(wardPublicId);
        Constituency constituency = pin.constituency();

        RepresentativeSummaryDto mp = null;
        UUID constituencyId = null;
        String constituencyName = null;
        if (constituency != null) {
            constituencyId = constituency.getPublicId();
            constituencyName = constituency.getName();
            mp = representativeRepository
                    .findByConstituencyAndStatus(constituency.getPublicId(), RepresentativeStatus.SITTING)
                    .stream()
                    .findFirst()
                    .map(mapper::toSummaryDto)
                    .orElse(null);
        }

        List<Representative> wardReps = representativeRepository
                .findByWardAndStatus(wardPublicId, RepresentativeStatus.SITTING);
        List<RepresentativeSummaryDto> councillors = wardReps.stream()
                .filter(r -> r.getType() == RepresentativeType.COUNCILLOR)
                .map(mapper::toSummaryDto)
                .toList();
        List<RepresentativeSummaryDto> wardExecutives = wardReps.stream()
                .filter(r -> r.getType() == RepresentativeType.WARD_EXEC)
                .map(mapper::toSummaryDto)
                .toList();

        return new MyRepresentativesDto(
                pin.ward().getPublicId(),
                pin.ward().getName(),
                constituencyId,
                constituencyName,
                mp,
                councillors,
                wardExecutives);
    }

    // ---------------------------------------------------------------------------------------------
    // Representative profile / directory / search (UC-C02, UC-C06).
    // ---------------------------------------------------------------------------------------------

    /**
     * Fetches a single representative's full profile.
     *
     * @param publicId the representative's public id.
     * @return the full {@link RepresentativeDto}.
     * @throws ResourceNotFoundException if not found/soft-deleted.
     */
    public RepresentativeDto getRepresentative(UUID publicId) {
        return mapper.toDto(requireRepresentative(publicId));
    }

    /**
     * Lists/searches representatives, optionally filtered by type/status, paged.
     *
     * <p>{@code FORMER} representatives remain listable (badged historical) so the accountability record
     * survives term-end (PRD §22.6). When {@code q} is provided it searches the biography; otherwise the
     * type/status directory filters apply.</p>
     *
     * @param type     optional type filter name (MP/COUNCILLOR/WARD_EXEC), or {@code null}/blank for any.
     * @param status   optional status filter name, or {@code null}/blank for any.
     * @param q        optional free-text term, or {@code null}/blank.
     * @param pageable paging/sorting.
     * @return a page of lean {@link RepresentativeSummaryDto}.
     */
    public Page<RepresentativeSummaryDto> listRepresentatives(String type, String status, String q,
                                                              Pageable pageable) {
        if (StringUtils.hasText(q)) {
            return representativeRepository.search(q.trim(), pageable).map(mapper::toSummaryDto);
        }
        RepresentativeType typeFilter = parseEnum(RepresentativeType.class, type);
        RepresentativeStatus statusFilter = parseEnum(RepresentativeStatus.class, status);
        return representativeRepository.findDirectory(typeFilter, statusFilter, pageable)
                .map(mapper::toSummaryDto);
    }

    // ---------------------------------------------------------------------------------------------
    // Party directory (UC-C07).
    // ---------------------------------------------------------------------------------------------

    /**
     * Lists/searches political parties, paged.
     *
     * @param q        optional free-text term over name/abbreviation, or {@code null}/blank for all.
     * @param pageable paging/sorting.
     * @return a page of {@link PoliticalPartyDto}.
     */
    public Page<PoliticalPartyDto> listParties(String q, Pageable pageable) {
        Page<?> page = StringUtils.hasText(q)
                ? partyRepository.search(q.trim(), pageable)
                : partyRepository.findAll(pageable);
        return castParties(page);
    }

    @SuppressWarnings("unchecked")
    private Page<PoliticalPartyDto> castParties(Page<?> page) {
        return ((Page<com.taarifu.institutions.domain.model.PoliticalParty>) page).map(mapper::toPartyDto);
    }

    /**
     * Fetches a single party by public id.
     *
     * @param publicId the party's public id.
     * @return the {@link PoliticalPartyDto}.
     * @throws ResourceNotFoundException if not found/soft-deleted.
     */
    public PoliticalPartyDto getParty(UUID publicId) {
        return partyRepository.findByPublicId(publicId)
                .map(mapper::toPartyDto)
                .orElseThrow(() -> new ResourceNotFoundException("institutions.party.notFound", publicId));
    }

    // ---------------------------------------------------------------------------------------------
    // Parliament directory (UC-C07).
    // ---------------------------------------------------------------------------------------------

    /**
     * Lists parliament terms, optionally filtered by legislature, paged.
     *
     * @param legislature optional legislature filter name, or {@code null}/blank for any.
     * @param pageable    paging/sorting.
     * @return a page of {@link ParliamentDto}.
     */
    public Page<ParliamentDto> listParliaments(String legislature, Pageable pageable) {
        Legislature filter = parseEnum(Legislature.class, legislature);
        Page<com.taarifu.institutions.domain.model.Parliament> page = filter != null
                ? parliamentRepository.findByLegislature(filter, pageable)
                : parliamentRepository.findAll(pageable);
        return page.map(mapper::toParliamentDto);
    }

    /**
     * Fetches a single parliament term by public id.
     *
     * @param publicId the term's public id.
     * @return the {@link ParliamentDto}.
     * @throws ResourceNotFoundException if not found/soft-deleted.
     */
    public ParliamentDto getParliament(UUID publicId) {
        return parliamentRepository.findByPublicId(publicId)
                .map(mapper::toParliamentDto)
                .orElseThrow(() -> new ResourceNotFoundException("institutions.parliament.notFound", publicId));
    }

    /**
     * Lists all parliament roles, paged.
     *
     * @param pageable paging/sorting.
     * @return a page of {@link ParliamentRoleDto}.
     */
    public Page<ParliamentRoleDto> listParliamentRoles(Pageable pageable) {
        return parliamentRoleRepository.findAll(pageable).map(mapper::toParliamentRoleDto);
    }

    // ---------------------------------------------------------------------------------------------
    // Internals.
    // ---------------------------------------------------------------------------------------------

    /** Loads a representative by public id or throws a localised not-found. */
    private Representative requireRepresentative(UUID publicId) {
        return representativeRepository.findByPublicId(publicId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("institutions.representative.notFound", publicId));
    }

    /**
     * Null/blank-tolerant enum parse for optional filter params.
     *
     * <p>WHY tolerant (returns {@code null}) for a blank value but a typed {@link ApiException} for a
     * non-blank invalid value: a blank filter means "any"; a malformed non-blank filter is a client bug
     * surfaced as a localised {@link ErrorCode#BAD_REQUEST} (400) — never an unhandled 500 (PRD §17).</p>
     */
    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.BAD_REQUEST, value);
        }
    }
}
