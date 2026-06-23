package com.taarifu.communications.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.communications.api.dto.NotificationPreferenceDto;
import com.taarifu.communications.api.dto.NotificationPreferenceRequest;
import com.taarifu.communications.application.mapper.CommunicationsMapper;
import com.taarifu.communications.application.service.NotificationPreferenceService;
import com.taarifu.communications.domain.model.NotificationPreference;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Citizen notification-preference management (PRD §13, UC-G08, M5).
 *
 * <p>Responsibility: the thin REST surface over {@link NotificationPreferenceService}. It validates
 * input, delegates, and wraps results in the single {@link ApiResponse} envelope — no business logic, no
 * {@code @Transactional} (CLAUDE.md §8).</p>
 *
 * <p><b>Authorization</b>: a registered citizen manages only their <b>own</b> preferences
 * ({@code @PreAuthorize("isAuthenticated()")}). The service rejects disabling an "always-on" type
 * (SYSTEM/MODERATION_OUTCOME) so a citizen cannot silence security/moderation notices (PRD §13).</p>
 */
@RestController
@RequestMapping("/notification-preferences")
@Tag(name = "Notification preferences", description = "Per-channel/type opt-in, quiet hours, language.")
public class NotificationPreferenceController {

    private final NotificationPreferenceService preferenceService;
    private final CommunicationsMapper mapper;
    private final ResponseFactory responses;

    /**
     * @param preferenceService preference orchestration.
     * @param mapper            entity→DTO mapper.
     * @param responses         envelope builder.
     */
    public NotificationPreferenceController(NotificationPreferenceService preferenceService,
                                            CommunicationsMapper mapper,
                                            ResponseFactory responses) {
        this.preferenceService = preferenceService;
        this.mapper = mapper;
        this.responses = responses;
    }

    /**
     * Lists the caller's notification preferences.
     *
     * @return {@code 200} + the caller's {@link NotificationPreferenceDto} list (may be empty → defaults).
     */
    @GetMapping("/mine")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List my notification preferences")
    public ApiResponse<List<NotificationPreferenceDto>> listMine() {
        List<NotificationPreferenceDto> prefs =
                preferenceService.listMyPreferences(CurrentUser.requirePublicId()).stream()
                        .map(mapper::toPreferenceDto)
                        .toList();
        return responses.ok(prefs);
    }

    /**
     * Upserts a single preference for the caller.
     *
     * @param request the validated (type, channel) preference.
     * @return {@code 200} + the upserted {@link NotificationPreferenceDto}.
     */
    @PutMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Set a notification preference",
            description = "Upsert by (type, channel). Cannot disable always-on types.")
    public ApiResponse<NotificationPreferenceDto> upsert(
            @Valid @RequestBody NotificationPreferenceRequest request) {
        NotificationPreference pref = preferenceService.upsert(
                CurrentUser.requirePublicId(),
                request.type(), request.channel(), request.enabled(),
                request.quietFrom(), request.quietTo(), request.language());
        return responses.ok(mapper.toPreferenceDto(pref));
    }
}
