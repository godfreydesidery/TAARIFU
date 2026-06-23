package com.taarifu.common.security;

import java.util.UUID;

/**
 * The scope-aware authorization seam — MF-3 (AUTH-DESIGN §8, ADR-0011 §4, D13/D16).
 *
 * <p>Responsibility: turns RBAC into <b>role × scope</b>. {@code hasRole('RESPONDER_AGENT')} alone would
 * let an agent act on <i>any</i> area/category; these checks read the caller's <b>live</b>
 * {@code RoleAssignment} scope (area/category/constituency) from the database — never the token — and
 * answer "may this caller act here?". Registered as the Spring bean named {@code taarifuAuthz} so method
 * security can call it: {@code @PreAuthorize("@taarifuAuthz.canActOnArea(#areaPublicId)")}.</p>
 *
 * <p>Deny-by-default: an unknown grant, an empty match, or no active assignment → {@code false}. An
 * <b>empty</b> scope set on an active assignment means "unrestricted within that role" (an area-less
 * agent grant covers all areas) — that is the one permissive case, and it is explicit.</p>
 *
 * <p>The interface lives in {@code common.security} (every module's authz depends on it); the live-query
 * implementation lives in {@code identity} and is wired as {@code taarifuAuthz} (AUTH-DESIGN §2). The
 * per-endpoint {@code @PreAuthorize} annotations land with the modules that own those resources; the
 * bean + contract + {@link #isNotSelf} ship now (the conflict check is needed the moment any
 * "act on someone" endpoint exists).</p>
 */
public interface ScopeGuard {

    /**
     * @param areaPublicId the geography area the action targets.
     * @return {@code true} if the caller holds an active assignment that is area-unrestricted, contains
     *         this area, or contains an ancestor of it (a District grant covers its Wards).
     */
    boolean canActOnArea(UUID areaPublicId);

    /**
     * @param categoryPublicId the issue category the action targets.
     * @return {@code true} if the caller holds an active assignment that is category-unrestricted or
     *         contains this category.
     */
    boolean canActOnCategory(UUID categoryPublicId);

    /**
     * @param constituencyPublicId the electoral unit the action targets.
     * @return {@code true} if the caller holds an active assignment scoped to this constituency.
     */
    boolean canActInConstituency(UUID constituencyPublicId);

    /**
     * Conflict-of-interest check (D13/D16): blocks rating/resolving/answering/moderating self or own
     * work. Pairs with the audit trail (multi-hat actions are audited).
     *
     * @param subjectPublicId the public id of the entity/person being acted upon.
     * @return {@code true} if the subject is <b>not</b> the caller (the action is permitted on this axis);
     *         {@code false} if it would be a self-action.
     */
    boolean isNotSelf(UUID subjectPublicId);
}
