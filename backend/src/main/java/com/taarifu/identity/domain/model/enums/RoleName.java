package com.taarifu.identity.domain.model.enums;

/**
 * The platform role catalogue (PRD §7.2; ARCHITECTURE.md §6.2).
 *
 * <p>Responsibility: enumerates the assignable roles. Roles are <b>additive on a single account</b>
 * (§6.4, D15) — a citizen who becomes an MP keeps one account and gains the {@link #REPRESENTATIVE}
 * role. Authorization combines role (RBAC) with scope ({@code RoleAssignment.areaIds/categoryIds/
 * constituencyId}) and trust tier (PRD §7.1).</p>
 *
 * <p>WHY {@code RESPONDER_AGENT}/{@code RESPONDER_ADMIN} replace the legacy "Area Official": the
 * responder directory generalises government/parastatal/private responders (D20, §24); the old role is
 * subsumed.</p>
 */
public enum RoleName {

    /** Unauthenticated visitor (default, no account). */
    GUEST,

    /** Registered citizen — the base role of every account. */
    CITIZEN,

    /** Member of an organisation profile. */
    ORG_MEMBER,

    /** Administrator of an organisation profile. */
    ORG_ADMIN,

    /** Elected representative (MP/Councillor/exec) acting in civic capacity. */
    REPRESENTATIVE,

    /** Staff agent of a responder (govt/parastatal/private), scoped to areas/categories (D20). */
    RESPONDER_AGENT,

    /** Administrator of a responder workspace (D20). */
    RESPONDER_ADMIN,

    /** Content/safety moderator. */
    MODERATOR,

    /** Platform administrator (reference data, taxonomy, role granting). */
    ADMIN,

    /** Super-administrator (root); the highest-trust operational role. */
    ROOT
}
