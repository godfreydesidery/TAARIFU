package com.taarifu.common.persistence;

import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Supplies the current actor's {@code publicId} for JPA auditing of {@code createdBy}/{@code updatedBy}
 * on {@link com.taarifu.common.domain.model.BaseEntity} (ARCHITECTURE.md §4.2).
 *
 * <p>Responsibility: resolves "who is acting" for the audit columns. When an authenticated principal
 * is present and exposes a {@code UUID} (the user's {@code publicId} carried as the JWT subject —
 * ADR-0006/0007), that id is used; otherwise the {@link #SYSTEM_ACTOR} is recorded.</p>
 *
 * <p>WHY a fixed system actor (not {@code null}) before auth lands: every state change must attribute
 * to <i>someone</i> for the immutable audit trail (PRD §18). In this foundation increment there is no
 * auth yet, so reference-data/system writes are attributed to {@link #SYSTEM_ACTOR}. Once the auth
 * increment wires the JWT principal, real user ids flow through here with no change to entities.</p>
 */
@Component("auditorAware")
public class AuditorAwareImpl implements AuditorAware<UUID> {

    /**
     * Stable sentinel id attributed to system/seed/unauthenticated writes. A fixed, well-known value
     * (all-zero UUID) makes such rows easy to identify and audit.
     */
    public static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

    /**
     * @return the acting principal's {@code publicId}, or {@link #SYSTEM_ACTOR} when no authenticated
     *         UUID-bearing principal is present. Never {@link Optional#empty()} so audit columns are
     *         always populated.
     */
    @Override
    public Optional<UUID> getCurrentAuditor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof UUID principalId) {
            return Optional.of(principalId);
        }
        return Optional.of(SYSTEM_ACTOR);
    }
}
