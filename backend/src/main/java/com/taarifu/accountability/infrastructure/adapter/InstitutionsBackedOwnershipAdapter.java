package com.taarifu.accountability.infrastructure.adapter;

import com.taarifu.accountability.domain.port.RepresentativeOwnershipPort;
import com.taarifu.institutions.api.RepresentativeQueryApi;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The <b>real, wired-by-default</b> {@link RepresentativeOwnershipPort} for the right-of-reply ownership fence
 * (PRD §10 Epic M6, US-6.2; the D16 conflict-of-interest rule). It resolves "is this account the representative
 * behind this rating's subject?" by delegating to <b>institutions'</b> published
 * {@link RepresentativeQueryApi#ownsRepresentative(UUID, UUID)} — the module that owns the account ↔
 * representative link (the rep's {@code profileId}, §6.4).
 *
 * <p><b>WHY this replaces the deny-stub as the default:</b> with the institutions ownership query now published,
 * the self-reply path is fully wired — a rated representative can post their own right-of-reply, while still
 * being unable to reply to a rating about a rival (the fence). This adapter is a plain {@code @Component}, so it
 * registers unconditionally; the {@code DenyByDefaultOwnershipAdapter} now backs off via
 * {@code @ConditionalOnMissingBean} and only takes over if this real adapter is ever absent (fail-safe — the
 * worst case stays "self-reply temporarily unavailable", never "anyone can speak as a representative",
 * CLAUDE.md §12).</p>
 *
 * <h3>Boundary discipline (ADR-0013 §1)</h3>
 * <p>This adapter lives in accountability's {@code infrastructure.adapter} and depends only on institutions'
 * <b>published api</b> port — a sanctioned cross-module {@code api → api} edge (accountability → institutions),
 * never a reach into institutions'/identity's {@code domain}/{@code infrastructure}. The two-hop resolution
 * (account → profile via identity, profile → representative via institutions) is institutions' to perform behind
 * its port; this adapter just asks the question. The port stays in accountability's {@code domain} so the
 * service depends on the abstraction, not on institutions (DIP) — and the ModuleBoundaryTest stays GREEN
 * (cross-module {@code api} target is the permitted contract).</p>
 *
 * <p><b>🔒 PII:</b> consumes/returns opaque public ids only; logs nothing (the predicate is two UUIDs through a
 * published port). The conflict-of-interest <i>audit</i> on a denial is the service's responsibility, with
 * refs/codes only (PRD §18).</p>
 */
@Component
public class InstitutionsBackedOwnershipAdapter implements RepresentativeOwnershipPort {

    private final RepresentativeQueryApi representativeQueryApi;

    /**
     * @param representativeQueryApi institutions' published port resolving the account ↔ representative link
     *                               ({@code ownsRepresentative}); never institutions' repositories (ADR-0013 §1).
     */
    public InstitutionsBackedOwnershipAdapter(RepresentativeQueryApi representativeQueryApi) {
        this.representativeQueryApi = representativeQueryApi;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates verbatim to {@link RepresentativeQueryApi#ownsRepresentative(UUID, UUID)} — which is total and
     * fails closed (returns {@code false}, never throws, for a {@code null}/unknown id, an unlinked rep, or an
     * account that is not the rep's). So a {@code null} caller, a phantom representative, or a mismatch all yield
     * a clean {@code false}, which the service turns into a localised conflict-of-interest rejection.</p>
     */
    @Override
    public boolean isLinkedAccountOf(UUID accountPublicId, UUID representativePublicId) {
        return representativeQueryApi.ownsRepresentative(accountPublicId, representativePublicId);
    }
}
