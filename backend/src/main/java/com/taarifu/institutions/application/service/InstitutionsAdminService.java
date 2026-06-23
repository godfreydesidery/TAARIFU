package com.taarifu.institutions.application.service;

import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.geography.application.service.GeographyQueryService;
import com.taarifu.geography.domain.model.Constituency;
import com.taarifu.geography.domain.model.Location;
import com.taarifu.institutions.api.dto.ParliamentDto;
import com.taarifu.institutions.api.dto.ParliamentRoleDto;
import com.taarifu.institutions.api.dto.ParliamentRoleWriteDto;
import com.taarifu.institutions.api.dto.ParliamentWriteDto;
import com.taarifu.institutions.api.dto.PartyWriteDto;
import com.taarifu.institutions.api.dto.PoliticalPartyDto;
import com.taarifu.institutions.api.dto.RepresentativeDto;
import com.taarifu.institutions.api.dto.RepresentativeWriteDto;
import com.taarifu.institutions.application.mapper.InstitutionsMapper;
import com.taarifu.institutions.domain.model.Parliament;
import com.taarifu.institutions.domain.model.ParliamentRole;
import com.taarifu.institutions.domain.model.PoliticalParty;
import com.taarifu.institutions.domain.model.Representative;
import com.taarifu.institutions.domain.model.enums.Legislature;
import com.taarifu.institutions.domain.model.enums.Mandate;
import com.taarifu.institutions.domain.model.enums.PartyStatus;
import com.taarifu.institutions.domain.model.enums.RepresentativeStatus;
import com.taarifu.institutions.domain.model.enums.RepresentativeType;
import com.taarifu.institutions.domain.repository.ParliamentRepository;
import com.taarifu.institutions.domain.repository.ParliamentRoleRepository;
import com.taarifu.institutions.domain.repository.PoliticalPartyRepository;
import com.taarifu.institutions.domain.repository.RepresentativeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.UUID;

/**
 * Write/CRUD application service for the institutions reference data — political parties, parliament
 * terms, parliament roles, and representatives (PRD §9.1; UC-B11..B13, UC-C04, UC-C08).
 *
 * <p>Responsibility: owns the write transaction boundary and <b>all institutions integrity invariants</b>.
 * Controllers gate access with {@code @PreAuthorize("hasRole('ADMIN')")}; this service assumes an
 * authorised admin caller and concentrates on correctness, not authorization.</p>
 *
 * <h3>Integrity invariants enforced here</h3>
 * <ul>
 *   <li><b>One SITTING constituency-MP per constituency</b> — a pre-check raises a localised
 *       {@link ErrorCode#CONFLICT} with a helpful message; the DB partial-unique index is the backstop
 *       against races (PRD §9.1 "one sitting MP per constituency").</li>
 *   <li><b>Mandate ⇄ geography coherence</b> — CONSTITUENCY ⇒ constituency required &amp; ward null;
 *       COUNCILLOR_WARD ⇒ ward required &amp; constituency null; SPECIAL_SEATS/NOMINATED ⇒ both null.
 *       Violations raise {@link ErrorCode#VALIDATION_FAILED}; a DB CHECK is the backstop.</li>
 *   <li><b>Single current parliament term per legislature</b> — marking a term current clears the prior
 *       current term in the same transaction; the DB partial-unique index is the backstop.</li>
 *   <li><b>Immutable identity codes</b> — a party/role {@code code} cannot change on update (it is the
 *       idempotent seed/import key); attempts raise {@link ErrorCode#CONFLICT}.</li>
 * </ul>
 *
 * <p>WHY this depends on geography's <b>public application service</b>
 * ({@link GeographyQueryService#resolveConstituency}/{@link GeographyQueryService#resolveWard}) rather than
 * geography's repositories: geography is an upstream foundation module whose entities {@link Representative}
 * legitimately FK-references (ARCHITECTURE.md §3.2, §4.3), but the FK <i>resolution</i> must pass through
 * geography's own service so the closed module boundary holds at the application layer (CLAUDE.md §8;
 * ADR-0013 — a module never reaches into a sibling's {@code domain.repository}). The references stay
 * one-directional (geography never depends on institutions).</p>
 */
@Service
@Transactional
public class InstitutionsAdminService {

    /** Sentinel "no row to exclude" id for create-time invariant pre-checks. */
    private static final long NO_EXCLUDE = -1L;

    /** Field tokens for validation errors (kept as constants to avoid literal drift — java:S1192). */
    private static final String FIELD_CONSTITUENCY_ID = "constituencyId";
    private static final String FIELD_WARD_ID = "wardId";

    /** Resource-specific i18n not-found keys (Swahili-first; see CENTRAL INTEGRATION NEEDS). */
    private static final String KEY_PARTY_NOT_FOUND = "institutions.party.notFound";
    private static final String KEY_PARLIAMENT_NOT_FOUND = "institutions.parliament.notFound";
    private static final String KEY_PARLIAMENT_ROLE_NOT_FOUND = "institutions.parliamentRole.notFound";
    private static final String KEY_REPRESENTATIVE_NOT_FOUND = "institutions.representative.notFound";

    private final PoliticalPartyRepository partyRepository;
    private final ParliamentRepository parliamentRepository;
    private final ParliamentRoleRepository parliamentRoleRepository;
    private final RepresentativeRepository representativeRepository;
    private final GeographyQueryService geographyQueryService;
    private final InstitutionsMapper mapper;

    /**
     * @param partyRepository           party persistence port.
     * @param parliamentRepository      parliament persistence port.
     * @param parliamentRoleRepository  parliament-role persistence port.
     * @param representativeRepository  representative persistence port.
     * @param geographyQueryService     geography's public service for constituency/ward FK resolution
     *                                  (ADR-0013 — never geography's repositories).
     * @param mapper                    entity→DTO mapper.
     */
    public InstitutionsAdminService(PoliticalPartyRepository partyRepository,
                                    ParliamentRepository parliamentRepository,
                                    ParliamentRoleRepository parliamentRoleRepository,
                                    RepresentativeRepository representativeRepository,
                                    GeographyQueryService geographyQueryService,
                                    InstitutionsMapper mapper) {
        this.partyRepository = partyRepository;
        this.parliamentRepository = parliamentRepository;
        this.parliamentRoleRepository = parliamentRoleRepository;
        this.representativeRepository = representativeRepository;
        this.geographyQueryService = geographyQueryService;
        this.mapper = mapper;
    }

    // =============================================================================================
    // Political party CRUD (UC-B11).
    // =============================================================================================

    /**
     * Creates a political party.
     *
     * @param dto the validated write payload.
     * @return the created party DTO.
     * @throws ApiException {@link ErrorCode#CONFLICT} if the code already exists.
     */
    public PoliticalPartyDto createParty(PartyWriteDto dto) {
        partyRepository.findByCode(dto.code()).ifPresent(p -> {
            throw new ApiException(ErrorCode.CONFLICT, dto.code());
        });
        PoliticalParty party = PoliticalParty.create();
        applyParty(party, dto, true);
        return mapper.toPartyDto(partyRepository.save(party));
    }

    /**
     * Updates a political party. The {@code code} is immutable (idempotent identity key).
     *
     * @param publicId the party's public id.
     * @param dto      the validated write payload.
     * @return the updated party DTO.
     * @throws ResourceNotFoundException if no such party.
     * @throws ApiException {@link ErrorCode#CONFLICT} if the code is changed.
     */
    public PoliticalPartyDto updateParty(UUID publicId, PartyWriteDto dto) {
        PoliticalParty party = partyRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(KEY_PARTY_NOT_FOUND, publicId));
        if (!party.getCode().equals(dto.code())) {
            throw new ApiException(ErrorCode.CONFLICT, dto.code());
        }
        applyParty(party, dto, false);
        return mapper.toPartyDto(partyRepository.save(party));
    }

    /**
     * Soft-deletes a political party.
     *
     * <p>WHY soft-delete: a party referenced by a (possibly FORMER) representative must remain
     * referentially intact for the historical record (PRD §22.6, ARCHITECTURE.md §4.2).</p>
     *
     * @param publicId the party's public id.
     * @throws ResourceNotFoundException if no such party.
     */
    public void deleteParty(UUID publicId) {
        PoliticalParty party = partyRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(KEY_PARTY_NOT_FOUND, publicId));
        party.markDeleted(currentActor());
        partyRepository.save(party);
    }

    // =============================================================================================
    // Parliament CRUD (UC-B12).
    // =============================================================================================

    /**
     * Creates a parliament term. If {@code current} is set, any prior current term of the same
     * legislature is cleared first (single-current invariant).
     *
     * @param dto the validated write payload.
     * @return the created parliament DTO.
     */
    public ParliamentDto createParliament(ParliamentWriteDto dto) {
        Parliament parliament = Parliament.create();
        Legislature legislature = parseLegislature(dto.legislature());
        if (dto.current()) {
            clearCurrentParliament(legislature, NO_EXCLUDE);
        }
        applyParliament(parliament, dto, legislature);
        return mapper.toParliamentDto(parliamentRepository.save(parliament));
    }

    /**
     * Updates a parliament term, preserving the single-current invariant.
     *
     * @param publicId the term's public id.
     * @param dto      the validated write payload.
     * @return the updated parliament DTO.
     * @throws ResourceNotFoundException if no such term.
     */
    public ParliamentDto updateParliament(UUID publicId, ParliamentWriteDto dto) {
        Parliament parliament = parliamentRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(KEY_PARLIAMENT_NOT_FOUND, publicId));
        Legislature legislature = parseLegislature(dto.legislature());
        if (dto.current()) {
            clearCurrentParliament(legislature, parliament.getId());
        }
        applyParliament(parliament, dto, legislature);
        return mapper.toParliamentDto(parliamentRepository.save(parliament));
    }

    /**
     * Soft-deletes a parliament term.
     *
     * @param publicId the term's public id.
     * @throws ResourceNotFoundException if no such term.
     */
    public void deleteParliament(UUID publicId) {
        Parliament parliament = parliamentRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(KEY_PARLIAMENT_NOT_FOUND, publicId));
        parliament.markDeleted(currentActor());
        parliamentRepository.save(parliament);
    }

    // =============================================================================================
    // Parliament role CRUD (UC-B13).
    // =============================================================================================

    /**
     * Creates a parliament role.
     *
     * @param dto the validated write payload.
     * @return the created role DTO.
     * @throws ApiException {@link ErrorCode#CONFLICT} if the code already exists.
     */
    public ParliamentRoleDto createParliamentRole(ParliamentRoleWriteDto dto) {
        parliamentRoleRepository.findByCode(dto.code()).ifPresent(r -> {
            throw new ApiException(ErrorCode.CONFLICT, dto.code());
        });
        ParliamentRole role = ParliamentRole.create();
        applyParliamentRole(role, dto, true);
        return mapper.toParliamentRoleDto(parliamentRoleRepository.save(role));
    }

    /**
     * Updates a parliament role. The {@code code} is immutable.
     *
     * @param publicId the role's public id.
     * @param dto      the validated write payload.
     * @return the updated role DTO.
     * @throws ResourceNotFoundException if no such role.
     * @throws ApiException {@link ErrorCode#CONFLICT} if the code is changed.
     */
    public ParliamentRoleDto updateParliamentRole(UUID publicId, ParliamentRoleWriteDto dto) {
        ParliamentRole role = parliamentRoleRepository.findByPublicId(publicId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(KEY_PARLIAMENT_ROLE_NOT_FOUND, publicId));
        if (!role.getCode().equals(dto.code())) {
            throw new ApiException(ErrorCode.CONFLICT, dto.code());
        }
        applyParliamentRole(role, dto, false);
        return mapper.toParliamentRoleDto(parliamentRoleRepository.save(role));
    }

    /**
     * Soft-deletes a parliament role.
     *
     * @param publicId the role's public id.
     * @throws ResourceNotFoundException if no such role.
     */
    public void deleteParliamentRole(UUID publicId) {
        ParliamentRole role = parliamentRoleRepository.findByPublicId(publicId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(KEY_PARLIAMENT_ROLE_NOT_FOUND, publicId));
        role.markDeleted(currentActor());
        parliamentRoleRepository.save(role);
    }

    // =============================================================================================
    // Representative CRUD (UC-C04, UC-C08) — the integrity-critical writes.
    // =============================================================================================

    /**
     * Creates (or links) a representative, enforcing the mandate⇄geography rule and the one-SITTING-MP
     * invariant.
     *
     * @param dto the validated write payload (geography/party/parliament referenced by public id).
     * @return the created representative DTO.
     * @throws ResourceNotFoundException if a referenced constituency/ward/party/parliament/role is absent.
     * @throws ApiException              on an invariant violation (see class Javadoc).
     */
    public RepresentativeDto createRepresentative(RepresentativeWriteDto dto) {
        Representative rep = Representative.create();
        applyRepresentative(rep, dto, NO_EXCLUDE);
        return mapper.toDto(representativeRepository.save(rep));
    }

    /**
     * Updates a representative (including a status transition such as SITTING→FORMER, UC-C08),
     * re-validating all invariants against the new state.
     *
     * @param publicId the representative's public id.
     * @param dto      the validated write payload.
     * @return the updated representative DTO.
     * @throws ResourceNotFoundException if no such representative.
     * @throws ApiException              on an invariant violation.
     */
    public RepresentativeDto updateRepresentative(UUID publicId, RepresentativeWriteDto dto) {
        Representative rep = representativeRepository.findByPublicId(publicId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(KEY_REPRESENTATIVE_NOT_FOUND, publicId));
        applyRepresentative(rep, dto, rep.getId());
        return mapper.toDto(representativeRepository.save(rep));
    }

    /**
     * Soft-deletes a representative. WHY soft-delete (never physical): the civic/accountability record of
     * who held a seat must survive (PRD §22.6); routine "removal" should normally be a SITTING→FORMER
     * transition, with delete reserved for erroneous rows.
     *
     * @param publicId the representative's public id.
     * @throws ResourceNotFoundException if no such representative.
     */
    public void deleteRepresentative(UUID publicId) {
        Representative rep = representativeRepository.findByPublicId(publicId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(KEY_REPRESENTATIVE_NOT_FOUND, publicId));
        rep.markDeleted(currentActor());
        representativeRepository.save(rep);
    }

    // =============================================================================================
    // Internals — field application + invariant enforcement.
    // =============================================================================================

    private void applyParty(PoliticalParty party, PartyWriteDto dto, boolean isCreate) {
        if (isCreate) {
            party.assignCode(dto.code());
        }
        PartyStatus status = StringUtils.hasText(dto.status())
                ? parseEnum(PartyStatus.class, dto.status()) : PartyStatus.ACTIVE;
        party.applyDetails(dto.name(), dto.abbreviation(), dto.ideology(), dto.foundedYear(),
                dto.logoRef(), status, dto.contacts());
    }

    private void applyParliament(Parliament parliament, ParliamentWriteDto dto, Legislature legislature) {
        parliament.applyDetails(dto.termNumber(), dto.name(), legislature,
                dto.startDate(), dto.endDate(), dto.current());
    }

    private void applyParliamentRole(ParliamentRole role, ParliamentRoleWriteDto dto, boolean isCreate) {
        if (isCreate) {
            role.assignCode(dto.code());
        }
        role.applyDetails(dto.name(), dto.description());
    }

    /**
     * Applies a representative write payload, resolving cross-module references and enforcing every
     * representative invariant before mutation is accepted.
     */
    private void applyRepresentative(Representative rep, RepresentativeWriteDto dto, long excludeId) {
        RepresentativeType type = parseEnum(RepresentativeType.class, dto.type());
        Mandate mandate = parseEnum(Mandate.class, dto.mandate());
        Legislature legislature = parseLegislature(dto.legislature());
        RepresentativeStatus status = StringUtils.hasText(dto.status())
                ? parseEnum(RepresentativeStatus.class, dto.status())
                : RepresentativeStatus.PENDING_VERIFICATION;

        // Resolve the geographic seat per the mandate, enforcing the mandate⇄geography coherence rule.
        Constituency constituency = null;
        Location ward = null;
        switch (mandate) {
            case CONSTITUENCY -> {
                requirePresent(dto.constituencyId(), FIELD_CONSTITUENCY_ID);
                requireAbsent(dto.wardId(), FIELD_WARD_ID);
                constituency = resolveConstituency(dto.constituencyId());
            }
            case COUNCILLOR_WARD -> {
                requirePresent(dto.wardId(), FIELD_WARD_ID);
                requireAbsent(dto.constituencyId(), FIELD_CONSTITUENCY_ID);
                ward = resolveWard(dto.wardId());
            }
            case SPECIAL_SEATS, NOMINATED -> {
                // Non-geographic mandate: both geographic FKs MUST be null (Viti Maalum / nominated).
                requireAbsent(dto.constituencyId(), FIELD_CONSTITUENCY_ID);
                requireAbsent(dto.wardId(), FIELD_WARD_ID);
            }
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED, "mandate");
        }

        // One-SITTING-MP-per-constituency pre-check (the DB index is the race backstop).
        if (status == RepresentativeStatus.SITTING && constituency != null
                && representativeRepository.existsSittingByConstituency(constituency.getId(), excludeId)) {
            throw new ApiException(ErrorCode.CONFLICT, constituency.getName());
        }

        rep.applyDetails(
                dto.profileId(), type, mandate, constituency, ward,
                dto.partyId() != null ? resolveParty(dto.partyId()) : null,
                legislature,
                dto.parliamentId() != null ? resolveParliament(dto.parliamentId()) : null,
                dto.parliamentRoleId() != null ? resolveParliamentRole(dto.parliamentRoleId()) : null,
                status, dto.electedAt(), dto.bio());
    }

    /** Clears the {@code current} flag of the existing current term of a legislature (excluding one id). */
    private void clearCurrentParliament(Legislature legislature, long excludeId) {
        parliamentRepository.findByLegislatureAndCurrentTrue(legislature).ifPresent(existing -> {
            if (!existing.getId().equals(excludeId)) {
                existing.clearCurrent();
                parliamentRepository.save(existing);
            }
        });
    }

    /** Resolves a constituency FK through geography's public service (never its repository — ADR-0013). */
    private Constituency resolveConstituency(UUID publicId) {
        return geographyQueryService.resolveConstituency(publicId);
    }

    /**
     * Resolves a ward FK through geography's public service, which also enforces the minimum-pin-granularity
     * (WARD) rule (PRD §9.0) — so this service no longer touches geography's {@code LocationType}/repository.
     */
    private Location resolveWard(UUID publicId) {
        return geographyQueryService.resolveWard(publicId);
    }

    private PoliticalParty resolveParty(UUID publicId) {
        return partyRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(KEY_PARTY_NOT_FOUND, publicId));
    }

    private Parliament resolveParliament(UUID publicId) {
        return parliamentRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(KEY_PARLIAMENT_NOT_FOUND, publicId));
    }

    private ParliamentRole resolveParliamentRole(UUID publicId) {
        return parliamentRoleRepository.findByPublicId(publicId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(KEY_PARLIAMENT_ROLE_NOT_FOUND, publicId));
    }

    private Legislature parseLegislature(String value) {
        return StringUtils.hasText(value)
                ? parseEnum(Legislature.class, value) : Legislature.UNION_PARLIAMENT;
    }

    private static void requirePresent(UUID id, String field) {
        if (id == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, field);
        }
    }

    private static void requireAbsent(UUID id, String field) {
        if (id != null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, field);
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, value);
        }
    }

    /** @return the authenticated admin actor's public id for audit attribution (soft-delete). */
    private UUID currentActor() {
        return com.taarifu.common.security.CurrentUser.current()
                .map(com.taarifu.common.security.CurrentUser::publicId)
                .orElse(null);
    }
}
