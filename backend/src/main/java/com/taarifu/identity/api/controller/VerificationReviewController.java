package com.taarifu.identity.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.identity.api.dto.ApproveVerificationDto;
import com.taarifu.identity.api.dto.RejectVerificationDto;
import com.taarifu.identity.api.dto.VerificationQueueItemDto;
import com.taarifu.identity.application.service.VerificationReviewService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Moderator verification queue + approve/reject — <b>the first scoped staff endpoint</b> (Flow 3,
 * VERIFICATION-DESIGN §5, §9.4). It is where every guard from the auth increment is exercised together
 * for the first time: staff <b>role</b> + N-4 <b>staff TOTP</b> + area <b>scope</b> + <b>conflict of
 * interest</b>.
 *
 * <p>Method security (deny-by-default, defence in depth):</p>
 * <ul>
 *   <li>{@code hasRole('MODERATOR')} — RBAC.</li>
 *   <li>{@code @mfa.isStaffMfaSatisfied()} — N-4: the caller's session must have completed a TOTP step
 *       and the account must hold a live staff role (a non-MFA / citizen-only / un-enrolled session is
 *       refused even if it somehow carries a staff authority).</li>
 *   <li>{@code @taarifuAuthz.canActOnArea(@verificationScope.wardOf(#publicId))} — MF-3 area scope.</li>
 *   <li>{@code @taarifuAuthz.isNotSelf(@verificationScope.subjectOf(#publicId))} — D16: a Moderator cannot
 *       decide their <b>own</b> verification.</li>
 * </ul>
 *
 * <p>No business logic/transaction here — the service owns the decision, the tier recompute, the
 * authoritative-electoral set, and the audit (D13/D16/L-1).</p>
 */
@RestController
@RequestMapping("/moderation/verifications")
public class VerificationReviewController {

    private final VerificationReviewService reviewService;
    private final ResponseFactory responses;

    /**
     * @param reviewService the operator decision service.
     * @param responses     envelope builder.
     */
    public VerificationReviewController(VerificationReviewService reviewService, ResponseFactory responses) {
        this.reviewService = reviewService;
        this.responses = responses;
    }

    /**
     * Lists the PENDING ID queue, scoped to the caller's area (MF-3). Requires a TOTP-satisfied staff
     * session (N-4).
     *
     * @return {@code 200} + the scoped queue items.
     */
    @GetMapping
    @PreAuthorize("hasRole('MODERATOR') and @mfa.isStaffMfaSatisfied()")
    public ResponseEntity<ApiResponse<List<VerificationQueueItemDto>>> queue() {
        List<VerificationQueueItemDto> items = reviewService.listQueue().stream()
                .map(i -> new VerificationQueueItemDto(
                        i.verificationPublicId(), i.subjectPublicId(), i.idType(),
                        i.submittedAt(), i.evidenceRef()))
                .toList();
        return ResponseEntity.ok(responses.ok(items));
    }

    /**
     * Approves a PENDING ID request → the subject becomes <b>live</b> T3; a voter ID sets the authoritative
     * electoral location (D13). Guarded by role + staff-MFA + area scope + {@code isNotSelf} (D16).
     *
     * @param publicId the request to approve.
     * @param request  the optional registered ward (voter ID) + note.
     * @return {@code 200} + the new status and the subject's resulting tier.
     */
    @PostMapping("/{publicId}/approve")
    @PreAuthorize("hasRole('MODERATOR') and @mfa.isStaffMfaSatisfied() "
            + "and @taarifuAuthz.canActOnArea(@verificationScope.wardOf(#publicId)) "
            + "and @taarifuAuthz.isNotSelf(@verificationScope.subjectOf(#publicId))")
    public ResponseEntity<ApiResponse<Map<String, String>>> approve(
            @PathVariable UUID publicId, @Valid @RequestBody(required = false) ApproveVerificationDto request) {
        UUID registeredWard = request == null ? null : request.registeredWardPublicId();
        String note = request == null ? null : request.note();
        VerificationReviewService.DecisionResult result =
                reviewService.approve(CurrentUser.requirePublicId(), publicId, registeredWard, note);
        return ResponseEntity.ok(responses.ok(Map.of(
                "status", result.status().name(),
                "subjectTier", result.subjectTier().name())));
    }

    /**
     * Rejects a PENDING ID request with a reason code; the subject's tier is untouched. Same guards.
     *
     * @param publicId the request to reject.
     * @param request  the reason code + optional note.
     * @return {@code 200} + the new status.
     */
    @PostMapping("/{publicId}/reject")
    @PreAuthorize("hasRole('MODERATOR') and @mfa.isStaffMfaSatisfied() "
            + "and @taarifuAuthz.canActOnArea(@verificationScope.wardOf(#publicId)) "
            + "and @taarifuAuthz.isNotSelf(@verificationScope.subjectOf(#publicId))")
    public ResponseEntity<ApiResponse<Map<String, String>>> reject(
            @PathVariable UUID publicId, @Valid @RequestBody RejectVerificationDto request) {
        VerificationReviewService.DecisionResult result = reviewService.reject(
                CurrentUser.requirePublicId(), publicId, request.reasonCode(), request.note());
        return ResponseEntity.ok(responses.ok(Map.of("status", result.status().name())));
    }
}
