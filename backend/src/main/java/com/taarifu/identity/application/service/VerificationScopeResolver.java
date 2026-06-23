package com.taarifu.identity.application.service;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Tiny method-security helper resolving a verification request's <b>subject</b> and <b>subject ward</b>
 * from its public id, for use inside {@code @PreAuthorize} expressions (VERIFICATION-DESIGN §5).
 *
 * <p>Registered as the bean {@code verificationScope} so the Moderator endpoints can write:
 * {@code @PreAuthorize("@taarifuAuthz.canActOnArea(@verificationScope.wardOf(#publicId)) and
 * @taarifuAuthz.isNotSelf(@verificationScope.subjectOf(#publicId))")}.</p>
 *
 * <p>WHY a separate bean (not the review service itself): keeping the SpEL-referenced resolvers off the
 * secured service avoids a self-referential method-security evaluation and keeps the {@code @PreAuthorize}
 * inputs free of business logic (clean boundaries). It delegates to {@link VerificationReviewService}'s
 * read-only resolvers. Returning {@code null} (request not found) makes the scope check deny-by-default.</p>
 */
@Component("verificationScope")
public class VerificationScopeResolver {

    private final VerificationReviewService reviewService;

    /**
     * @param reviewService the service owning the read-only subject/ward resolvers.
     */
    public VerificationScopeResolver(VerificationReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * @param verificationPublicId the request public id (from the path variable).
     * @return the subject's electoral/primary ward public id, or {@code null} if none/not found
     *         (→ {@code canActOnArea(null)} denies, deny-by-default).
     */
    public UUID wardOf(UUID verificationPublicId) {
        return reviewService.wardOf(verificationPublicId).orElse(null);
    }

    /**
     * @param verificationPublicId the request public id.
     * @return the subject account's public id, or {@code null} if not found (→ {@code isNotSelf(null)}
     *         denies, deny-by-default).
     */
    public UUID subjectOf(UUID verificationPublicId) {
        return reviewService.subjectOf(verificationPublicId).orElse(null);
    }
}
