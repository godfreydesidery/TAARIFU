package com.taarifu.accountability.application.service;

import com.taarifu.accountability.api.dto.AttendanceDto;
import com.taarifu.accountability.api.dto.ContributionDto;
import com.taarifu.accountability.api.dto.CreateAttendanceDto;
import com.taarifu.accountability.api.dto.CreateContributionDto;
import com.taarifu.accountability.api.dto.CreatePromiseDto;
import com.taarifu.accountability.api.dto.PromiseDto;
import com.taarifu.accountability.api.dto.UpdatePromiseStatusDto;
import com.taarifu.accountability.application.mapper.AccountabilityMapper;
import com.taarifu.accountability.domain.model.Attendance;
import com.taarifu.accountability.domain.model.Promise;
import com.taarifu.accountability.domain.model.PromiseStatusEntry;
import com.taarifu.accountability.domain.model.RepresentativeContribution;
import com.taarifu.accountability.domain.model.enums.PromiseStatus;
import com.taarifu.accountability.domain.repository.AttendanceRepository;
import com.taarifu.accountability.domain.repository.PromiseRepository;
import com.taarifu.accountability.domain.repository.PromiseStatusEntryRepository;
import com.taarifu.accountability.domain.repository.RepresentativeContributionRepository;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.institutions.api.RepresentativeQueryApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Curated-authoring application service for accountability records (PRD §10 Epic M6; D-Q4 curated
 * authorship; EI-11).
 *
 * <p>Responsibility: creates and maintains the <b>non-binding</b> accountability data —
 * contributions, attendance, and promises. Per D-Q4 these are curated by the platform/partners, so the
 * authoring endpoints are gated to {@code ROLE_ADMIN} (authorised-author roles widen this at wiring).
 * Every record carries provenance so it is shown attributably (EI-11).</p>
 *
 * <p>WHY this is separate from {@code RatingService}: ratings are a binding civic action behind the
 * integrity fence (tier + one-per-person + no-self, no token balance — §23/D18); curation is ordinary
 * admin authoring. Keeping them apart prevents the fence and the authoring path from ever bleeding into
 * one another (SOLID single-responsibility).</p>
 *
 * <h3>Referenced-representative existence (the integrity guard this service enforces)</h3>
 * <p>Every curated record is <i>about</i> a representative, referenced by the opaque {@code representativeId}
 * public id — never an FK, because accountability must not import institutions' internals (ADR-0013,
 * ARCHITECTURE §3.2). Before persisting a contribution, attendance row, or promise, this service confirms
 * the referent actually exists via institutions' published {@link RepresentativeQueryApi#exists(UUID)}
 * port, rejecting an unknown id with a localised {@code NOT_FOUND}. WHY: without this check a curator could
 * create accountability data attributed to a non-existent representative — orphaned records pointing at
 * nobody, exactly the integrity hole this wiring closes. The cross-module call is through the published
 * api port only; this service never reaches into institutions' {@code domain}/{@code infrastructure}.</p>
 *
 * <p>{@code linkedProjectIds} on a promise are still accepted as opaque public ids and not yet validated:
 * there is no projects module/published port to validate them against — distinct from the
 * representative-existence guard, which is now wired.
 * // PHASE-3: needs the projects module's published existence query (e.g. {@code projects.api.ProjectQueryApi
 * .exists(UUID)}); this service's referenced-existence guard pattern — already used for the representative via
 * {@code RepresentativeQueryApi.exists} — is the ready receiver to validate each linked project id once that
 * module ships its api port.</p>
 */
@Service
@Transactional
public class CurationService {

    private final RepresentativeContributionRepository contributionRepository;
    private final AttendanceRepository attendanceRepository;
    private final PromiseRepository promiseRepository;
    private final PromiseStatusEntryRepository promiseStatusEntryRepository;
    private final AccountabilityMapper mapper;
    private final RepresentativeQueryApi representativeQueryApi;

    /**
     * @param contributionRepository       contribution persistence port.
     * @param attendanceRepository         attendance persistence port.
     * @param promiseRepository            promise persistence port.
     * @param promiseStatusEntryRepository append-only promise status-timeline persistence port.
     * @param mapper                       entity → DTO mapper.
     * @param representativeQueryApi       institutions' published port used to confirm the referenced
     *                                     representative exists before persisting any curated record (ADR-0013).
     */
    public CurationService(RepresentativeContributionRepository contributionRepository,
                           AttendanceRepository attendanceRepository,
                           PromiseRepository promiseRepository,
                           PromiseStatusEntryRepository promiseStatusEntryRepository,
                           AccountabilityMapper mapper,
                           RepresentativeQueryApi representativeQueryApi) {
        this.contributionRepository = contributionRepository;
        this.attendanceRepository = attendanceRepository;
        this.promiseRepository = promiseRepository;
        this.promiseStatusEntryRepository = promiseStatusEntryRepository;
        this.mapper = mapper;
        this.representativeQueryApi = representativeQueryApi;
    }

    /**
     * Creates a curated representative contribution.
     *
     * @param request the validated create request.
     * @return the created {@link ContributionDto}.
     * @throws ResourceNotFoundException if the referenced representative does not exist (no orphaned record).
     */
    public ContributionDto createContribution(CreateContributionDto request) {
        requireRepresentativeExists(request.representativeId());
        RepresentativeContribution saved = contributionRepository.save(
                RepresentativeContribution.create(
                        request.representativeId(), request.type(), request.title(), request.summary(),
                        request.occurredOn(), request.parliamentSession(), request.sourceUrl(),
                        request.attachmentRefs()));
        return mapper.toContributionDto(saved);
    }

    /**
     * Records an attendance row for a (representative, session).
     *
     * @param request the validated create request.
     * @return the created {@link AttendanceDto}.
     * @throws ResourceNotFoundException if the referenced representative does not exist (no orphaned record).
     * @throws ApiException {@link ErrorCode#CONFLICT} if a row already exists for that (rep, session)
     *                      (one authoritative row per session — caught before the DB unique to give a
     *                      clean, localised 409).
     */
    public AttendanceDto recordAttendance(CreateAttendanceDto request) {
        requireRepresentativeExists(request.representativeId());
        attendanceRepository.findByRepresentativeIdAndSessionRef(
                        request.representativeId(), request.sessionRef())
                .ifPresent(existing -> {
                    throw new ApiException(ErrorCode.CONFLICT);
                });
        Attendance saved = attendanceRepository.save(Attendance.create(
                request.representativeId(), request.sessionRef(), request.present()));
        return mapper.toAttendanceDto(saved);
    }

    /**
     * Creates a curated promise.
     *
     * @param request the validated create request.
     * @return the created {@link PromiseDto}.
     * @throws ResourceNotFoundException if the referenced representative does not exist (no orphaned record).
     */
    public PromiseDto createPromise(CreatePromiseDto request) {
        requireRepresentativeExists(request.representativeId());
        Promise saved = promiseRepository.save(Promise.create(
                request.representativeId(), request.text(), request.madeAt(), request.status(),
                request.evidenceRef(), request.linkedProjectIds()));
        // Open the citizen-visible timeline with the promise's INITIAL state (US-6.3). Promise.create defaults
        // a null status to MADE, so read it back from the saved entity (never the raw, possibly-null request)
        // — the first timeline entry must mirror the persisted status exactly.
        promiseStatusEntryRepository.save(PromiseStatusEntry.record(
                saved, saved.getStatus(), saved.getEvidenceRef(), null));
        return mapper.toPromiseDto(saved);
    }

    /**
     * Advances a promise's tracked status (curated, evidence-backed — D-Q4).
     *
     * @param promisePublicId the promise's public id.
     * @param request         the validated status-update request.
     * @return the updated {@link PromiseDto}.
     * @throws ResourceNotFoundException if no live promise has that id.
     */
    public PromiseDto updatePromiseStatus(UUID promisePublicId, UpdatePromiseStatusDto request) {
        Promise promise = promiseRepository.findByPublicId(promisePublicId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "accountability.promise.notFound", promisePublicId));
        PromiseStatus previous = promise.getStatus();
        promise.updateStatus(request.status(), request.evidenceRef());
        Promise saved = promiseRepository.save(promise);
        // Append a timeline entry ONLY on a genuine transition (US-6.3 — the timeline is the dated provenance
        // of how a promise MOVED). A no-op re-statement (e.g. attaching new evidence to the same status)
        // updates the promise but adds no spurious "moved to X" row; an evidence/note-only correction without
        // a status change is intentionally NOT a timeline event. WHY append (never edit a prior entry): the
        // timeline is an append-only accountability record — rewriting it would let a status judgement be
        // silently changed (neutrality, PRD §10).
        if (request.status() != null && request.status() != previous) {
            promiseStatusEntryRepository.save(PromiseStatusEntry.record(
                    saved, saved.getStatus(), saved.getEvidenceRef(), request.note()));
        }
        return mapper.toPromiseDto(saved);
    }

    /**
     * Confirms the referenced representative exists before any curated record is persisted, via
     * institutions' published {@link RepresentativeQueryApi#exists(UUID)} port (ADR-0013 — cross-module
     * through the api surface only). WHY a guard, not a trust: {@code representativeId} arrives as an
     * opaque public id with no FK to enforce it (the boundary rule forbids accountability importing
     * institutions' entities), so this is the only thing standing between a typo'd/forged id and an
     * accountability record attributed to a non-existent representative. A missing referent is a localised
     * {@code NOT_FOUND} ({@code institutions.representative.notFound} → "Mwakilishi hakupatikana"), the
     * same message institutions itself uses, so the citizen/curator sees a consistent Swahili-first error.
     *
     * @param representativeId the referenced representative's public id.
     * @throws ResourceNotFoundException if no live representative has that public id.
     */
    private void requireRepresentativeExists(UUID representativeId) {
        if (!representativeQueryApi.exists(representativeId)) {
            throw new ResourceNotFoundException(
                    "institutions.representative.notFound", representativeId);
        }
    }
}
