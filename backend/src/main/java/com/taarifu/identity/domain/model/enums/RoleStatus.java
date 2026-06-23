package com.taarifu.identity.domain.model.enums;

/**
 * Lifecycle status of a {@code RoleAssignment} (PRD §7, §9.1).
 *
 * <p>Responsibility: a granted role is not always immediately effective — a representative or responder
 * claim may await verification, and roles end (a former MP). This status captures that lifecycle
 * separately from the account's {@code UserStatus}, so a user can hold an {@link #ACTIVE} citizen role
 * while a representative claim is still {@link #PENDING_VERIFICATION}.</p>
 */
public enum RoleStatus {

    /** Granted but awaiting verification (e.g. a representative/responder claim). Not yet effective. */
    PENDING_VERIFICATION,

    /** Active and effective. */
    ACTIVE,

    /** Temporarily suspended. */
    SUSPENDED,

    /** Ended (e.g. term expired, staff left); retained for history/audit. */
    FORMER
}
