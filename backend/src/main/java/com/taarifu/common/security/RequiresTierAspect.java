package com.taarifu.common.security;

import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditOutcome;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The interceptor that enforces {@link RequiresTier} against the <b>live</b> trust tier — MF-2
 * (AUTH-DESIGN §7.2, ADR-0011 §3).
 *
 * <p>Responsibility: wraps every method annotated {@code @RequiresTier("Tn")}, reads the caller's
 * {@code publicId} from the security context, asks {@link TierResolver} for the <b>current</b> tier
 * computed from the database, and compares it to the annotation's minimum. The JWT {@code trustTier}
 * claim is <b>never</b> consulted here — a forged or stale-after-downgrade claim cannot escalate
 * because the DB is the only authority (the keystone regression test asserts exactly this).</p>
 *
 * <p>On denial it throws {@link ApiException} with {@link ErrorCode#TIER_TOO_LOW} (→ 403 envelope with
 * the machine code and a localised "verify your identity" message) and writes an
 * {@link AuditEventType#AUTHZ_TIER_DENIED} audit event (required-vs-live tier as the reason code,
 * never PII).</p>
 *
 * <p>WHY an AspectJ {@code @Around} (not a {@code @PreAuthorize} string): tier resolution is a live DB
 * read, not a static SpEL predicate; folding it into a method-security expression would re-trust the
 * claim. The aspect keeps the live-resolve mandatory and greppable, and pairs with the integrity fence
 * (binding actions = tier + electoral scope + one-per-person, never a token balance — D18).</p>
 */
@Aspect
@Component
public class RequiresTierAspect {

    private final TierResolver tierResolver;
    private final AuditEventService audit;

    /**
     * @param tierResolver the live-tier authority (DB-backed; never the token claim — MF-2).
     * @param audit        the append-only audit writer for denial evidence (L-1).
     */
    public RequiresTierAspect(TierResolver tierResolver, AuditEventService audit) {
        this.tierResolver = tierResolver;
        this.audit = audit;
    }

    /**
     * Enforces the minimum tier before the guarded method runs.
     *
     * @param joinPoint the intercepted invocation.
     * @return the method's result if the tier is sufficient.
     * @throws Throwable             the method's own exceptions if it proceeds.
     * @throws ApiException          {@link ErrorCode#UNAUTHENTICATED} if no principal,
     *                               {@link ErrorCode#TIER_TOO_LOW} if the live tier is below the minimum.
     */
    @Around("@annotation(com.taarifu.common.security.RequiresTier)")
    public Object enforceTier(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        RequiresTier annotation = signature.getMethod().getAnnotation(RequiresTier.class);
        int requiredRank = parseRank(annotation.value());

        UUID publicId = CurrentUser.current()
                .map(CurrentUser::publicId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));

        int liveRank = tierResolver.resolveLiveTierRank(publicId);
        if (liveRank < requiredRank) {
            audit.record(AuditEvent.Builder
                    .of(AuditEventType.AUTHZ_TIER_DENIED, AuditOutcome.DENIED)
                    .actor(publicId)
                    .subject(publicId)
                    .reason("required=T" + requiredRank + ",live=T" + liveRank)
                    .build());
            // The required tier is surfaced in the localised message args for the UI prompt.
            throw new ApiException(ErrorCode.TIER_TOO_LOW, "T" + requiredRank);
        }
        return joinPoint.proceed();
    }

    /** Parses a tier token like {@code "T3"} into its rank 0–3. */
    private static int parseRank(String tier) {
        if (tier == null || tier.length() != 2 || tier.charAt(0) != 'T') {
            throw new IllegalArgumentException("Invalid @RequiresTier value: " + tier);
        }
        int rank = tier.charAt(1) - '0';
        if (rank < 0 || rank > 3) {
            throw new IllegalArgumentException("Tier out of range T0..T3: " + tier);
        }
        return rank;
    }
}
