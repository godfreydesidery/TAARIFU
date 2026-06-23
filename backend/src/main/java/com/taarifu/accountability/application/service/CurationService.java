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
import com.taarifu.accountability.domain.model.RepresentativeContribution;
import com.taarifu.accountability.domain.repository.AttendanceRepository;
import com.taarifu.accountability.domain.repository.PromiseRepository;
import com.taarifu.accountability.domain.repository.RepresentativeContributionRepository;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.error.ResourceNotFoundException;
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
 * <p>// TODO(wiring): {@code representativeId}/{@code linkedProjectIds} are accepted as opaque public ids;
 * validate them against the institutions/projects modules' public APIs once those seams exist.</p>
 */
@Service
@Transactional
public class CurationService {

    private final RepresentativeContributionRepository contributionRepository;
    private final AttendanceRepository attendanceRepository;
    private final PromiseRepository promiseRepository;
    private final AccountabilityMapper mapper;

    /**
     * @param contributionRepository contribution persistence port.
     * @param attendanceRepository   attendance persistence port.
     * @param promiseRepository      promise persistence port.
     * @param mapper                 entity → DTO mapper.
     */
    public CurationService(RepresentativeContributionRepository contributionRepository,
                           AttendanceRepository attendanceRepository,
                           PromiseRepository promiseRepository,
                           AccountabilityMapper mapper) {
        this.contributionRepository = contributionRepository;
        this.attendanceRepository = attendanceRepository;
        this.promiseRepository = promiseRepository;
        this.mapper = mapper;
    }

    /**
     * Creates a curated representative contribution.
     *
     * @param request the validated create request.
     * @return the created {@link ContributionDto}.
     */
    public ContributionDto createContribution(CreateContributionDto request) {
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
     * @throws ApiException {@link ErrorCode#CONFLICT} if a row already exists for that (rep, session)
     *                      (one authoritative row per session — caught before the DB unique to give a
     *                      clean, localised 409).
     */
    public AttendanceDto recordAttendance(CreateAttendanceDto request) {
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
     */
    public PromiseDto createPromise(CreatePromiseDto request) {
        Promise saved = promiseRepository.save(Promise.create(
                request.representativeId(), request.text(), request.madeAt(), request.status(),
                request.evidenceRef(), request.linkedProjectIds()));
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
        promise.updateStatus(request.status(), request.evidenceRef());
        return mapper.toPromiseDto(promiseRepository.save(promise));
    }
}
