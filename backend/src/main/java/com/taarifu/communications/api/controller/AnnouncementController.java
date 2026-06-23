package com.taarifu.communications.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.pagination.PageMapper;
import com.taarifu.common.pagination.PageRequestFactory;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.communications.api.dto.AnnouncementDto;
import com.taarifu.communications.api.dto.PublishAnnouncementRequest;
import com.taarifu.communications.application.mapper.CommunicationsMapper;
import com.taarifu.communications.application.service.AnnouncementService;
import com.taarifu.communications.domain.model.Announcement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Author/official/admin endpoints to publish and manage announcements (PRD §12 UC-G01/G02, M4, US-4.1).
 *
 * <p>Responsibility: the thin REST surface over {@link AnnouncementService}. It validates input,
 * delegates, and wraps results in the single {@link ApiResponse} envelope — no business logic, no
 * {@code @Transactional} (CLAUDE.md §8, ARCHITECTURE §3.3).</p>
 *
 * <p><b>Authorization</b> (deny-by-default, method security): publishing requires the caller to hold an
 * authoring role — {@code REPRESENTATIVE}, {@code RESPONDER_AGENT}/{@code RESPONDER_ADMIN} (official),
 * {@code ORGANIZATION_ADMIN}, {@code ADMIN}, or {@code ROOT} (PRD §6, §7). The
 * <b>moderation-hold for new authors</b> (US-4.1) is computed here from the caller's roles: a privileged
 * author ({@code ADMIN}/{@code ROOT}/established official/representative) is trusted and publishes
 * immediately; any other authorised author is <b>held for moderation</b>. This is a deliberately
 * conservative default — trust is widened later by author reputation (TODO(wiring): reputation source).</p>
 *
 * <p>WHY the trust decision lives in the controller (not the service): it is derived from the
 * authenticated principal's roles, which the controller already holds; the service stays free of identity
 * internals and simply honours the {@code trustedAuthor} flag (ARCHITECTURE §3.2).</p>
 *
 * <p><b>Public read</b>: {@code GET /announcements/&#123;publicId&#125;} is {@code permitAll()} — a
 * published announcement is public civic data any guest may read (PRD §22.6, AC-T0). Its visibility is
 * enforced entirely service-side ({@link AnnouncementService#getPublicDetail(java.util.UUID)}): only a
 * {@code PUBLISHED}, in-window announcement is returned; everything else 404s so nothing in flight leaks.
 * The public URL pattern must be centrally allow-listed — see CENTRAL INTEGRATION NEEDS.</p>
 */
@RestController
@RequestMapping("/announcements")
@Tag(name = "Announcements", description = "Publish and manage geo-targeted civic announcements.")
public class AnnouncementController {

    private final AnnouncementService announcementService;
    private final CommunicationsMapper mapper;
    private final ResponseFactory responses;
    private final PageRequestFactory pageRequests;
    private final PageMapper pageMapper;

    /**
     * @param announcementService publish/manage orchestration.
     * @param mapper              entity→DTO mapper.
     * @param responses           envelope builder.
     * @param pageRequests        safe {@link org.springframework.data.domain.Pageable} factory.
     * @param pageMapper          {@code Page}→{@code PageMeta} adapter.
     */
    public AnnouncementController(AnnouncementService announcementService,
                                  CommunicationsMapper mapper,
                                  ResponseFactory responses,
                                  PageRequestFactory pageRequests,
                                  PageMapper pageMapper) {
        this.announcementService = announcementService;
        this.mapper = mapper;
        this.responses = responses;
        this.pageRequests = pageRequests;
        this.pageMapper = pageMapper;
    }

    /**
     * Publishes (or, for a new author, queues for moderation) an announcement.
     *
     * @param request the validated announcement composition.
     * @return {@code 201} + the created {@link AnnouncementDto} (PUBLISHED/SCHEDULED if trusted, else
     *         DRAFT held for moderation).
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('REPRESENTATIVE','RESPONDER_AGENT','RESPONDER_ADMIN',"
            + "'ORG_ADMIN','ADMIN','ROOT')")
    @Operation(summary = "Publish an announcement",
            description = "Author/official/admin only. New authors are held for moderation (US-4.1).")
    public ResponseEntity<ApiResponse<AnnouncementDto>> publish(
            @Valid @RequestBody PublishAnnouncementRequest request) {
        boolean trusted = callerIsTrustedAuthor();
        Announcement saved = announcementService.publish(
                CurrentUser.requirePublicId(),
                trusted,
                request.title(), request.bodySw(), request.bodyEn(),
                request.areaIds(), request.categoryId(), request.audienceRole(),
                request.channels(), request.attachmentRefs(),
                request.publishAt(), request.expireAt());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responses.ok(mapper.toAnnouncementDto(saved)));
    }

    /**
     * Lists the caller's own authored announcements (any status), paged — the author management view.
     *
     * @param page zero-based page index.
     * @param size page size (capped at 100).
     * @param sort sort expression {@code field,asc|desc}.
     * @return a paged envelope of {@link AnnouncementDto}.
     */
    @GetMapping("/mine")
    @PreAuthorize("hasAnyRole('REPRESENTATIVE','RESPONDER_AGENT','RESPONDER_ADMIN',"
            + "'ORG_ADMIN','ADMIN','ROOT')")
    @Operation(summary = "List my announcements")
    public ApiResponse<List<AnnouncementDto>> listMine(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        var pageable = pageRequests.of(page, size, sort);
        var result = announcementService /* read */
                .listMine(CurrentUser.requirePublicId(), pageable)
                .map(mapper::toAnnouncementDto);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }

    /**
     * Fetches a single <b>published, citizen-visible</b> announcement by its public id — the public
     * feed-detail read that lets the mobile/web feed-detail screen show the full body (title, full SW/EN
     * body, author ref, published timestamp, audience/area). Closes the mobile agent's gap: previously only
     * the lean {@link com.taarifu.communications.api.dto.FeedItemDto feed item} and the author-scoped
     * {@code /announcements/mine} existed.
     *
     * <p><b>Authorization</b>: {@code permitAll()} — a published announcement is part of the <b>public
     * civic graph</b> any guest may read (PRD §22.6, AC-T0 "view … announcements", SR-3 "active
     * announcements"). All visibility is enforced <b>service-side</b>: {@link
     * AnnouncementService#getPublicDetail(UUID)} returns the announcement only when it is {@code PUBLISHED}
     * and within its publish/expiry window, and otherwise throws {@code NOT_FOUND} — so a draft, scheduled,
     * expired, moderation-held, or soft-deleted announcement is <b>never leaked</b> (it 404s,
     * indistinguishable from a non-existent id, avoiding an enumeration vector — PRD §18).</p>
     *
     * <p>WHY the public-read URL needs central registration: like {@code GET /petitions/{id}}, this is a
     * GET that must be added to the security allow-list so it is reachable unauthenticated — flagged under
     * <b>CENTRAL INTEGRATION NEEDS</b> (this module must not edit {@code SecurityConfig}). Until then the
     * {@code @PreAuthorize("permitAll()")} permits the handler, but the URL filter still requires the
     * central pattern registration to be reachable by a guest.</p>
     *
     * @param publicId the announcement's public id.
     * @return {@code 200} + the full {@link AnnouncementDto}, or {@code 404} if not publicly visible.
     */
    @GetMapping("/{publicId}")
    @PreAuthorize("permitAll()")
    @Operation(summary = "Get a published announcement by id",
            description = "Public read. Only PUBLISHED, in-window announcements are returned; "
                    + "drafts/scheduled/expired/held → 404 (never leaked).")
    public ApiResponse<AnnouncementDto> get(@PathVariable UUID publicId) {
        Announcement announcement = announcementService.getPublicDetail(publicId);
        return responses.ok(mapper.toAnnouncementDto(announcement));
    }

    /**
     * Derives "trusted author" from the caller's roles: ADMIN/ROOT and established official/representative
     * roles publish immediately; any other authorised author is held for moderation (US-4.1).
     *
     * <p>WHY a conservative default (hold unless clearly privileged): the cold-start integrity risk is
     * astroturfed/abusive announcements (PRD §28); holding new authors until a moderator clears them is
     * the safe default the PRD mandates for new authors.</p>
     */
    private boolean callerIsTrustedAuthor() {
        List<String> roles = CurrentUser.current().map(CurrentUser::roles).orElse(List.of());
        return roles.contains("ADMIN") || roles.contains("ROOT")
                || roles.contains("RESPONDER_ADMIN") || roles.contains("REPRESENTATIVE");
    }
}
